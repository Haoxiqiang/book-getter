package org.bookgetter.runtime;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.Transport;

/** A source-scoped RSS browser. The Activity owns app actions; this class owns browser isolation. */
public final class RssWebViewController implements org.bookgetter.core.RssBrowserSession {
    private static final String BRIDGE = "__bookGetterRssNative";
    private static final int MAX_MESSAGE_CHARS = 65_536;
    private static final Set<String> METHODS = Set.of("searchBook", "open", "showPhoto", "put", "get");
    private final WebView view;
    private final Config config;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean resourceFilterFailed = new AtomicBoolean();
    private final Set<String> reportedDiagnostics = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Call> inFlight = new AtomicReference<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "rss-browser-worker"); thread.setDaemon(true); return thread;
            });
    private final HttpSourceTransport transport = new HttpSourceTransport(new OkHttpClient.Builder()
            .cookieJar(new SharedCookieJar()).build());
    private final Transport sourceTransport;
    private final RssWebPolicy policy;
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final Set<String> bridgeOrigins;
    private final Set<String> scriptOrigins;
    private final String localOrigin;
    private final boolean bridgeAvailable;
    private volatile String currentOrigin = "";
    private volatile String token = UUID.randomUUID().toString();
    private volatile boolean syntheticLoad;
    private boolean awaitingSyntheticStart;

    public RssWebViewController(WebView view, Config config, Host host) { this(view, config, host, null); }

    public RssWebViewController(WebView view, Config config, Host host, Transport sourceTransport) {
        requireMainThread();
        this.sourceTransport = sourceTransport;
        this.view = view; this.config = config; this.host = host;
        this.policy = new RssWebPolicy(config.contentBlacklist(), config.contentWhitelist());
        if (policy.invalidPatternCount() > 0)
            diagnostic("contentWhitelist/contentBlacklist", "UNSUPPORTED_LINEAR_RESOURCE_REGEX",
                    "Some resource filters are outside the RE2/J linear subset; only literal prefix matching remains available");
        // Only source-value variables enter the page, never login headers or other source metadata.
        config.variables().forEach((key, value) -> {
            if (variables.size() < 256 && key.startsWith("value:") && key.length() <= 1030 && value.length() <= 32_768)
                variables.put(key.substring(6), value);
        });
        String sourceOrigin = RssWebPolicy.origin(config.sourceUrl());
        localOrigin = "https://rss-" + UUID.nameUUIDFromBytes(config.sourceId().getBytes(StandardCharsets.UTF_8))
                + ".bookgetter.invalid";
        scriptOrigins = sourceOrigin.isEmpty() ? Set.of(localOrigin) : Set.of(localOrigin, sourceOrigin);
        bridgeOrigins = sourceOrigin.isEmpty() || !sourceOrigin.startsWith("https://")
                ? Set.of(localOrigin) : Set.of(localOrigin, sourceOrigin);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(config.enableJs());
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(config.cacheFirst() ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_DEFAULT);
        bridgeAvailable = config.enableJs() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (bridgeAvailable) {
            WebViewCompat.addWebMessageListener(view, BRIDGE, bridgeOrigins, (webView, message, source, mainFrame, reply) -> {
                if (closed.get() || !RssWebPolicy.permitsBridge(bridgeOrigins, currentOrigin, source.toString(), mainFrame)) return;
                long expectedGeneration = generation.get();
                String expectedToken = token;
                try {
                    String raw = message.getData();
                    if (raw == null || raw.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("Bridge message is too large");
                    JsonObject input = JsonParser.parseString(raw).getAsJsonObject();
                    if (!expectedToken.equals(input.get("token").getAsString())) return;
                    String method = input.get("method").getAsString();
                    if (!METHODS.contains(method)) throw new IllegalArgumentException("Unsupported RSS bridge method");
                    JsonArray array = input.getAsJsonArray("args");
                    if (array == null || array.size() > 4) throw new IllegalArgumentException("Invalid bridge arguments");
                    List<String> arguments = new ArrayList<>();
                    array.forEach(arg -> arguments.add(arg.isJsonNull() ? "" : arg.getAsString()));
                    validateArguments(method, arguments);
                    enqueue(() -> {
                        if (closed.get() || (!method.equals("put") && !active(expectedGeneration))) return;
                        try {
                            Object result;
                            if (method.equals("put")) {
                                synchronized (variables) {
                                    if (!variables.containsKey(arguments.get(0)) && variables.size() >= 256)
                                        throw new IllegalArgumentException("Too many source variables");
                                    variables.put(arguments.get(0), arguments.get(1));
                                }
                                result = host.dispatch(method, arguments);
                            } else if (method.equals("get")) {
                                synchronized (variables) { result = variables.getOrDefault(arguments.get(0), ""); }
                            } else result = host.dispatch(method, arguments);
                            JsonObject output = new JsonObject();
                            output.addProperty("token", expectedToken);
                            output.add("value", gson.toJsonTree(result));
                            String serialized = gson.toJson(output);
                            if (serialized.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("Bridge reply exceeds limit");
                            main.post(() -> { if (active(expectedGeneration)) reply.postMessage(serialized); });
                        } catch (Exception failure) { diagnostic("webBridge." + method, "WEB_BRIDGE_ERROR", failure.getMessage()); }
                    });
                } catch (RuntimeException invalid) { diagnostic("webBridge", "INVALID_BRIDGE_MESSAGE", invalid.getMessage()); }
            });
        } else if (config.enableJs()) {
            diagnostic("webBridge", "PARTIAL_WEB_BRIDGE", "This WebView provider cannot isolate native messages by origin and frame");
        }
        if (sourceOrigin.startsWith("http://"))
            diagnostic("webBridge", "PARTIAL_WEB_BRIDGE", "Native bridge is disabled for cleartext HTTP pages");
        view.setWebViewClient(new BrowserClient());
    }

    /** Must be called on the main thread. User navigations and prior variable writes remain ordered. */
    @Override public void loadUrl(String url) {
        requireMainThread();
        navigate(url, true);
    }

    @Override public void loadStartPage() {
        loadHtml(config.startHtml() == null ? "" : config.startHtml(), localOrigin + "/", true);
    }

    /** App-generated or extracted HTML is isolated on a per-source synthetic HTTPS origin. */
    @Override public void loadHtml(String html, String baseUrl, boolean startPage) {
        requireMainThread();
        if (closed.get()) return;
        long next = beginNavigation();
        enqueue(() -> {
            if (!active(next)) return;
            String prepared;
            try { prepared = prepareHtml(html, startPage, next); }
            catch (RuntimeException failure) { diagnostic(startPage ? "startHtml" : "ruleContent", "WEB_LOAD_ERROR", failure.getMessage()); return; }
            main.post(() -> {
                if (!active(next)) return;
                currentOrigin = localOrigin;
                syntheticLoad = true;
                awaitingSyntheticStart = true;
                // Resource resolution belongs to <base>; browser history must retain the synthetic document identity.
                view.loadDataWithBaseURL(localOrigin + "/", withBase(prepared, baseUrl), "text/html", "UTF-8", localOrigin + "/");
            });
        });
    }

    private void navigate(String url, boolean explicit) {
        if (closed.get()) return;
        if (RssWebPolicy.origin(url).isEmpty()) {
            diagnostic("shouldOverrideUrlLoading", "UNSUPPORTED_WEB_SCHEME", "RSS navigation requires an HTTP(S) URL");
            return;
        }
        long next = beginNavigation();
        enqueue(() -> {
            try {
                if (!active(next) || (!explicit && host.shouldOverride(url))) return;
                if (!active(next)) return;
                if ((config.enableJs() && nonblank(config.preloadJs())) || (!config.enableJs() && nonblank(config.style()))) {
                    FetchRequest request = new FetchRequest(url, "GET",
                            RedirectRequestHeaders.forTarget(config.headers(), config.sourceUrl(), url),
                            null, null, false, 0, true, 30_000);
                    FetchResponse response = sourceTransport == null ? transport.fetch(request, inFlight) : sourceTransport.fetch(request);
                    if (!active(next)) return;
                    if (response.status() < 200 || response.status() >= 300)
                        throw new IllegalStateException("RSS preload HTTP " + response.status());
                    String type = response.headers().entrySet().stream()
                            .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                            .map(Map.Entry::getValue).findFirst().orElse("text/html");
                    if (!type.toLowerCase(java.util.Locale.ROOT).contains("html")) {
                        diagnostic("preloadJs", "PARTIAL_PRELOAD_JS", "Preload script requires an HTML GET response");
                        postLoadUrl(url, next);
                        return;
                    }
                    String body = scriptOrigins.contains(RssWebPolicy.origin(response.url()))
                            ? prepareHtml(response.body(), false, next) : response.body();
                    main.post(() -> {
                        if (!active(next)) return;
                        currentOrigin = RssWebPolicy.origin(response.url());
                        syntheticLoad = true;
                        awaitingSyntheticStart = true;
                        view.loadDataWithBaseURL(response.url(), body, "text/html", "UTF-8", response.url());
                    });
                } else postLoadUrl(url, next);
            } catch (Exception failure) {
                if (active(next)) {
                    if (failure instanceof org.bookgetter.core.Models.SourceFailure sourceFailure)
                        diagnostic(sourceFailure.field == null ? "webView" : sourceFailure.field, sourceFailure.code, sourceFailure.getMessage());
                    else diagnostic("webView", "WEB_LOAD_ERROR", failure.getMessage());
                }
            }
        });
    }

    private void postLoadUrl(String url, long next) throws Exception {
        FetchRequest prepared = host.prepare(new FetchRequest(url, "GET",
                RedirectRequestHeaders.forTarget(config.headers(), config.sourceUrl(), url),
                null, null, false));
        main.post(() -> {
            if (!active(next)) return;
            currentOrigin = RssWebPolicy.origin(url);
            syntheticLoad = false;
            awaitingSyntheticStart = false;
            view.loadUrl(prepared.url(), prepared.headers());
        });
    }

    private long beginNavigation() {
        long next = generation.incrementAndGet();
        reportedDiagnostics.clear();
        token = UUID.randomUUID().toString();
        currentOrigin = "";
        Call previous = inFlight.getAndSet(null);
        if (previous != null) previous.cancel();
        view.stopLoading();
        return next;
    }

    private String prepareHtml(String html, boolean startPage, long expected) {
        if (html == null) html = "";
        if (html.length() > HttpSourceTransport.MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("RSS HTML exceeds the response limit");
        String css = startPage && nonblank(config.startStyle()) ? config.startStyle() : config.style();
        String code = config.enableJs() ? facade(expected) : "";
        if (config.enableJs() && nonblank(config.preloadJs())) code += "\n(0,eval)(" + gson.toJson(config.preloadJs()) + ");";
        String prefix = code.isEmpty() ? "" : "<script>" + code + "</script>";
        if (nonblank(css)) prefix += "<style>" + css.replace("</style", "<\\/style") + "</style>";
        String startCode = startPage && config.enableJs() && nonblank(config.startJs())
                ? "<script>(0,eval)(" + gson.toJson(config.startJs()) + ");</script>" : "";
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        document.head().prepend(prefix);
        document.body().append(startCode);
        return document.outerHtml();
    }

    private static String withBase(String html, String baseUrl) {
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(html);
        document.outputSettings().prettyPrint(false);
        document.select("base").remove();
        if (!RssWebPolicy.origin(baseUrl).isEmpty()) document.head().prependElement("base").attr("href", baseUrl);
        return document.outerHtml();
    }

    private String facade(long expected) {
        if (!bridgeAvailable) return "";
        Map<String, String> snapshot;
        synchronized (variables) { snapshot = new LinkedHashMap<>(variables); }
        return "(()=>{if(window.top!==window)return;const native=window." + BRIDGE
                + ";if(!native)return;const token=" + gson.toJson(token) + ",state=Object.assign(Object.create(null)," + gson.toJson(snapshot) + ");"
                + "const send=(method,args)=>native.postMessage(JSON.stringify({token,method,args}));"
                + "const api={get:(key)=>state[String(key)]||'',put:(key,value)=>{key=String(key);value=String(value);"
                + "if(key.length>1024||value.length>32768||(!(key in state)&&Object.keys(state).length>=256))throw Error('Variable exceeds limit');"
                + "state[key]=value;send('put',[key,value]);return value;},"
                + "searchBook:(key,scope)=>send('searchBook',[String(key),scope==null?'':String(scope)]),"
                + "open:(name,url,title,origin)=>send('open',[name,url,title,origin].map(x=>x==null?'':String(x))),"
                + "showPhoto:(url)=>send('showPhoto',[String(url)]),"
                + "addBook:()=>{throw Error('RSS bridge addBook is unsupported');}};"
                + "Object.assign(window.java||(window.java={}),api);})();";
    }

    private void validateArguments(String method, List<String> args) {
        int minimum = method.equals("put") ? 2 : 1;
        if (args.size() < minimum) throw new IllegalArgumentException("Missing bridge arguments");
        if ((method.equals("put") || method.equals("get")) && args.get(0).length() > 1024)
            throw new IllegalArgumentException("Variable key exceeds limit");
        if (method.equals("put") && args.get(1).length() > 32_768)
            throw new IllegalArgumentException("Variable value exceeds limit");
        if (method.equals("showPhoto") && RssWebPolicy.origin(args.get(0)).isEmpty())
            throw new IllegalArgumentException("Photo URL must be HTTP(S)");
        if (method.equals("open")) {
            if (!Set.of("login", "rss", "search", "explore", "browser").contains(args.get(0)))
                throw new IllegalArgumentException("Unsupported open target");
            if (args.size() > 1 && !args.get(1).isBlank() && RssWebPolicy.origin(args.get(1)).isEmpty())
                throw new IllegalArgumentException("Open URL must be HTTP(S)");
        }
    }

    private final class BrowserClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
            if (!request.isForMainFrame()) return RssWebPolicy.origin(request.getUrl().toString()).isEmpty();
            if (!request.getMethod().equalsIgnoreCase("GET")) {
                diagnostic("shouldOverrideUrlLoading", "PARTIAL_NAVIGATION_POST", "POST navigation cannot be replayed by the URL hook or receive native source header/rate preparation");
                return false;
            }
            navigate(request.getUrl().toString(), false);
            return true;
        }
        @SuppressWarnings("deprecation")
        @Override public boolean shouldOverrideUrlLoading(WebView webView, String url) {
            navigate(url, false); return true;
        }
        @Override public void onPageStarted(WebView webView, String url, android.graphics.Bitmap favicon) {
            if (closed.get()) return;
            String origin = RssWebPolicy.origin(url);
            if (awaitingSyntheticStart) {
                // History/presentation URLs are not security origins for loadDataWithBaseURL.
                awaitingSyntheticStart = false;
            } else {
                // Invalidate queued replies on every new document, including same-origin POST and redirects.
                generation.incrementAndGet(); token = UUID.randomUUID().toString();
                syntheticLoad = false;
                currentOrigin = origin;
            }
        }
        @Override public void onPageFinished(WebView webView, String url) {
            if (closed.get() || !config.enableJs() || !url.equals(webView.getUrl()) || !currentOrigin.equals(RssWebPolicy.origin(url))
                    || !scriptOrigins.contains(currentOrigin)) return;
            if (!syntheticLoad) {
                webView.evaluateJavascript(facade(generation.get()), null);
                if (nonblank(config.style())) webView.evaluateJavascript(
                        "(()=>{const style=document.createElement('style');style.textContent=" + gson.toJson(config.style())
                                + ";document.head.appendChild(style);})();", null);
            }
            if (nonblank(config.injectJs())) webView.evaluateJavascript(config.injectJs(), null);
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
            if (closed.get() || resourceFilterFailed.get()) return emptyResource();
            if (request.isForMainFrame()) return null;
            try { return policy.permitsResource(request.getUrl().toString(), closed::get) ? null : emptyResource(); }
            catch (IllegalArgumentException failure) {
                if (resourceFilterFailed.compareAndSet(false, true))
                    diagnostic("contentWhitelist/contentBlacklist", "RESOURCE_FILTER_ERROR", failure.getMessage());
                return emptyResource();
            }
        }
        @Override public void onReceivedSslError(WebView webView, android.webkit.SslErrorHandler handler,
                                                  android.net.http.SslError error) {
            handler.cancel();
            diagnostic("webView", "SSL_ERROR", "TLS certificate validation failed");
        }
    }

    private static WebResourceResponse emptyResource() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }
    private boolean active(long expected) { return !closed.get() && generation.get() == expected; }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private void enqueue(Runnable task) {
        try { if (!closed.get()) worker.execute(task); }
        catch (RejectedExecutionException overloaded) { diagnostic("webView", "WEB_QUEUE_FULL", "Too many pending browser actions"); }
    }
    private void diagnostic(String field, String code, String message) {
        if (!reportedDiagnostics.add(generation.get() + ":" + field + ":" + code)) return;
        main.post(() -> { if (!closed.get()) host.onDiagnostic(field, code, message == null ? code : message); });
    }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser operations must run on the main thread");
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        generation.incrementAndGet();
        Call call = inFlight.getAndSet(null);
        if (call != null) call.cancel();
        worker.shutdownNow();
        main.post(() -> {
            if (bridgeAvailable) WebViewCompat.removeWebMessageListener(view, BRIDGE);
            view.stopLoading();
            view.setWebViewClient(new WebViewClient());
            view.destroy();
        });
    }
}
