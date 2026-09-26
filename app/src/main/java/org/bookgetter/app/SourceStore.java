package org.bookgetter.app;

import android.content.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SourceImporter;
import org.bookgetter.core.Transport;

final class SourceStore {
    private final Context context;
    SourceStore(Context context) { this.context = context; }
    List<Source> all() {
        String json = context.getSharedPreferences("sources", 0).getString("json", "[]");
        return SourceImporter.parse(json).sources();
    }
    ImportResult importText(String json, Transport transport) throws Exception {
        Map<String, Source> updated = new LinkedHashMap<>();
        for (Source source : all()) updated.put(source.id(), source);
        List<Diagnostic> diagnostics = new ArrayList<>();
        int imported = importRecursive(json, transport, updated, diagnostics, 0);
        JsonArray array = new JsonArray();
        for (Source source : updated.values()) array.add(source.json());
        context.getSharedPreferences("sources", 0).edit().putString("json", array.toString()).apply();
        return new ImportResult(new ArrayList<>(updated.values()), List.of(), diagnostics);
    }
    private int importRecursive(String text, Transport transport, Map<String, Source> updated, List<Diagnostic> diagnostics, int depth) throws Exception {
        if (depth > 3) throw new IllegalArgumentException("sourceUrls nesting too deep");
        ImportResult parsed = SourceImporter.parse(text);
        diagnostics.addAll(parsed.diagnostics());
        for (Source source : parsed.sources()) updated.put(source.id(), source);
        for (String url : parsed.sourceUrls()) {
            String nested = transport.fetch(new FetchRequest(url)).body();
            importRecursive(nested, transport, updated, diagnostics, depth + 1);
        }
        return parsed.sources().size();
    }
}
