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
}
