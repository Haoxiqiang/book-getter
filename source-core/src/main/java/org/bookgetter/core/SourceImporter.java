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
        if (source.isJsSource()) result.add(new Diagnostic(Stage.IMPORT, "BASIC_MAIN_JS", "Only basic mainJs functions and java.ajax are available", "mainJs"));
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
        Set<String> hostMethods = new LinkedHashSet<>();
        for (String field : List.of("mainJs", "searchUrl", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent")) {
            if (source.json().has(field)) {
                Matcher matcher = Pattern.compile("\\bjava\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(").matcher(source.json().get(field).toString());
                while (matcher.find()) if (!matcher.group(1).equals("ajax")) hostMethods.add(matcher.group(1));
            }
        }
        if (!hostMethods.isEmpty()) result.add(new Diagnostic(Stage.IMPORT, "UNSUPPORTED_JS_HOST", "JavaScript host methods need implementation: " + String.join(", ", hostMethods), "mainJs/rules"));
        return result;
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }
}
