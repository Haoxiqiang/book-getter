package org.bookgetter.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.Transport;

/** HTTP and WebView share Android's per-domain CookieManager. Call on a worker thread. */
public final class AndroidTransport implements Transport {
    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder().cookieJar(new SharedCookieJar()).followRedirects(true).build();
    private final CookieManager cookies = CookieManager.getInstance();
    public AndroidTransport(Context context) { this.context = context.getApplicationContext(); cookies.setAcceptCookie(true); }

    @Override public FetchResponse fetch(FetchRequest request) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Network fetch must run off the main thread");
        return request.webView() ? fetchWebView(request) : fetchHttp(request);
    }

    private FetchResponse fetchHttp(FetchRequest input) throws IOException {
        Request.Builder builder = new Request.Builder().url(input.url());
        for (Map.Entry<String, String> header : input.headers().entrySet()) builder.header(header.getKey(), header.getValue());
        String contentType = "text/plain; charset=utf-8";
        for (Map.Entry<String, String> header : input.headers().entrySet())
            if (header.getKey().equalsIgnoreCase("Content-Type")) contentType = header.getValue();
        RequestBody body = input.body() == null
                ? (input.method().equalsIgnoreCase("POST") || input.method().equalsIgnoreCase("PUT") || input.method().equalsIgnoreCase("PATCH")
                    ? RequestBody.create(MediaType.parse(contentType), new byte[0]) : null)
                : RequestBody.create(MediaType.parse(contentType), input.body());
        builder.method(input.method(), body);
        try (Response response = client.newCall(builder.build()).execute()) {
            String finalUrl = response.request().url().toString();
            Charset charset = input.charset() != null ? Charset.forName(input.charset())
                    : response.body() != null && response.body().contentType() != null
                        ? response.body().contentType().charset(java.nio.charset.StandardCharsets.UTF_8)
                        : java.nio.charset.StandardCharsets.UTF_8;
            String text = response.body() == null ? "" : new String(response.body().bytes(), charset);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : response.headers().names()) headers.put(name, response.header(name));
            return new FetchResponse(finalUrl, text, response.code(), headers);
        }
    }

    private FetchResponse fetchWebView(FetchRequest request) throws Exception {
        if (!request.method().equals("GET")) throw new IllegalArgumentException("WebView transport supports GET only");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<FetchResponse> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        AtomicReference<Runnable> pendingRead = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                WebView view = new WebView(context);
                viewRef.set(view);
                view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setDomStorageEnabled(true);
                cookies.setAcceptThirdPartyCookies(view, true);
                view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) {
                    Runnable previous = pendingRead.get();
                    if (previous != null) mainHandler.removeCallbacks(previous);
                    Runnable read = () -> {
                        try {
                            webView.evaluateJavascript("document.documentElement.outerHTML", html -> {
                                try {
                                    String decoded = new com.google.gson.Gson().fromJson(html, String.class);
                                    result.set(new FetchResponse(url, decoded == null ? "" : decoded, 200, Map.of()));
                                } catch (Exception e) { error.set(e); }
                                done.countDown();
                            });
                        } catch (Exception e) { error.set(e); done.countDown(); }
                    };
                    pendingRead.set(read);
                    // Legado BackstageWebView defaults to 100 + 900 ms without webJs.
                    long delay = request.webViewDelayMillis() == 0 ? 1000L : 100L + request.webViewDelayMillis();
                    mainHandler.postDelayed(read, delay);
                }
                @Override public void onReceivedError(WebView webView, WebResourceRequest req, WebResourceError webError) {
                    if (req.isForMainFrame()) { error.set(new IOException(webError.getDescription().toString())); done.countDown(); }
                }
                @Override public void onReceivedHttpError(WebView webView, WebResourceRequest req, WebResourceResponse response) {
                    if (req.isForMainFrame()) { error.set(new IOException("HTTP " + response.getStatusCode() + " at " + req.getUrl())); done.countDown(); }
                }
                });
                view.loadUrl(request.url(), request.headers());
            } catch (Exception e) { error.set(e); done.countDown(); }
        });
        boolean finished;
        try { finished = done.await(45, TimeUnit.SECONDS); }
        finally { mainHandler.post(() -> {
            if (pendingRead.get() != null) mainHandler.removeCallbacks(pendingRead.get());
            if (viewRef.get() != null) viewRef.get().destroy();
        }); }
        if (!finished) throw new IOException("WebView timeout: " + request.url());
        if (error.get() != null) throw error.get();
        return result.get();
    }
}
