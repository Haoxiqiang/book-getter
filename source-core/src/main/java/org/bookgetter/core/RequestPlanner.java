package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bookgetter.core.Models.*;

public final class RequestPlanner {
    private static final Pattern JS_BLOCK = Pattern.compile("<js>([\\s\\S]*?)</js>");
    private final ScriptRuntime script;
    public RequestPlanner(ScriptRuntime script) { this.script = script; }

    public FetchRequest plan(String template, Source source, String key, int page, String base) {
        if (template == null || template.isBlank()) throw new SourceFailure(Stage.SEARCH, source.id(), "searchUrl", "Empty request URL");
        String text = template.trim();
        Map<String, Object> bindings = Map.of("key", key, "page", page, "baseUrl", base);
        if (text.startsWith("@js:")) text = String.valueOf(script.evaluate(text.substring(4), bindings, source));
        Matcher matcher = JS_BLOCK.matcher(text);
        StringBuffer replaced = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(replaced, Matcher.quoteReplacement(String.valueOf(script.evaluate(matcher.group(1), bindings, source))));
        matcher.appendTail(replaced);
        text = replaced.toString();
        int chainedJs = text.indexOf("\n@js:");
        if (chainedJs >= 0) {
            String initial = text.substring(0, chainedJs).trim();
            Map<String, Object> withResult = new LinkedHashMap<>(bindings);
            withResult.put("result", initial);
            Object evaluated = script.evaluate(text.substring(chainedJs + 5), withResult, source);
            text = evaluated == null ? "" : evaluated.toString();
        }
        Charset keywordCharset = StandardCharsets.UTF_8;
        int preliminaryOptionStart = optionStart(text);
        if (preliminaryOptionStart >= 0) {
            try {
                JsonObject preliminaryOptions = JsonParser.parseString(text.substring(preliminaryOptionStart + 1)).getAsJsonObject();
                String name = option(preliminaryOptions, "charset", null);
                if (name != null && !name.isBlank()) keywordCharset = Charset.forName(name);
            } catch (Exception ignored) { /* The final options parse reports malformed JSON. */ }
        }
        Matcher placeholders = Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL).matcher(text);
        StringBuffer expanded = new StringBuffer();
        while (placeholders.find()) {
            String expression = placeholders.group(1).trim();
            Object evaluated = expression.equals("key") && (preliminaryOptionStart < 0 || placeholders.start() < preliminaryOptionStart)
                    ? encode(key, keywordCharset)
                    : expression.equals("key") ? key
                    : expression.equals("page") ? page : script.evaluate(expression, bindings, source);
            String value = evaluated instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue())
                    ? Long.toString(number.longValue()) : String.valueOf(evaluated);
            placeholders.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(expanded);
        text = expanded.toString();
        int optionStart = optionStart(text);
        String rawUrl = replacePageChoices(optionStart < 0 ? text : text.substring(0, optionStart), page);
        JsonObject options = optionStart < 0 ? null : JsonParser.parseString(text.substring(optionStart + 1)).getAsJsonObject();
        Map<String, String> headers = new LinkedHashMap<>();
        if (source.string("header") != null) {
            try { JsonParser.parseString(source.string("header")).getAsJsonObject().entrySet().forEach(e -> headers.put(e.getKey(), e.getValue().getAsString())); }
            catch (Exception ignored) { /* preflight/runtime will show request failure if required */ }
        }
        if (options != null && options.has("headers")) mergeHeaders(headers, options.get("headers"));
        String method = option(options, "method", "GET").toUpperCase();
        String body = bodyOption(options);
        String charsetName = option(options, "charset", null);
        if (method.equals("POST") && body != null && !body.isBlank()
                && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))
                && (body.stripLeading().startsWith("{") || body.stripLeading().startsWith("[")))
            headers.put("Content-Type", "application/json; charset=UTF-8");
        if (method.equals("POST") && body != null && !body.isBlank()
                && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))
                && !body.stripLeading().startsWith("{") && !body.stripLeading().startsWith("[")
                && !body.stripLeading().startsWith("<")) {
            Charset formCharset = charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
            body = encodeForm(body, formCharset);
            headers.put("Content-Type", "application/x-www-form-urlencoded; charset=" + formCharset.name());
        }
        long webViewDelay = 0;
        if (options != null && options.has("webViewDelayTime"))
            webViewDelay = Math.max(0, options.get("webViewDelayTime").getAsLong());
        return new FetchRequest(encodeUnsafeUrl(RuleEvaluator.absolute(base, rawUrl)), method, headers,
                body, charsetName,
                useWebView(options), webViewDelay);
    }

    /** Request options follow the URL; `,{` inside an embedded JS expression is URL data. */
    private static int optionStart(String text) {
        int embedded = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.startsWith("{{", i)) { embedded++; i++; continue; }
            if (embedded > 0 && text.startsWith("}}", i)) { embedded--; i++; continue; }
            if (embedded == 0 && text.startsWith(",{", i)) return i;
        }
        return -1;
    }

    /** Legado's `<first,second,...>` URL syntax uses the current one-based page. */
    private static String replacePageChoices(String text, int page) {
        Matcher choices = Pattern.compile("<([^<>]+)>").matcher(text);
        StringBuffer result = new StringBuffer();
        while (choices.find()) {
            String[] values = choices.group(1).split(",", -1);
            choices.appendReplacement(result, Matcher.quoteReplacement(
                    values[Math.min(Math.max(page - 1, 0), values.length - 1)].trim()));
        }
        choices.appendTail(result);
        return result.toString();
    }

    /** URI consumers require UTF-8 escaping when JS returns raw Han text. */
    private static String encodeUnsafeUrl(String raw) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < raw.length();) {
            int codepoint = raw.codePointAt(offset);
            offset += Character.charCount(codepoint);
            if (codepoint > 0x7f || Character.isWhitespace(codepoint) || codepoint < 0x20) {
                for (byte value : new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8))
                    result.append('%').append(String.format("%02X", value & 0xff));
            } else result.append((char) codepoint);
        }
        return result.toString();
    }

    private static String encodeForm(String body, Charset charset) {
        StringBuilder result = new StringBuilder();
        for (String field : body.split("&", -1)) {
            if (result.length() > 0) result.append('&');
            int equal = field.indexOf('=');
            if (equal < 0) result.append(encodeValue(field, charset));
            else result.append(encodeValue(field.substring(0, equal), charset))
                    .append('=').append(encodeValue(field.substring(equal + 1), charset));
        }
        return result.toString();
    }

    private static String encodeValue(String value, Charset charset) {
        if (value.matches("(?i).*(?:%[0-9a-f]{2}).*")) return value;
        return encode(value, charset);
    }

    private static String encode(String value, Charset charset) {
        try { return URLEncoder.encode(value, charset.name()); }
        catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("Unsupported charset: " + charset.name(), impossible);
        }
    }

    private static String option(JsonObject object, String key, String fallback) {
        return object == null || !object.has(key) || object.get(key).isJsonNull() ? fallback : object.get(key).getAsString();
    }

    /** Legado UrlOption.useWebView treats any non-empty value other than false as enabled. */
    private static boolean useWebView(JsonObject options) {
        if (options == null || !options.has("webView") || options.get("webView").isJsonNull()) return false;
        if (!options.get("webView").isJsonPrimitive()) return true;
        String value = options.get("webView").getAsString();
        return !value.isEmpty() && !value.equals("false");
    }

    private static String bodyOption(JsonObject options) {
        if (options == null || !options.has("body") || options.get("body").isJsonNull()) return null;
        var body = options.get("body");
        return body.isJsonPrimitive() ? body.getAsString() : body.toString();
    }

    /** Legado's UrlOption accepts an object or a JSON-object string for headers. */
    private static void mergeHeaders(Map<String, String> target, JsonElement value) {
        try {
            JsonObject object = value.isJsonObject() ? value.getAsJsonObject()
                    : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    ? JsonParser.parseString(value.getAsString()).getAsJsonObject() : null;
            if (object != null) object.entrySet().forEach(entry ->
                    target.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (IllegalStateException | com.google.gson.JsonParseException ignored) {
            // Legado's getHeaderMap returns null for an unparseable header option.
        }
    }
}
