package org.bookgetter.runtime;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.ResponseSignals;
import org.bookgetter.core.ResponseTooLargeException;

/** HTTP transport kept separate from WebView so redirect behavior is verifiable on the JVM. */
final class HttpSourceTransport {
    private static final int MAX_REDIRECTS = 10;
    static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private final OkHttpClient client;

    HttpSourceTransport(OkHttpClient client) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
    }

    FetchResponse fetch(FetchRequest input) throws IOException { return fetch(input, null); }

    FetchResponse fetch(FetchRequest input, java.util.concurrent.atomic.AtomicReference<okhttp3.Call> activeCall) throws IOException {
        long deadline = input.timeoutMillis() > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(input.timeoutMillis(), 86_400_000L)) : 0;
        String target = input.url();
        String method = input.method();
        String body = input.body();
        boolean changedToGet = false;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled");
            Map<String, String> headers = RedirectRequestHeaders.forTarget(input.headers(), input.url(), target);
            if (changedToGet) headers.keySet().removeIf(name -> name.equalsIgnoreCase("Content-Type")
                    || name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding"));
            Request.Builder request = new Request.Builder().url(target);
            headers.forEach(request::header);
            String contentType = headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                    .map(Map.Entry::getValue).findFirst().orElse("text/plain; charset=utf-8");
            RequestBody requestBody = body == null
                    ? (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT") || method.equalsIgnoreCase("PATCH")
                    ? RequestBody.create(MediaType.parse(contentType), new byte[0]) : null)
                    : RequestBody.create(MediaType.parse(contentType), body);
            request.method(method, requestBody);
            okhttp3.Call call = client.newCall(request.build());
            if (deadline != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.net.SocketTimeoutException("Request timeout");
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
            }
            if (activeCall != null) {
                activeCall.set(call);
                if (Thread.currentThread().isInterrupted()) { call.cancel(); throw new IOException("Request cancelled"); }
            }
            try (CallInterruptMonitor cancellation = new CallInterruptMonitor(call); Response response = call.execute()) {
                if (input.followRedirects() && response.isRedirect()) {
                    String location = response.header("Location");
                    HttpUrl next = location == null ? null : response.request().url().resolve(location);
                    if (next != null && (next.isHttps() || next.scheme().equals("http"))) {
                        if (response.code() == 301 || response.code() == 302 || response.code() == 303) {
                            if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")) {
                                method = "GET";
                                body = null;
                                changedToGet = true;
                            }
                        }
                        target = next.toString();
                        continue;
                    }
                }
                Charset charset = input.charset() != null ? Charset.forName(input.charset())
                        : response.body() != null && response.body().contentType() != null
                        ? response.body().contentType().charset(StandardCharsets.UTF_8)
                        : StandardCharsets.UTF_8;
                String text = method.equalsIgnoreCase("HEAD") || response.body() == null ? "" : new String(readLimited(response.body().byteStream(),
                        response.body().contentLength()), charset);
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (String name : response.headers().names()) {
                    // HEAD rules can inspect every Set-Cookie field through Response.cookies().
                    // A comma is not a safe separator because Expires contains a comma.
                    responseHeaders.put(name, name.equalsIgnoreCase("Set-Cookie")
                            ? String.join("\n", response.headers().values(name)) : response.header(name));
                }
                return new FetchResponse(response.request().url().toString(), text, response.code(), responseHeaders);
            } finally {
                if (activeCall != null) activeCall.compareAndSet(call, null);
            }
        }
        throw new IOException("Too many redirects at " + ResponseSignals.urlForLog(target));
    }

    private static byte[] readLimited(InputStream input, long contentLength) throws IOException {
        if (contentLength > MAX_RESPONSE_BYTES) throw new ResponseTooLargeException(MAX_RESPONSE_BYTES);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() > MAX_RESPONSE_BYTES - count) throw new ResponseTooLargeException(MAX_RESPONSE_BYTES);
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
