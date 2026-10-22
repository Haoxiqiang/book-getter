package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.RssModels.*;

/** Native RssSource importer. Never interprets bookSourceType as an RSS media type. */
public final class RssImporter {
    private RssImporter() {}
    public record Result(List<RssSource> sources, List<Diagnostic> diagnostics) {
        public Result { sources = List.copyOf(sources); diagnostics = List.copyOf(diagnostics); }
    }
    public static Result parse(String text) {
        if (text == null || text.length() > 16 * 1024 * 1024
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
            throw new SourceFailure(Stage.IMPORT, "", null, "Invalid RSS source size");
        JsonElement root;
        try { root = JsonParser.parseString(text); }
        catch (RuntimeException error) { throw new SourceFailure(Stage.IMPORT, "", null, "Invalid RSS source JSON", error); }
        List<JsonElement> entries = new ArrayList<>();
        if (root.isJsonArray()) root.getAsJsonArray().forEach(entries::add); else entries.add(root);
        if (entries.size() > 10_000) throw new SourceFailure(Stage.IMPORT, "", null, "Too many RSS sources");
        var sources = new LinkedHashMap<String, RssSource>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (JsonElement entry : entries) {
            try {
                JsonObject json = entry.getAsJsonObject();
                if (json.has("bookSourceUrl")) throw new IllegalArgumentException("BookSource belongs in the book importer");
                String id = SourceFields.text(json, "sourceUrl");
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing sourceUrl");
                int type = SourceFields.integer(json, "type", 0);
                if (type < 0 || type >= RssKind.values().length) throw new IllegalArgumentException("Invalid RSS type");
                String name = json.has("sourceName") ? SourceFields.text(json, "sourceName") : id;
                List<Diagnostic> warnings = new ArrayList<>();
                for (String field : List.of("loginCheckJs", "coverDecodeJs"))
                    if (json.has(field) && !json.get(field).isJsonNull() && !json.get(field).getAsString().isBlank())
                        warnings.add(new Diagnostic(Stage.IMPORT, "UNSUPPORTED_RSS_CAPABILITY",
                                field + " is not executed by the RSS core", field));
                sources.put(id, new RssSource(id, name == null ? id : name, RssKind.values()[type], json.deepCopy()));
                diagnostics.addAll(warnings);
            } catch (RuntimeException error) {
                diagnostics.add(new Diagnostic(Stage.IMPORT, "INVALID_RSS_ENTRY", "Invalid RSS source entry", Severity.ERROR, "sourceUrl"));
            }
        }
        return new Result(new ArrayList<>(sources.values()), diagnostics);
    }
}
