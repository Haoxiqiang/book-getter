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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.Transport;
import org.bookgetter.core.WebContentTransport;
import org.bookgetter.core.ResponseSignals;
import org.bookgetter.core.ResponseTooLargeException;

/** HTTP and WebView share Android's per-domain CookieManager. Call on a worker thread. */
public final class AndroidTransport implements WebContentTransport {
    private final Context context;
    private final HttpSourceTransport http = new HttpSourceTransport(
            new OkHttpClient.Builder().cookieJar(new SharedCookieJar()).build());
    private final CookieManager cookies = CookieManager.getInstance();
    public AndroidTransport(Context context) { this.context = context.getApplicationContext(); cookies.setAcceptCookie(true); }

    @Override public FetchResponse fetch(FetchRequest request) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Network fetch must run off the main thread");
        return request.webView() ? fetchWebView(request) : http.fetch(request);
    }

    @Override public FetchResponse fetchContent(FetchRequest request, String sourceRegex, String webJs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Network fetch must run off the main thread");
        return fetchWebView(request, sourceRegex, webJs);
    }

    private FetchResponse fetchWebView(FetchRequest request) throws Exception {
        return fetchWebView(request, null, null);
    }

    private FetchResponse fetchWebView(FetchRequest request, String sourceRegex, String webJs) throws Exception {
        if (!request.method().equals("GET")) throw new IllegalArgumentException("WebView transport supports GET only");
        Pattern resourcePattern = sourceRegex == null || sourceRegex.isBlank() ? null : Pattern.compile(sourceRegex);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<FetchResponse> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        AtomicReference<Runnable> pendingRead = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                // The worker may have timed out before this main-thread creation callback runs.
                if (completed.get()) return;
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
                            if (completed.get()) return;
                            if (resourcePattern != null) {
                                // A sniffing request completes only when a matching resource is observed.
                                if (webJs != null && !webJs.isBlank()) webView.evaluateJavascript(webJs, ignored -> {});
                                return;
                            }
                            String expression = webJs == null || webJs.isBlank() ? "document.documentElement.outerHTML" : webJs;
                            webView.evaluateJavascript(expression, html -> {
                                try {
                                    String decoded = new com.google.gson.Gson().fromJson(html, String.class);
                                    if (decoded != null && (decoded.length() > HttpSourceTransport.MAX_RESPONSE_BYTES
                                            || decoded.getBytes(StandardCharsets.UTF_8).length > HttpSourceTransport.MAX_RESPONSE_BYTES))
                                        throw new ResponseTooLargeException(HttpSourceTransport.MAX_RESPONSE_BYTES);
                                    if (completed.compareAndSet(false, true))
                                        result.set(new FetchResponse(url, decoded == null ? "" : decoded, 200, Map.of()));
                                } catch (Exception e) {
                                    if (completed.compareAndSet(false, true)) error.set(e);
                                }
                                done.countDown();
                            });
                        } catch (Exception e) {
                            if (completed.compareAndSet(false, true)) error.set(e);
                            done.countDown();
                        }
                    };
                    pendingRead.set(read);
                    // Legado BackstageWebView defaults to 100 + 900 ms without webJs.
                    long delay = request.webViewDelayMillis() == 0 ? 1000L : 100L + request.webViewDelayMillis();
                    mainHandler.postDelayed(read, delay);
                }
                @Override public void onReceivedError(WebView webView, WebResourceRequest req, WebResourceError webError) {
                    if (req.isForMainFrame() && completed.compareAndSet(false, true)) {
                        error.set(new IOException(webError.getDescription().toString())); done.countDown();
                    }
                }
                @Override public void onReceivedHttpError(WebView webView, WebResourceRequest req, WebResourceResponse response) {
                    if (req.isForMainFrame() && completed.compareAndSet(false, true)) {
                        error.set(new WebContentTransport.HttpStatusException(
                                response.getStatusCode(), req.getUrl().toString())); done.countDown();
                    }
                }
                @Override public void onLoadResource(WebView webView, String resourceUrl) {
                    if (resourcePattern != null && resourcePattern.matcher(resourceUrl).matches()
                            && completed.compareAndSet(false, true)) {
                        // Legado's sourceRegex captures the resource URL requested by the page.
                        result.set(new FetchResponse(webView.getUrl(), resourceUrl, 200, Map.of()));
                        done.countDown();
                    }
                }
                });
                view.loadUrl(request.url(), request.headers());
            } catch (Exception e) {
                if (completed.compareAndSet(false, true)) error.set(e);
                done.countDown();
            }
        });
        boolean finished;
        try { finished = done.await(45, TimeUnit.SECONDS); }
        finally {
            // Close the gate on the worker before posting cleanup; main-thread creation may still be queued.
            completed.set(true);
            mainHandler.post(() -> {
                if (pendingRead.get() != null) mainHandler.removeCallbacks(pendingRead.get());
                if (viewRef.get() != null) viewRef.get().destroy();
            });
        }
        if (!finished && resourcePattern != null) throw new WebContentTransport.NoMatchingResourceException();
        if (!finished) throw new IOException("WebView timeout: " + ResponseSignals.urlForLog(request.url()));
        if (error.get() != null) throw error.get();
        if (result.get() == null) throw new IOException("WebView completed without a response");
        return result.get();
    }
}
