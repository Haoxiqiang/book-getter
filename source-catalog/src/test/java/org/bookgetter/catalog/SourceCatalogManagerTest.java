package org.bookgetter.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;

class SourceCatalogManagerTest {
    @Test void boundedDownloadAcceptsExactLimitAndRejectsExtraByte() throws Exception {
        byte[] body = "source".getBytes(StandardCharsets.UTF_8);
        assertEquals("source", new String(SourceCatalogManager.readLimited(
                new ByteArrayInputStream(body), body.length, "feed.json"), StandardCharsets.UTF_8));
        IOException failure = assertThrows(IOException.class, () -> SourceCatalogManager.readLimited(
                new ByteArrayInputStream(body), body.length - 1, "feed.json"));
        assertTrue(failure.getMessage().contains("feed.json"));
    }

    @Test void failedUpdateRemovesStagingAndKeepsCurrentPointer(@TempDir Path directory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/good", exchange -> {
            byte[] body = "{\"bookSourceUrl\":\"https://example.org/book\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/bad", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Files.writeString(directory.resolve("feeds.json"), "[{\"file\":\"good.json\",\"url\":\"http://127.0.0.1:"
                    + port + "/good\",\"allowHttp\":true},{\"file\":\"bad.json\",\"url\":\"http://127.0.0.1:"
                    + port + "/bad\",\"allowHttp\":true}]");
            Files.createDirectories(directory.resolve("snapshots/old"));
            Files.writeString(directory.resolve("CURRENT"), "old\n");
            assertThrows(IOException.class, () -> SourceCatalogManager.main(new String[]{"update", directory.toString()}));
            assertEquals("old", Files.readString(directory.resolve("CURRENT")).trim());
            try (var snapshots = Files.list(directory.resolve("snapshots"))) {
                assertEquals(java.util.List.of("old"), snapshots.map(path -> path.getFileName().toString()).toList());
            }
            try (var files = Files.list(directory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".CURRENT-")));
            }
        } finally { server.stop(0); }
    }

    @Test void successfulUpdatePublishesVerifiableSnapshot(@TempDir Path directory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            byte[] body = "{\"bookSourceUrl\":\"https://example.org/book\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            Files.writeString(directory.resolve("feeds.json"), "[{\"file\":\"feed.json\",\"url\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/feed\",\"allowHttp\":true}]");
            SourceCatalogManager.main(new String[]{"update", directory.toString()});
            SourceCatalogManager.main(new String[]{"verify", directory.toString()});
            String current = Files.readString(directory.resolve("CURRENT")).trim();
            assertTrue(current.matches("[0-9]{8}-[0-9]{6}-[a-f0-9]{8}"));
            try (var snapshots = Files.list(directory.resolve("snapshots"))) {
                assertEquals(java.util.List.of(current), snapshots.map(path -> path.getFileName().toString()).toList());
            }
        } finally { server.stop(0); }
    }

    @Test void pointerPublishFailureRemovesUnpublishedSnapshot(@TempDir Path directory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            byte[] body = "{\"bookSourceUrl\":\"https://example.org/book\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            Files.writeString(directory.resolve("feeds.json"), "[{\"file\":\"feed.json\",\"url\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/feed\",\"allowHttp\":true}]");
            Files.createDirectory(directory.resolve("CURRENT"));
            Files.writeString(directory.resolve("CURRENT/marker"), "keep");
            assertThrows(IOException.class, () -> SourceCatalogManager.main(new String[]{"update", directory.toString()}));
            assertEquals("keep", Files.readString(directory.resolve("CURRENT/marker")));
            try (var snapshots = Files.list(directory.resolve("snapshots"))) {
                assertEquals(0, snapshots.count());
            }
            try (var files = Files.list(directory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".CURRENT-")));
            }
        } finally { server.stop(0); }
    }
    @Test void configCheckWorksWithoutDownloadedSnapshot(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("feeds.json"), "[{\"file\":\"one.json\",\"url\":\"https://example.org/feed\"}]");
        SourceCatalogManager.main(new String[]{"check-config", directory.toString()});
        Files.writeString(directory.resolve("feeds.json"), "[{\"file\":\"one.json\",\"url\":\"http://example.org/feed\"}]");
        assertThrows(IllegalArgumentException.class,
                () -> SourceCatalogManager.main(new String[]{"check-config", directory.toString()}));
    }
    @Test void normalizedUrlHandlesCaseDefaultPortSlashAndFragment() {
        assertEquals("https://example.org/books", SourceCatalogManager.normalizedId("HTTPS://EXAMPLE.ORG:443/books/#frag"));
        assertEquals("http://example.org", SourceCatalogManager.normalizedId("http://Example.org:80/"));
    }

    @Test void symbolicBookSourceKeysRemainDistinctAndRssUrlsStayStrict() {
        assertEquals("key:必应搜书", SourceCatalogManager.normalizedId(" 必应搜书 "));
        assertEquals("key:必应搜书#自定义", SourceCatalogManager.normalizedId("必应搜书#自定义"));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.normalizedId("file:///secret"));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.rssSourceId(
                JsonParser.parseString("{\"sourceUrl\":\"搜索代理\"}")));
        JsonArray all = JsonParser.parseString("[{\"bookSourceUrl\":\"必应搜书\"},"
                + "{\"bookSourceUrl\":\"https://example.org\"}]").getAsJsonArray();
        JsonArray files = JsonParser.parseString("[{\"file\":\"mixed.json\",\"entries\":2}]").getAsJsonArray();
        assertEquals(2, SourceCatalogManager.merge(all, files).unique().size());
    }

    @Test void mergeKeepsFirstSourceAndRecordsDuplicate() {
        JsonArray all = JsonParser.parseString("[{\"bookSourceName\":\"first\",\"bookSourceUrl\":\"https://example.org/\"},"
                + "{\"bookSourceName\":\"second\",\"bookSourceUrl\":\"https://EXAMPLE.org:443\"}]").getAsJsonArray();
        JsonArray files = JsonParser.parseString("[{\"file\":\"one.json\",\"entries\":1},"
                + "{\"file\":\"two.json\",\"entries\":1}]").getAsJsonArray();
        var merged = SourceCatalogManager.merge(all, files);
        assertEquals(1, merged.unique().size());
        assertEquals("first", merged.unique().get(0).getAsJsonObject().get("bookSourceName").getAsString());
        assertEquals("one.json", merged.duplicates().get(0).getAsJsonObject().get("keptFrom").getAsString());
    }
    @Test void rssIsValidatedButExcludedFromBookMerge() {
        var rss = JsonParser.parseString("{\"sourceUrl\":\"http://example.org/rss\"}");
        assertEquals("http://example.org/rss", SourceCatalogManager.rssSourceId(rss));
        assertThrows(IllegalArgumentException.class, () -> SourceCatalogManager.rssSourceId(
                JsonParser.parseString("{\"bookSourceUrl\":\"https://example.org\"}")));
        JsonArray files = JsonParser.parseString("[{\"file\":\"rss.json\",\"entries\":1,\"importedEntries\":0}]").getAsJsonArray();
        assertEquals(0, SourceCatalogManager.merge(new JsonArray(), files).unique().size());
    }
}
