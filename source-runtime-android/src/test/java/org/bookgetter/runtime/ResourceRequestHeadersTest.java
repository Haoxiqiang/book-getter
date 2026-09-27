package org.bookgetter.runtime;

import static org.junit.Assert.*;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.bookgetter.core.Models.Kind;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.SourceImporter;
import org.junit.Test;

public final class ResourceRequestHeadersTest {
    private static final Source SOURCE = source();

    private static Source source() {
        Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://books.test/library\"}").sources().get(0);
        source.json().addProperty("header", """
                {"Authorization":"Bearer secret","Cookie":"session=secret","X-Token":"secret",
                 "Referer":"https://books.test/book?token=secret","User-Agent":"Reader"}
                """);
        return source;
    }

    @Test public void sameOriginKeepsSourceHeaders() {
        Map<String, String> headers = ResourceRequestHeaders.forUrl(SOURCE, "https://books.test/chapter");
        assertEquals("Bearer secret", headers.get("Authorization"));
        assertEquals("session=secret", headers.get("Cookie"));
        assertEquals("https://books.test/book?token=secret", headers.get("Referer"));
    }

    @Test public void foreignResourceAndRedirectTargetsReceiveOnlySafeHeaders() {
        Map<String, String> headers = ResourceRequestHeaders.forUrl(SOURCE, "https://cdn.test/image.jpg");
        assertFalse(headers.containsKey("Authorization"));
        assertFalse(headers.containsKey("Cookie"));
        assertFalse(headers.containsKey("X-Token"));
        assertEquals("https://books.test/", headers.get("Referer"));
        assertEquals("Reader", headers.get("User-Agent"));
        assertFalse(ResourceRequestHeaders.forUrl(SOURCE, "http://books.test/chapter").containsKey("Authorization"));
    }

    @Test public void redirectedDownloadRechecksHeadersOnEveryHop() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            foreign.enqueue(new MockResponse().setBody("file"));
            origin.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", foreign.url("/file")));
            origin.start();
            String sourceUrl = origin.url("/").toString();
            JsonObject json = new JsonObject();
            json.addProperty("bookSourceUrl", sourceUrl);
            json.addProperty("header", "{\"Authorization\":\"Bearer secret\",\"Cookie\":\"session=secret\","
                    + "\"X-Token\":\"secret\",\"User-Agent\":\"Reader\"}");
            Source source = new Source(sourceUrl, sourceUrl, Kind.TEXT, json);
            var output = new java.io.ByteArrayOutputStream();
            new ResourceDownloader(new OkHttpClient()).download(source,
                    new Resource(origin.url("/redirect").toString(), "File", null), output);
            assertEquals("file", output.toString(StandardCharsets.UTF_8));
            RecordedRequest received = foreign.takeRequest();
            assertNotNull(received);
            assertNull(received.getHeader("Authorization"));
            assertNull(received.getHeader("Cookie"));
            assertNull(received.getHeader("X-Token"));
            assertEquals("Reader", received.getHeader("User-Agent"));
        }
    }

    @Test public void htmlLoginPageCannotBecomeADownloadedImage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/octet-stream")
                    .setBody("<!doctype html><html><body>Login</body></html>"));
            server.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Source imageSource = new Source(SOURCE.id(), SOURCE.name(), Kind.IMAGE, SOURCE.json());
            assertThrows(IOException.class, () -> new ResourceDownloader(new OkHttpClient())
                    .download(imageSource, new Resource(server.url("/page.jpg").toString(), "Page", "image/*"), output));
            assertEquals(0, output.size());
        }
    }

    @Test public void validBinaryPrefixIsPreservedAfterInspection() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "image/jpeg")
                    .setBody(new okio.Buffer().write(new byte[] {(byte) 0xff, (byte) 0xd8, 0, 1, 2})));
            server.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new ResourceDownloader(new OkHttpClient()).download(SOURCE,
                    new Resource(server.url("/page.jpg").toString(), "Page", "image/*"), output);
            assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, 0, 1, 2}, output.toByteArray());
        }
    }
}
