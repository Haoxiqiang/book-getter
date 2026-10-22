package org.bookgetter.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bookgetter.core.FileSourceStateStore;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.SourceFailure;
import org.bookgetter.runtime.AndroidTransport;
import org.bookgetter.runtime.LinearRegex;
import org.bookgetter.runtime.RssWebViewController;

/**
 * Debug-only, offline browser security probe. Logs one PASS/FAIL/SKIP per assertion and a summary.
 * adb shell am start -n org.bookgetter.app/.RssBrowserProbeActivity
 * Optional: --es fixturesBaseUrl http://127.0.0.1:PORT (GET /regex and /cancel, see LocalFixtures).
 * The default starts its own loopback server; no third-party page or account is accessed.
 */
public final class RssBrowserProbeActivity extends Activity {
    private static final String TAG = "BookGetterRssBrowserProbe";
    private static final String NATIVE = "__bookGetterRssNative";
    private static final String ID = "https://browser-probe.bookgetter.invalid/rss";
    private static final String STATE = "rss-browser-probe";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final AtomicInteger actions = new AtomicInteger();
    private final CountDownLatch hold = new CountDownLatch(1);
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch closeHold = new CountDownLatch(1);
    private final CountDownLatch closeHeld = new CountDownLatch(1);
    private FileSourceStateStore states;
    private File stateDirectory;
    private RssWebViewController browser;
    private WebView webView;
    private TextView status;
    private LocalFixtures fixtures;
    private String fixtureBase;
    private String firstRaw;
    private volatile Thread networkWorker;
    private volatile boolean destroyed;
    private volatile boolean bridgeUnsupported;
    private boolean finished;
    private int phase;
    private int passed;
    private int failed;
    private int skipped;
    private long heartbeat;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("Running local RSS browser probe; results are in logcat.");
        layout.addView(status);
        webView = new WebView(this);
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                String text = message.message();
                Log.i(TAG, "PAGE_CONSOLE " + message.messageLevel() + " "
                        + (text.length() > 300 ? text.substring(0, 300) : text));
                return true;
            }
        });
        layout.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        stateDirectory = new File(getCacheDir(), "rss-browser-probe-" + SystemClock.elapsedRealtime());
        states = new FileSourceStateStore(stateDirectory);
        states.put(STATE, "value:seed", "persisted-seed");
        states.put(STATE, "loginHeader", "not-visible-to-page");
        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed || finished) return;
                heartbeat = SystemClock.elapsedRealtime();
                main.postDelayed(this, 50);
            }
        });
        main.postDelayed(() -> {
            if (!finished) { fail("probe_watchdog", "No complete result within 35 seconds"); complete(); }
        }, 35_000);
        try {
            String configured = getIntent().getStringExtra("fixturesBaseUrl");
            if (configured == null) configured = getIntent().getStringExtra("baseUrl");
            if (configured == null) {
                fixtures = new LocalFixtures();
                fixtureBase = fixtures.baseUrl();
                fixtures.start();
            } else {
                URI uri = URI.create(configured);
                if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null
                        || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())
                        || "10.0.2.2".equals(uri.getHost())))
                    throw new IllegalArgumentException("Fixtures must use a loopback HTTP URL");
                fixtureBase = configured.replaceAll("/+$", "");
            }
            createBrowser();
            browser.loadHtml(firstPage(), ID, true);
            pollPage("script_and_main_frame", object -> object.has("order")
                    && object.get("order").getAsString().equals("preload,body,start,inject")
                    && object.get("main").getAsString().equals("main-ok"), this::firstPageReady, 8_000);
            main.postDelayed(() -> {
                if (bridgeUnsupported && !finished && phase == 0) {
                    phase++;
                    skip("native_bridge_cases", "Provider lacks origin-scoped messages; controller reported PARTIAL_WEB_BRIDGE");
                    closeBrowser();
                    runRegexCases();
                }
            }, 300);
        } catch (Exception failure) {
            fail("setup", failure.getClass().getSimpleName());
            complete();
        }
    }

    private void createBrowser() {
        RssWebViewController.Config config = new RssWebViewController.Config(ID, ID, Map.of(), true, false,
                "window.probeOrder=['preload'];java.put('preloaded',java.get('seed'));", "window.probeOrder.push('inject');"
                + "java.put('injectedOrder',window.probeOrder.join(','));", null,
                "window.probeOrder.push('start');java.put('startCheck','ready');", null, null, null, null,
                states.snapshot(STATE));
        browser = new RssWebViewController(webView, config, new RssWebViewController.Host() {
            @Override public String dispatch(String method, List<String> args) throws Exception {
                if (method.equals("put")) {
                    if (args.get(0).equals("hold")) { held.countDown(); hold.await(3, TimeUnit.SECONDS); }
                    if (args.get(0).equals("closeHold")) { closeHeld.countDown(); closeHold.await(3, TimeUnit.SECONDS); }
                    states.put(STATE, "value:" + args.get(0), args.get(1));
                    return args.get(1);
                }
                if (method.equals("searchBook")) { actions.incrementAndGet(); return ""; }
                throw new IllegalArgumentException("Unexpected probe action");
            }
            @Override public void onDiagnostic(String field, String code, String message) {
                if (code.equals("PARTIAL_WEB_BRIDGE")) bridgeUnsupported = true;
                Log.i(TAG, "DIAGNOSTIC " + field + " " + code);
            }
        });
    }

    private static String captureScript() {
        return "const native=window." + NATIVE + ";const send=native.postMessage.bind(native);"
                + "native.postMessage=function(raw){window.probeLastRaw=raw;send(raw);};";
    }
    private String firstPage() {
        return "<!doctype html><html><head></head><body><script>window.probeOrder.push('body');"
                + captureScript() + "java.put('main','main-ok');</script></body></html>";
    }
    private String secondPage() {
        return "<!doctype html><html><head></head><body><script>" + captureScript()
                + "window.secondReady=true;window.afterSnapshot=java.get('after');"
                + "java.put('secondReady','yes');</script></body></html>";
    }

    private void firstPageReady(JsonObject page) {
        pass("preload_body_start_inject_order");
        pass("synthetic_main_frame_put_get");
        check("source_state_prefill", page.get("seed").getAsString().equals("persisted-seed"));
        check("login_header_not_exposed", page.get("secret").getAsString().isEmpty());
        firstRaw = page.get("raw").getAsString();
        check("message_capture_available", !firstRaw.isEmpty());
        pollPersistent("main_frame_file_persistence", "main", "main-ok", () -> {
            evaluate("java.put('hold','held');java.put('after','after-navigation');true", ignored -> waitForHold());
        }, 3_000);
    }

    private void waitForHold() {
        long deadline = SystemClock.elapsedRealtime() + 2_000;
        Runnable wait = new Runnable() {
            @Override public void run() {
                if (destroyed || finished) return;
                if (held.getCount() == 0) {
                    // Let both native message callbacks enter the ordered worker queue before navigating.
                    main.postDelayed(() -> {
                        browser.loadHtml(secondPage(), ID, false);
                        hold.countDown();
                        pollPage("navigation_flush", page -> page.has("second") && page.get("second").getAsBoolean(),
                                RssBrowserProbeActivity.this::secondPageReady, 5_000);
                    }, 150);
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    fail("navigation_hold", "Native put did not reach the worker"); hold.countDown(); runFrameCases();
                } else main.postDelayed(this, 30);
            }
        };
        main.post(wait);
    }

    private void secondPageReady(JsonObject page) {
        check("navigation_waits_for_accepted_put", page.get("after").getAsString().equals("after-navigation"));
        pollPersistent("navigation_put_file_persistence", "after", "after-navigation", this::runFrameCases, 3_000);
    }

    private void runFrameCases() {
        if (destroyed || finished || browser == null) return;
        String iframeCode = "(()=>{const b=window." + NATIVE + ";if(b)b.postMessage(RAW);"
                + "parent.postMessage({kind:KIND,hasBridge:!!b},'*');})();";
        String same = "<script>" + iframeCode.replace("RAW", "JSON.stringify(Object.assign(JSON.parse(parent.probeLastRaw),"
                + "{args:['sameFrameAttempt','forbidden']}))").replace("KIND", "'same'") + "</script>";
        String cross = "<script>" + iframeCode.replace("RAW", "'{\"method\":\"put\",\"token\":\"fake\",\"args\":[\"crossFrameAttempt\",\"forbidden\"]}'")
                .replace("KIND", "'cross'") + "</script>";
        String script = "window.frameResults={};window.addEventListener('message',e=>{if(e.data&&e.data.kind)"
                + "window.frameResults[e.data.kind]=e.data;});"
                + "const same=document.createElement('iframe');same.srcdoc=" + gson.toJson(same) + ";document.body.appendChild(same);"
                + "const cross=document.createElement('iframe');cross.sandbox='allow-scripts';cross.srcdoc=" + gson.toJson(cross)
                + ";document.body.appendChild(cross);true;";
        evaluate(script, ignored -> pollPage("iframe_callbacks", page -> page.has("frames")
                && page.getAsJsonObject("frames").has("same") && page.getAsJsonObject("frames").has("cross"), page -> {
                    JsonObject frames = page.getAsJsonObject("frames");
                    check("same_origin_iframe_has_transport", frames.getAsJsonObject("same").get("hasBridge").getAsBoolean());
                    check("opaque_cross_origin_iframe_has_no_transport", !frames.getAsJsonObject("cross").get("hasBridge").getAsBoolean());
                    main.postDelayed(() -> {
                        check("same_origin_iframe_native_call_rejected", states.get(STATE, "value:sameFrameAttempt").isEmpty());
                        check("cross_origin_iframe_native_call_rejected", states.get(STATE, "value:crossFrameAttempt").isEmpty());
                        runOldTokenCase();
                    }, 250);
                }, 5_000));
    }

    private void runOldTokenCase() {
        if (firstRaw == null || firstRaw.isEmpty()) {
            skip("previous_document_token_rejected", "Provider did not allow capturing the outgoing message"); runCloseCase(); return;
        }
        String old = gson.toJson(firstRaw);
        String script = "(()=>{const old=JSON.parse(" + old + ");const current=JSON.parse(window.probeLastRaw);"
                + "window.tokensDiffer=old.token!==current.token;old.args=['oldTokenAttempt','forbidden'];"
                + "window." + NATIVE + ".postMessage(JSON.stringify(old));return window.tokensDiffer;})();";
        evaluate(script, result -> {
            check("navigation_changes_document_token", Boolean.TRUE.equals(gson.fromJson(result, Boolean.class)));
            main.postDelayed(() -> {
                check("previous_document_token_rejected", states.get(STATE, "value:oldTokenAttempt").isEmpty());
                runCloseCase();
            }, 250);
        });
    }

    private void runCloseCase() {
        evaluate("java.searchBook('before-close');true", ignored -> {
            long deadline = SystemClock.elapsedRealtime() + 2_000;
            Runnable wait = new Runnable() {
                @Override public void run() {
                    if (destroyed || finished) return;
                    if (actions.get() == 1) {
                        pass("native_action_control");
                        evaluate("java.put('closeHold','held');java.searchBook('after-close');true", result -> waitCloseHold());
                    } else if (SystemClock.elapsedRealtime() >= deadline) {
                        fail("native_action_control", "Main-frame action was not dispatched"); closeBrowser(); runRegexCases();
                    } else main.postDelayed(this, 30);
                }
            };
            main.post(wait);
        });
    }

    private void waitCloseHold() {
        long deadline = SystemClock.elapsedRealtime() + 2_000;
        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed || finished) return;
                if (closeHeld.getCount() == 0) {
                    main.postDelayed(() -> {
                        int before = actions.get();
                        closeBrowser(); closeHold.countDown();
                        main.postDelayed(() -> {
                            check("close_cancels_queued_bridge_action", actions.get() == before);
                            Log.i(TAG, "COVERAGE close assertion covers queued controller actions; already-dispatched Activity UI callbacks are not exercised");
                            runRegexCases();
                        }, 350);
                    }, 100);
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    fail("close_hold", "Native put did not reach worker"); closeBrowser(); closeHold.countDown(); runRegexCases();
                } else main.postDelayed(this, 30);
            }
        });
    }

    private void runRegexCases() {
        if (destroyed || finished) return;
        phase++;
        Thread thread = new Thread(() -> {
            try {
                FetchRequest request = new FetchRequest(fixtureBase + "/regex", "GET", Map.of(), null, null,
                        true, 0, true, 4_000);
                long started = SystemClock.elapsedRealtime();
                boolean rejected = new LinearRegex(Pattern.quote(fixtureBase + "/") + "(a|aa)+$")
                        .matches(fixtureBase + "/" + "a".repeat(10_000) + "!");
                long elapsed = SystemClock.elapsedRealtime() - started;
                main.post(() -> check("malicious_pattern_linear_nonmatch", !rejected && elapsed < 1_000));
                var captured = new AndroidTransport(this).fetchContent(request,
                        Pattern.quote(fixtureBase + "/") + "(a|aa)+!", null);
                main.post(() -> check("source_regex_long_resource_capture",
                        captured.body().length() > 10_000 && captured.body().endsWith("!")));
                try {
                    new AndroidTransport(this).fetchContent(request, "(?<=x)y", null);
                    main.post(() -> fail("unsupported_source_regex_field_diagnostic", "Lookbehind was not rejected"));
                } catch (SourceFailure failure) {
                    boolean correct = "ruleContent.sourceRegex".equals(failure.field)
                            && "UNSUPPORTED_LINEAR_REGEX".equals(failure.code);
                    main.post(() -> check("unsupported_source_regex_field_diagnostic", correct));
                }
            } catch (Exception failure) {
                main.post(() -> fail("malicious_source_regex", failure.getClass().getSimpleName()));
            } finally { main.post(this::runCancellationCase); }
        }, "rss-probe-regex");
        networkWorker = thread;
        thread.start();
    }

    private void runCancellationCase() {
        if (destroyed || finished) return;
        check("main_thread_heartbeat_after_regex", SystemClock.elapsedRealtime() - heartbeat < 250);
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                new AndroidTransport(this).fetchContent(new FetchRequest(fixtureBase + "/cancel", "GET", Map.of(),
                        null, null, true, 0, true, 10_000), "never-match-this-resource", null);
                main.post(() -> fail("cancel_fetch", "Unmatched sniff unexpectedly returned"));
            } catch (InterruptedException expected) {
                main.post(() -> pass("cancel_fetch_interrupt"));
            } catch (Exception failure) {
                main.post(() -> fail("cancel_fetch", failure.getClass().getSimpleName()));
            } finally { done.countDown(); }
        }, "rss-probe-cancel");
        networkWorker = thread;
        thread.start();
        long fixtureDeadline = SystemClock.elapsedRealtime() + 2_500;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed || finished) return;
                if (fixtures != null && fixtures.ticks.get() == 0 && SystemClock.elapsedRealtime() < fixtureDeadline) {
                    main.postDelayed(this, 50); return;
                }
                if (fixtures != null) check("cancel_fixture_script_executed", fixtures.ticks.get() > 0);
                thread.interrupt();
                main.postDelayed(() -> {
                    check("cancelled_worker_terminates", done.getCount() == 0);
                    if (fixtures == null) {
                        skip("cancel_stops_page_resource_requests", "External fixtures do not expose the local request counter"); complete();
                    } else {
                        int afterCleanup = fixtures.ticks.get();
                        main.postDelayed(() -> {
                            check("cancel_stops_page_resource_requests", fixtures.ticks.get() == afterCleanup);
                            complete();
                        }, 400);
                    }
                }, 700);
            }
        }, 250);
    }

    private interface PageCondition { boolean matches(JsonObject page); }
    private void pollPage(String name, PageCondition condition, Consumer<JsonObject> next, long timeoutMillis) {
        int expectedPhase = phase;
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (destroyed || finished || browser == null || phase != expectedPhase) return;
                evaluate("JSON.stringify({order:(window.probeOrder||[]).join(','),main:window.java?java.get('main'):'',"
                        + "seed:window.java?java.get('seed'):'',secret:window.java?java.get('loginHeader'):'',"
                        + "raw:window.probeLastRaw||'',second:!!window.secondReady,after:window.afterSnapshot||'',"
                        + "frames:window.frameResults||{}})", result -> {
                    if (phase != expectedPhase || finished) return;
                    JsonObject page;
                    try { page = JsonParser.parseString(gson.fromJson(result, String.class)).getAsJsonObject(); }
                    catch (RuntimeException invalid) { page = new JsonObject(); }
                    if (condition.matches(page)) next.accept(page);
                    else if (SystemClock.elapsedRealtime() >= deadline) {
                        JsonObject observed = page.deepCopy(); observed.remove("raw");
                        Log.i(TAG, "PAGE_STATE " + gson.toJson(observed) + " visibleUrl=" + webView.getUrl());
                        fail(name, "Expected page state was not observed"); closeBrowser(); runRegexCases();
                    } else main.postDelayed(this, 40);
                });
            }
        };
        main.post(poll);
    }

    private void pollPersistent(String name, String key, String expected, Runnable next, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed || finished) return;
                // Reopen the file-backed store rather than checking a JavaScript or Java memory mirror.
                String actual = new FileSourceStateStore(stateDirectory).get(STATE, "value:" + key);
                if (expected.equals(actual)) { pass(name); next.run(); }
                else if (SystemClock.elapsedRealtime() >= deadline) { fail(name, "Stored value was not committed"); next.run(); }
                else main.postDelayed(this, 40);
            }
        });
    }

    private void evaluate(String script, Consumer<String> callback) {
        if (!destroyed && !finished && browser != null) webView.evaluateJavascript(script, callback::accept);
    }
    private void check(String name, boolean valid) { if (valid) pass(name); else fail(name, "Assertion did not hold"); }
    private void pass(String name) { if (finished || destroyed) return; passed++; Log.i(TAG, "PASS " + name); }
    private void fail(String name, String reason) { if (finished || destroyed) return; failed++; Log.e(TAG, "FAIL " + name + " " + reason); }
    private void skip(String name, String reason) { if (finished || destroyed) return; skipped++; Log.i(TAG, "SKIP " + name + " " + reason); }
    private void closeBrowser() { if (browser != null) { browser.close(); browser = null; } }
    private void complete() {
        if (finished || destroyed) return;
        finished = true;
        closeBrowser();
        Thread thread = networkWorker; if (thread != null) thread.interrupt();
        if (fixtures != null) fixtures.close();
        String summary = "SUMMARY pass=" + passed + " fail=" + failed + " skip=" + skipped;
        Log.i(TAG, summary); status.setText(summary);
        if (getIntent().getBooleanExtra("finish", true)) main.postDelayed(this::finish, 200);
    }
    @Override protected void onDestroy() {
        destroyed = true; hold.countDown(); closeHold.countDown(); closeBrowser();
        Thread thread = networkWorker; if (thread != null) thread.interrupt();
        if (fixtures != null) fixtures.close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Minimal loopback fixture server. External fixtures should serve equivalent paths. */
    private static final class LocalFixtures implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicInteger ticks = new AtomicInteger();
        private volatile boolean closed;
        LocalFixtures() throws IOException { server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); }
        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void start() {
            Thread thread = new Thread(() -> {
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2_000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String request = reader.readLine();
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { /* discard local fixture headers */ }
                        if (request == null) continue;
                        String path = request.split(" ")[1];
                        String body;
                        if (path.startsWith("/regex")) body = "<html><body><img src='/" + "a".repeat(10_000) + "!'></body></html>";
                        else if (path.startsWith("/cancel")) body = "<html><body><script>setInterval(function(){var image=new Image();image.src='/tick?x='+Date.now();},40);</script></body></html>";
                        else { if (path.startsWith("/tick")) ticks.incrementAndGet(); body = "fixture"; }
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: "
                                + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(bytes); output.flush();
                    } catch (IOException failure) { if (!closed) Log.w(TAG, "FIXTURE_IO " + failure.getClass().getSimpleName()); }
                }
            }, "rss-probe-fixtures");
            thread.setDaemon(true); thread.start();
        }
        @Override public void close() {
            closed = true;
            try { server.close(); } catch (IOException ignored) { /* already closed */ }
        }
    }
}
