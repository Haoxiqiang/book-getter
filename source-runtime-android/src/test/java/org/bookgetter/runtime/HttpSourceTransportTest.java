package org.bookgetter.runtime;

import static org.junit.Assert.*;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.bookgetter.core.Models.FetchRequest;
import org.junit.Test;

public final class HttpSourceTransportTest {
    private final HttpSourceTransport transport = new HttpSourceTransport(new OkHttpClient());

    @Test public void rejectsOversizedResponseBeforeReadingBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("small")
                    .setHeader("Content-Length", HttpSourceTransport.MAX_RESPONSE_BYTES + 1L));
            server.start();
            var error = assertThrows(java.io.IOException.class,
                    () -> transport.fetch(new FetchRequest(server.url("/large").toString())));
            assertTrue(error.getMessage().contains("32 MiB"));
        }
    }

    @Test public void rejectsChunkedResponseThatGrowsPastLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setChunkedBody("x".repeat(HttpSourceTransport.MAX_RESPONSE_BYTES + 1), 65536));
            server.start();
            var error = assertThrows(java.io.IOException.class,
                    () -> transport.fetch(new FetchRequest(server.url("/chunked").toString())));
            assertTrue(error.getMessage().contains("32 MiB"));
        }
    }

    @Test public void crossOriginRedirectDropsCredentialsAndKeepsSafeHeaders() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer destination = new MockWebServer()) {
            destination.start();
            destination.enqueue(new MockResponse().setBody("book list"));
            origin.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", destination.url("/results")));
            origin.start();
            var response = transport.fetch(new FetchRequest(origin.url("/search").toString(), "GET",
                    Map.of("Authorization", "Bearer secret", "Cookie", "session=secret",
                            "X-Token", "secret", "User-Agent", "Reader",
                            "Referer", origin.url("/book?token=secret").toString()), null, null, false));
            assertEquals("book list", response.body());
            assertEquals(destination.url("/results").toString(), response.url());
            var first = origin.takeRequest();
            assertEquals("secret", first.getHeader("X-Token"));
            var redirected = destination.takeRequest();
            assertNull(redirected.getHeader("Authorization"));
            assertNull(redirected.getHeader("Cookie"));
            assertNull(redirected.getHeader("X-Token"));
            assertEquals("Reader", redirected.getHeader("User-Agent"));
            assertEquals(origin.url("/").toString(), redirected.getHeader("Referer"));
        }
    }

    @Test public void post302BecomesGetButNoFollowReturnsTheRedirect() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/results"));
            server.enqueue(new MockResponse().setBody("found"));
            server.start();
            var followed = transport.fetch(new FetchRequest(server.url("/search").toString(), "POST",
                    Map.of("Content-Type", "application/x-www-form-urlencoded"), "q=book", null, false));
            assertEquals("found", followed.body());
            assertEquals("POST", server.takeRequest().getMethod());
            var redirected = server.takeRequest();
            assertEquals("GET", redirected.getMethod());
            assertNull(redirected.getHeader("Content-Type"));

            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/unused"));
            var notFollowed = transport.fetch(new FetchRequest(server.url("/post").toString(), "POST",
                    Map.of(), "body", null, false, 0, false));
            assertEquals(302, notFollowed.status());
            assertEquals(server.url("/post").toString(), notFollowed.url());
            assertEquals("POST", server.takeRequest().getMethod());
        }
    }

    @Test public void headDoesNotFollowRedirectWhenRequested() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next")
                    .setHeader("Content-Length", HttpSourceTransport.MAX_RESPONSE_BYTES + 1L));
            server.start();
            var response = transport.fetch(new FetchRequest(server.url("/check").toString(), "HEAD",
                    Map.of("X-Probe", "one"), null, null, false, 0, false));
            assertEquals(302, response.status());
            assertEquals("/next", response.headers().get("Location"));
            var request = server.takeRequest();
            assertEquals("HEAD", request.getMethod());
            assertEquals("one", request.getHeader("X-Probe"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void headPreservesMultipleSetCookieHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next")
                    .addHeader("Set-Cookie", "token=abc; Path=/")
                    .addHeader("Set-Cookie", "secret=101; Expires=Wed, 21 Oct 2030 07:28:00 GMT"));
            server.start();
            var response = transport.fetch(new FetchRequest(server.url("/check").toString(), "HEAD",
                    Map.of(), null, null, false, 0, false));
            assertEquals("token=abc; Path=/\nsecret=101; Expires=Wed, 21 Oct 2030 07:28:00 GMT",
                    response.headers().get("Set-Cookie"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void post307KeepsMethodAndBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/new"));
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            transport.fetch(new FetchRequest(server.url("/old").toString(), "POST", Map.of(), "payload", null, false));
            assertEquals("POST", server.takeRequest().getMethod());
            var redirected = server.takeRequest();
            assertEquals("POST", redirected.getMethod());
            assertEquals("payload", redirected.getBody().readUtf8());
        }
    }
    @Test public void explicitTimeoutBoundsResponseWaiting() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS).setBody("late"));
            server.start();
            FetchRequest request = new FetchRequest(server.url("/slow").toString(), "GET", Map.of(),
                    null, null, false, 0, true, 50);
            assertThrows(java.io.IOException.class, () -> transport.fetch(request));
        }
    }

    @Test public void defaultTimeoutDoesNotOverrideClientTimeout() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();
            assertEquals("ok", transport.fetch(new FetchRequest(server.url("/").toString())).body());
        }
    }

    @Test public void interruptCancelsABlockedSocketRead() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            server.start();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { transport.fetch(new FetchRequest(server.url("/blocked").toString())); }
                catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            try {
                assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(2_000);
                assertFalse("interrupted network worker remains blocked", worker.isAlive());
                assertTrue(failure.get() instanceof java.io.IOException);
            } finally { worker.interrupt(); worker.join(2_000); }
        }
    }

}
