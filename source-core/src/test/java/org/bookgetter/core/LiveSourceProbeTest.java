package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.bookgetter.core.Models.Kind;
import org.bookgetter.core.Models.Source;

final class LiveSourceProbeTest {
    @Test void sourceFilterSelectsSeveralDistinctSitesWithoutMatchingEverything() {
        Source first = new Source("https://first.example", "全本小说①", Kind.TEXT, new JsonObject());
        Source second = new Source("https://second.example", "文学迷", Kind.TEXT, new JsonObject());
        Source other = new Source("https://other.example", "其它站点", Kind.TEXT, new JsonObject());
        assertTrue(LiveSourceProbe.matchesSource(first, "全本小说①, 文学迷"));
        assertTrue(LiveSourceProbe.matchesSource(second, "全本小说①, 文学迷"));
        assertFalse(LiveSourceProbe.matchesSource(other, "全本小说①, 文学迷"));
        assertFalse(LiveSourceProbe.matchesSource(other, " , "));
        assertTrue(LiveSourceProbe.matchesSource(other, ""));
    }

    @Test void gzipResponseIsDecodedBeforeRuleParsing() throws Exception {
        byte[] json = "{\"data\":[{\"novelName\":\"Fixture\"}]}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(buffer)) { compressed.write(json); }
        assertArrayEquals(json, LiveSourceProbe.decodeBody(buffer.toByteArray(), "gzip"));
        assertArrayEquals(json, LiveSourceProbe.decodeBody(json, "identity"));
    }

    @Test void directBookProbeRequiresPublicHttpAddress() {
        assertTrue(LiveSourceProbe.validHttpBookUrl("https://books.example/book/123"));
        assertFalse(LiveSourceProbe.validHttpBookUrl("file:///tmp/book.html"));
        assertFalse(LiveSourceProbe.validHttpBookUrl("https://user:pass@books.example/book/123"));
        assertFalse(LiveSourceProbe.validHttpBookUrl("javascript:alert(1)"));
    }

    @Test void redirectTraceShowsPostChangingToGetOn301() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/new", exchange -> {
            byte[] body = exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/old");
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.ofString("searchkey=fixture")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("GET", response.body());
            var rows = LiveSourceProbe.redirectRows(response);
            assertTrue(rows.get(0).contains("REDIRECT\tPOST\t301"));
            assertTrue(rows.get(1).contains("REDIRECT_FINAL\tGET\t200"));
        } finally { server.stop(0); }
    }
}
