package org.bookgetter.runtime;

import static org.junit.Assert.*;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipInputStream;
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
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public final class ResourceRequestHeadersTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
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
            ResourceDownloader.withClient(new OkHttpClient()).download(source,
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
            assertThrows(IOException.class, () -> ResourceDownloader.withClient(new OkHttpClient())
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
            ResourceDownloader.withClient(new OkHttpClient()).download(SOURCE,
                    new Resource(server.url("/page.jpg").toString(), "Page", "image/*"), output);
            assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, 0, 1, 2}, output.toByteArray());
        }
    }

    @Test public void comicZipUsesDetectedImageTypeInsteadOfUrlSuffix() throws Exception {
        byte[] png = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, png, 0, 8);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/octet-stream")
                    .setBody(new okio.Buffer().write(png)));
            server.start();
            Source images = new Source(SOURCE.id(), SOURCE.name(), Kind.IMAGE, SOURCE.json());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            java.io.File staging = temporary.newFolder();
            ResourceDownloader.withClient(new OkHttpClient(), staging, ResourceDownloader.MAX_STAGED_IMAGE_BYTES).downloadZip(images,
                    java.util.List.of(new Resource(server.url("/opaque.jpg").toString(), "Page", null)), output);
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
                assertEquals("0001.png", zip.getNextEntry().getName());
                assertArrayEquals(png, zip.readAllBytes());
                assertNull(zip.getNextEntry());
            }
            assertEquals(0, staging.listFiles().length);
        }
    }

    @Test public void laterImageFailureLeavesFinalZipEmptyAndRemovesStaging() throws Exception {
        byte[] png = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, png, 0, 8);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                    .setBody(new okio.Buffer().write(png)));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.start();
            Source images = new Source(SOURCE.id(), SOURCE.name(), Kind.IMAGE, SOURCE.json());
            java.io.File staging = temporary.newFolder();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ResourceDownloader downloader = ResourceDownloader.withClient(new OkHttpClient(), staging,
                    ResourceDownloader.MAX_STAGED_IMAGE_BYTES);
            assertThrows(IOException.class, () -> downloader.downloadZip(images, java.util.List.of(
                    new Resource(server.url("/one").toString(), "One", null),
                    new Resource(server.url("/two").toString(), "Two", null)), output));
            assertEquals(0, output.size());
            assertEquals(0, staging.listFiles().length);
        }
    }

    @Test public void stagedComicKeepsImageOrderAndBytes() throws Exception {
        byte[] first = new byte[40];
        byte[] second = new byte[41];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        System.arraycopy(signature, 0, first, 0, signature.length);
        System.arraycopy(signature, 0, second, 0, signature.length);
        second[second.length - 1] = 7;
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(first)));
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(second)));
            server.start();
            Source images = new Source(SOURCE.id(), SOURCE.name(), Kind.IMAGE, SOURCE.json());
            java.io.File staging = temporary.newFolder();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ResourceDownloader.withClient(new OkHttpClient(), staging, ResourceDownloader.MAX_STAGED_IMAGE_BYTES)
                    .downloadZip(images, java.util.List.of(
                            new Resource(server.url("/one").toString(), "One", null),
                            new Resource(server.url("/two").toString(), "Two", null)), output);
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
                assertEquals("0001.png", zip.getNextEntry().getName());
                assertArrayEquals(first, zip.readAllBytes());
                assertEquals("0002.png", zip.getNextEntry().getName());
                assertArrayEquals(second, zip.readAllBytes());
                assertNull(zip.getNextEntry());
            }
            assertEquals(0, staging.listFiles().length);
        }
    }

    @Test public void comicStagingLimitFailsBeforeWritingFinalZip() throws Exception {
        byte[] png = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, png, 0, 8);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "image/png")
                    .setBody(new okio.Buffer().write(png)));
            server.start();
            Source images = new Source(SOURCE.id(), SOURCE.name(), Kind.IMAGE, SOURCE.json());
            java.io.File staging = temporary.newFolder();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ResourceDownloader downloader = ResourceDownloader.withClient(new OkHttpClient(), staging, 20);
            IOException failure = assertThrows(IOException.class, () -> downloader.downloadZip(images,
                    java.util.List.of(new Resource(server.url("/one").toString(), "One", null)), output));
            assertTrue(failure.getMessage().contains("staging exceeds"));
            assertEquals(0, output.size());
            assertEquals(0, staging.listFiles().length);
        }
    }
    @Test public void savedSourceLoginHeadersAreAppliedOnceAndStrippedOnForeignRedirect() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            foreign.enqueue(new MockResponse().setBody("file"));
            origin.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/file")));
            origin.start();
            Source source = SourceImporter.parse("{\"bookSourceUrl\":\"" + origin.url("/") + "\"}").sources().get(0);
            var states = new org.bookgetter.core.MemorySourceStateStore();
            states.put(org.bookgetter.core.SourcePolicy.of(source).stateKey(), "loginHeader", "{\"X-Token\":\"saved-secret\"}");
            var downloader = ResourceDownloader.withClient(new OkHttpClient(), temporary.newFolder(),
                    ResourceDownloader.MAX_STAGED_IMAGE_BYTES, states);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            downloader.download(source, new Resource(origin.url("/redirect").toString(), "File", null), output);
            assertEquals("file", output.toString(StandardCharsets.UTF_8));
            assertEquals("saved-secret", origin.takeRequest().getHeader("X-Token"));
            assertNull(foreign.takeRequest().getHeader("X-Token"));
        }
    }

    @Test public void comicZipReleasesCompressorWithoutClosingCallerStream() throws Exception {
        byte[] png = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, png, 0, 8);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(png)));
            server.start();
            Source images = new Source(SOURCE.id(), SOURCE.name(), Kind.IMAGE, SOURCE.json());
            java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override public void close() { closed.set(true); }
            };
            ResourceDownloader.withClient(new OkHttpClient(), temporary.newFolder(), ResourceDownloader.MAX_STAGED_IMAGE_BYTES)
                    .downloadZip(images, java.util.List.of(new Resource(server.url("/image").toString(), "Page", null)), output);
            assertFalse(closed.get());
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
                assertNotNull(zip.getNextEntry());
                assertArrayEquals(png, zip.readAllBytes());
            }
        }
    }

}
