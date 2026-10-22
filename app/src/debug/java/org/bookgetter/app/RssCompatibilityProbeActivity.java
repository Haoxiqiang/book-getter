package org.bookgetter.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.net.URI;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.SourceFailure;
import org.bookgetter.core.ResponseSignals;
import org.bookgetter.core.RhinoRuntime;
import org.bookgetter.core.RssEngine;
import org.bookgetter.core.RssImporter;
import org.bookgetter.core.RssModels.*;
import org.bookgetter.runtime.AndroidTransport;

/** Debug-only, local synthetic fixtures. Logs stage summaries without article bodies. */
public final class RssCompatibilityProbeActivity extends Activity {
    private static final String TAG = "BookGetterRssProbe";
    private Thread worker;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("catalogUrl");
        int sourceIndex = getIntent().getIntExtra("sourceIndex", 0);
        if (!localHttp(url) || sourceIndex < 0) {
            Log.e(TAG, "ARG_ERROR requires a local HTTP fixture catalog and nonnegative sourceIndex"); finish(); return;
        }
        worker = new Thread(() -> {
            try {
                AndroidTransport transport = new AndroidTransport(this);
                var response = transport.fetch(new FetchRequest(url));
                if (response.status() != 200) throw new IllegalStateException("Fixture catalog request failed");
                var imported = RssImporter.parse(response.body());
                if (sourceIndex >= imported.sources().size()) throw new IllegalArgumentException("Unknown sourceIndex");
                RssSource source = imported.sources().get(sourceIndex);
                if (!localHttp(source.id())) throw new IllegalArgumentException("Probe accepts local fixture sources only");
                RssEngine engine = new RssEngine(transport, new RhinoRuntime(transport),
                        diagnostic -> Log.i(TAG, "DIAGNOSTIC code=" + diagnostic.code() + " field=" + diagnostic.field()),
                        trace -> Log.i(TAG, "FETCH stage=" + trace.stage() + " status=" + trace.status()
                                + " url=" + ResponseSignals.urlForLog(trace.responseUrl())));
                Log.i(TAG, "IMPORT_OK sources=" + imported.sources().size());
                var categories = engine.categories(source);
                Log.i(TAG, "CATEGORIES_OK count=" + categories.size());
                Page page = engine.articles(source, categories.get(0), 1, "synthetic");
                Log.i(TAG, "ARTICLES_OK count=" + page.articles().size() + " next=" + (page.nextPageUrl() != null));
                if (page.articles().isEmpty()) throw new IllegalStateException("Synthetic feed unexpectedly empty");
                ReadTarget target = engine.open(source, page.articles().get(0));
                if (target instanceof HtmlContent html) Log.i(TAG, "CONTENT_OK chars=" + html.html().length()
                        + " summary=" + html.html().contains("rss-summary")
                        + " first=" + html.html().contains("rss-page-one") + " final=" + html.html().contains("rss-page-two"));
                else Log.i(TAG, "READ_TARGET_OK kind=" + target.getClass().getSimpleName());
                Log.i(TAG, "RSS_PROBE_DONE");
            } catch (SourceFailure error) {
                Log.e(TAG, "RSS_PROBE_FAILED stage=" + error.stage + " field=" + error.field + " code=" + error.code);
            } catch (Exception error) { Log.e(TAG, "RSS_PROBE_FAILED type=" + error.getClass().getSimpleName()); }
            finally { runOnUiThread(this::finish); }
        }, "rss-compatibility-probe");
        worker.start();
    }
    private static boolean localHttp(String value) {
        try {
            URI uri = URI.create(value);
            return "http".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && ("127.0.0.1".equals(uri.getHost()) || "10.0.2.2".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        } catch (RuntimeException invalid) { return false; }
    }
    @Override protected void onDestroy() {
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
