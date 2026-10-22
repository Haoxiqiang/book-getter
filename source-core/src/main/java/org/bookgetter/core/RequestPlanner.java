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
        Matcher placeholders = Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL).matcher(text);
        StringBuffer expanded = new StringBuffer();
        while (placeholders.find()) {
            String expression = placeholders.group(1).trim();
            Object evaluated = expression.equals("key") ? key
                    : expression.equals("page") ? page : script.evaluate(expression, bindings, source);
            String value = evaluated instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue())
                    ? Long.toString(number.longValue()) : String.valueOf(evaluated);
            placeholders.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(expanded);
        text = expanded.toString();
        int optionStart = RuleSyntax.urlOptionsStart(text);
        String rawUrl = replacePageChoices(optionStart < 0 ? text : text.substring(0, optionStart).stripTrailing(), page);
        JsonObject options = optionStart < 0 ? null : JsonParser.parseString(text.substring(optionStart + 1)).getAsJsonObject();
        Map<String, String> headers = new LinkedHashMap<>();
        if (source.string("header") != null) {
            try {
                String header = source.string("header").trim();
                if (header.startsWith("@js:")) header = String.valueOf(script.evaluate(header.substring(4), bindings, source));
                else if (header.startsWith("<js>") && header.endsWith("</js>"))
                    header = String.valueOf(script.evaluate(header.substring(4, header.length() - 5), bindings, source));
                JsonParser.parseString(header).getAsJsonObject().entrySet().forEach(e -> putHeader(headers, e.getKey(), e.getValue().getAsString()));
            }
            catch (Exception ignored) { /* preflight/runtime will show request failure if required */ }
        }
        Map<String, String> defaults = new LinkedHashMap<>(headers);
        if (options != null && options.has("headers")) {
            Map<String, String> explicit = new LinkedHashMap<>();
            mergeHeaders(explicit, options.get("headers"));
            explicit.forEach((name, value) -> {
                defaults.keySet().removeIf(keyName -> keyName.equalsIgnoreCase(name));
                putHeader(headers, name, value);
            });
        }
        String method = option(options, "method", "GET").toUpperCase();
        String body = bodyOption(options);
        String charsetName = option(options, "charset", null);
        if (method.equals("POST") && body != null && !body.isBlank()
                && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))
                && (body.stripLeading().startsWith("{") || body.stripLeading().startsWith("["))) {
            putHeader(headers, "Content-Type", "application/json; charset=UTF-8");
            defaults.put("Content-Type", headers.get("Content-Type"));
        }
        if (method.equals("POST") && body != null && !body.isBlank()
                && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))
                && !body.stripLeading().startsWith("{") && !body.stripLeading().startsWith("[")
                && !body.stripLeading().startsWith("<")) {
            Charset formCharset = "escape".equals(charsetName) ? null : charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
            body = encodeForm(body, formCharset, charsetName == null || charsetName.isBlank());
            headers.put("Content-Type", "application/x-www-form-urlencoded" + (formCharset == null ? "" : "; charset=" + formCharset.name()));
            defaults.put("Content-Type", headers.get("Content-Type"));
        }
        long webViewDelay = 0;
        if (options != null && options.has("webViewDelayTime"))
            webViewDelay = Math.max(0, options.get("webViewDelayTime").getAsLong());
        boolean followRedirects = options == null || !options.has("followRedirects")
                || options.get("followRedirects").isJsonNull() || options.get("followRedirects").getAsBoolean();
        long timeout = options == null || !options.has("timeout") || options.get("timeout").isJsonNull()
                ? 0 : options.get("timeout").getAsLong();
        return new FetchRequest(encodeRequestUrl(RuleEvaluator.absolute(base, rawUrl), charsetName), method, headers,
                body, "escape".equals(charsetName) ? null : charsetName,
                useWebView(options), webViewDelay, followRedirects, timeout,
                SourcePolicy.of(source).withDefaultHeaders(defaults));
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

    /** Encode query text after all JS expansion, using the request's charset. */
    private static String encodeRequestUrl(String raw, String charsetName) {
        int queryAt = raw.indexOf('?');
        int fragmentAt = raw.indexOf('#');
        if (queryAt < 0 || (fragmentAt >= 0 && fragmentAt < queryAt))
            return encodeUnsafe(raw, StandardCharsets.UTF_8, false);
        if ("escape".equals(charsetName)) {
            int end = fragmentAt < 0 ? raw.length() : fragmentAt;
            return encodeUnsafe(raw.substring(0, queryAt + 1), StandardCharsets.UTF_8, false)
                    + encodeForm(raw.substring(queryAt + 1, end), null, false)
                    + (fragmentAt < 0 ? "" : encodeUnsafe(raw.substring(fragmentAt), StandardCharsets.UTF_8, false));
        }
        Charset charset = charsetName == null || charsetName.isBlank()
                ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        int queryEnd = fragmentAt < 0 ? raw.length() : fragmentAt;
        return encodeUnsafe(raw.substring(0, queryAt + 1), StandardCharsets.UTF_8, false)
                + encodeUnsafe(raw.substring(queryAt + 1, queryEnd), charset, true)
                + (fragmentAt < 0 ? "" : encodeUnsafe(raw.substring(fragmentAt), StandardCharsets.UTF_8, false));
    }

    private static String encodeUnsafe(String raw, Charset charset, boolean query) {
        final String querySafe = "!$%&()*+,/:;=?@[\\]^`{|}~-._";
        final char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < raw.length();) {
            int codepoint = raw.codePointAt(offset);
            offset += Character.charCount(codepoint);
            boolean safe = query ? codepoint < 128 && (Character.isLetterOrDigit(codepoint)
                    || querySafe.indexOf(codepoint) >= 0)
                    : codepoint >= 0x20 && codepoint <= 0x7f && !Character.isWhitespace(codepoint);
            if (safe) result.appendCodePoint(codepoint);
            else for (byte value : new String(Character.toChars(codepoint)).getBytes(charset)) {
                int unsigned = value & 0xff;
                result.append('%').append(hex[unsigned >>> 4]).append(hex[unsigned & 15]);
            }
        }
        return result.toString();
    }

    private static String encodeForm(String body, Charset charset, boolean preserveEncoded) {
        StringBuilder result = new StringBuilder();
        for (String field : body.split("&", -1)) {
            if (result.length() > 0) result.append('&');
            int equal = field.indexOf('=');
            if (equal < 0) result.append(encodeValue(field, charset, preserveEncoded));
            else result.append(encodeValue(field.substring(0, equal), charset, preserveEncoded))
                    .append('=').append(encodeValue(field.substring(equal + 1), charset, preserveEncoded));
        }
        return result.toString();
    }

    private static String encodeValue(String value, Charset charset, boolean preserveEncoded) {
        if (preserveEncoded && isEncodedFormValue(value)) return value;
        return charset == null ? escape(value) : encode(value, charset);
    }

    // Legado EncoderUtils.escape uses UTF-16 units and lowercase hexadecimal.
    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') result.append(c);
            else result.append(c < 16 ? "%0" : c < 256 ? "%" : "%u").append(Integer.toHexString(c));
        }
        return result.toString();
    }

    private static boolean isEncodedFormValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c) || "*-._".indexOf(c) >= 0)) continue;
            if (c == '%' && i + 2 < value.length()
                    && Character.digit(value.charAt(i + 1), 16) >= 0
                    && Character.digit(value.charAt(i + 2), 16) >= 0) { i += 2; continue; }
            return false;
        }
        return true;
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

    private static void putHeader(Map<String, String> target, String name, String value) {
        target.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        target.put(name, value);
    }

    /** Legado's UrlOption accepts an object or a JSON-object string for headers. */
    private static void mergeHeaders(Map<String, String> target, JsonElement value) {
        try {
            JsonObject object = value.isJsonObject() ? value.getAsJsonObject()
                    : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    ? JsonParser.parseString(value.getAsString()).getAsJsonObject() : null;
            if (object != null) object.entrySet().forEach(entry ->
                    putHeader(target, entry.getKey(), entry.getValue().getAsString()));
        } catch (IllegalStateException | com.google.gson.JsonParseException ignored) {
            // Legado's getHeaderMap returns null for an unparseable header option.
        }
    }
}
