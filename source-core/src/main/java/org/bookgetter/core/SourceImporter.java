package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.bookgetter.core.Models.*;

public final class SourceImporter {
    private static final Pattern HOST_CALL = Pattern.compile("\\bjava\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Set<String> PROVIDED_HOST = Set.of("ajax", "put", "md5Encode", "base64Encode", "timeFormatUTC");
    private static final Set<String> PARTIAL_HOST = Set.of("get", "post", "getString", "getElement",
            "getElements", "setContent", "t2s", "toast", "longToast", "startBrowserAwait",
            "base64Decode", "encodeURI");
    private SourceImporter() {}

    public static ImportResult parse(String text) {
        JsonElement root;
        try { root = JsonParser.parseString(text.trim()); }
        catch (Exception e) { throw new SourceFailure(Stage.IMPORT, "", null, "Invalid source JSON", e); }
        if (root.isJsonObject() && root.getAsJsonObject().has("sourceUrls")) {
            List<String> urls = new ArrayList<>();
            for (JsonElement value : root.getAsJsonObject().getAsJsonArray("sourceUrls")) {
                String url = value.getAsString().trim();
                if (!url.startsWith("https://") && !url.startsWith("http://"))
                    throw new SourceFailure(Stage.IMPORT, "", "sourceUrls", "Invalid source URL: " + url);
                urls.add(url);
            }
            return new ImportResult(List.of(), urls, List.of());
        }
        List<JsonElement> entries = new ArrayList<>();
        if (root.isJsonArray()) root.getAsJsonArray().forEach(entries::add); else entries.add(root);
        List<Source> sources = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonElement entry = entries.get(i);
            if (!entry.isJsonObject()) {
                diagnostics.add(new Diagnostic(Stage.IMPORT, "INVALID_ENTRY", "Entry " + i + " is not an object", Severity.ERROR, null));
                continue;
            }
            JsonObject json = entry.getAsJsonObject();
            String id = string(json, "bookSourceUrl");
            if (id == null || id.isBlank()) {
                diagnostics.add(new Diagnostic(Stage.IMPORT, "MISSING_ID", "Entry " + i + " has no bookSourceUrl", Severity.ERROR, "bookSourceUrl"));
                continue;
            }
            int kindValue = json.has("bookSourceType") ? json.get("bookSourceType").getAsInt() : 0;
            Kind kind = kindValue >= 0 && kindValue < Kind.values().length ? Kind.values()[kindValue] : Kind.TEXT;
            Source source = new Source(id, string(json, "bookSourceName") == null ? id : string(json, "bookSourceName"), kind, json.deepCopy());
            sources.add(source);
            diagnostics.addAll(preflight(source));
        }
        return new ImportResult(sources, List.of(), diagnostics);
    }

    public static List<Diagnostic> preflight(Source source) {
        List<Diagnostic> result = new ArrayList<>();
        if (source.isJsSource()) result.add(new Diagnostic(Stage.IMPORT, "BASIC_MAIN_JS",
                "Only the documented mainJs function and host-method subset is available", "mainJs"));
        for (String field : List.of("exploreUrl", "exploreScreen", "ruleExplore", "ruleReview", "loginCheckJs", "coverDecodeJs")) {
            if (source.json().has(field)) result.add(new Diagnostic(Stage.IMPORT, "DEFERRED_CAPABILITY", field + " is not executed", field));
        }
        if (source.json().has("concurrentRate")) result.add(new Diagnostic(Stage.IMPORT, "DEFERRED_CAPABILITY", "Per-source concurrentRate is not enforced", "concurrentRate"));
        JsonObject content = source.rules("ruleContent");
        if (content != null) for (String field : List.of("payAction", "contentBatch", "imageDecode", "callBackJs", "webJs")) {
            if (content.has(field)) result.add(new Diagnostic(Stage.IMPORT, "DEFERRED_CAPABILITY", "ruleContent." + field + " is not executed", "ruleContent." + field));
        }
        if (!source.isJsSource() && (source.string("searchUrl") == null || source.string("searchUrl").isBlank()))
            result.add(new Diagnostic(Stage.SEARCH, "NO_SEARCH_URL", "Source cannot perform search", "searchUrl"));
        for (String field : List.of("mainJs", "jsLib", "searchUrl"))
            if (source.string(field) != null) addHostDiagnostics(result, field, source.string(field));
        for (String section : List.of("ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent")) {
            JsonObject rules = source.rules(section);
            if (rules != null) for (var entry : rules.entrySet())
                addHostDiagnostics(result, section + "." + entry.getKey(), entry.getValue().toString());
        }
        return result;
    }

    private static void addHostDiagnostics(List<Diagnostic> diagnostics, String field, String rule) {
        Set<String> partial = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = HOST_CALL.matcher(rule);
        while (matcher.find()) {
            String method = matcher.group(1);
            if (PROVIDED_HOST.contains(method)) continue;
            (PARTIAL_HOST.contains(method) ? partial : missing).add(method);
        }
        Stage stage = field.equals("searchUrl") || field.startsWith("ruleSearch.") ? Stage.SEARCH
                : field.startsWith("ruleBookInfo.") ? Stage.DETAIL
                : field.startsWith("ruleToc.") ? Stage.CATALOG
                : field.startsWith("ruleContent.") ? Stage.CONTENT : Stage.IMPORT;
        if (!partial.isEmpty()) diagnostics.add(new Diagnostic(stage, "PARTIAL_JS_HOST",
                "JavaScript host methods have limited or interactive-only support: " + String.join(", ", partial),
                Severity.WARNING, field));
        if (!missing.isEmpty()) diagnostics.add(new Diagnostic(stage, "UNSUPPORTED_JS_HOST",
                "JavaScript host methods are unavailable: " + String.join(", ", missing),
                Severity.WARNING, field));
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }
}
