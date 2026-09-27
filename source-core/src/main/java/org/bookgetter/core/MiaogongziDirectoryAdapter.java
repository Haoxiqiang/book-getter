package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Resolves the Miaogongzi source-management RSS entry into selectable BookSource feeds. */
public final class MiaogongziDirectoryAdapter {
    private static final String HOST = "yuedu.miaogongzi.net";
    private static final int MAX_COLLECTIONS = 100;
    private MiaogongziDirectoryAdapter() {}

    public record Collection(String name, String url) {}

    /** Returns the supported directory page, or null for an ordinary BookSource JSON file. */
    public static String directoryPage(String json) {
        JsonElement root;
        try { root = JsonParser.parseString(json); }
        catch (Exception ignored) { return null; }
        if (root.isJsonArray()) {
            if (root.getAsJsonArray().size() != 1) return null;
            root = root.getAsJsonArray().get(0);
        }
        if (!root.isJsonObject()) return null;
        JsonObject source = root.getAsJsonObject();
        if (source.has("bookSourceUrl") || !source.has("sourceUrl")) return null;
        String page;
        try { page = source.get("sourceUrl").getAsString(); }
        catch (Exception ignored) { return null; }
        URI uri;
        try { uri = validHttpUrl(page); }
        catch (Exception ignored) { return null; }
        if (!HOST.equalsIgnoreCase(uri.getHost()) || !"/gx.html".equals(uri.getPath())) return null;
        return page;
    }

    public static List<Collection> discover(String html) {
        Map<String, Collection> found = new LinkedHashMap<>();
        for (Element anchor : Jsoup.parse(html).select("a[href]")) {
            String url = importUrl(anchor.attr("href"));
            if (url == null) continue;
            Element card = anchor.closest("div.aui-flex");
            String name = card == null ? "" : card.selectFirst("h1") == null ? "" : card.selectFirst("h1").text().trim();
            if (name.isBlank()) throw new IllegalArgumentException("A book-source collection has no title");
            Collection previous = found.putIfAbsent(name, new Collection(name, url));
            if (previous != null && !previous.url().equals(url))
                throw new IllegalArgumentException("Duplicate collection title: " + name);
            if (found.size() > MAX_COLLECTIONS) throw new IllegalArgumentException("Too many source collections");
        }
        if (found.isEmpty()) throw new IllegalArgumentException("No book-source collection links found");
        return List.copyOf(found.values());
    }

    private static String importUrl(String raw) {
        URI link;
        try { link = URI.create(raw.trim()); }
        catch (Exception ignored) { return null; }
        if (!"yuedu".equalsIgnoreCase(link.getScheme()) || !"booksource".equalsIgnoreCase(link.getHost())
                || !"/importonline".equals(link.getPath()) || link.getRawQuery() == null) return null;
        for (String parameter : link.getRawQuery().split("&")) {
            int equal = parameter.indexOf('=');
            if (equal < 0 || !parameter.substring(0, equal).equals("src")) continue;
            String encoded = parameter.substring(equal + 1);
            // The site embeds ordinary percent-encoded paths directly in src. Decode only
            // when the whole URL itself was encoded as a query value.
            String value = encoded.startsWith("https://") || encoded.startsWith("http://")
                    ? encoded : URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            validHttpUrl(value);
            return value;
        }
        throw new IllegalArgumentException("Book-source import link has no src URL");
    }

    private static URI validHttpUrl(String value) {
        URI uri = URI.create(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid collection URL: " + value);
        return uri;
    }
}
