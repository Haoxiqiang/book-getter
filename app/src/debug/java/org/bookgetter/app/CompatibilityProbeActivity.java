package org.bookgetter.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.util.List;
import org.bookgetter.core.Models.BookDetail;
import org.bookgetter.core.Models.BookHit;
import org.bookgetter.core.Models.Chapter;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.Models.SourceFailure;
import org.bookgetter.core.SourceEngine;
import org.bookgetter.core.SourceImporter;
import org.bookgetter.core.Transport;
import org.bookgetter.runtime.AndroidTransport;

/** Debug-build-only entry point for repeatable on-device source-stage diagnostics. */
public final class CompatibilityProbeActivity extends Activity {
    private static final String TAG = "BookGetterProbe";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String catalogUrl = getIntent().getStringExtra("catalogUrl");
        String sourceFilter = getIntent().getStringExtra("sourceFilter");
        String keyword = getIntent().getStringExtra("keyword");
        boolean captureFirst = getIntent().getBooleanExtra("captureFirst", false);
        long webViewDelayOverride = getIntent().getLongExtra("webViewDelayMillis", -1);
        if (catalogUrl == null || sourceFilter == null || keyword == null) {
            Log.e(TAG, "ARG_ERROR catalogUrl, sourceFilter and keyword are required");
            finish();
            return;
        }
        new Thread(() -> {
            try {
                AndroidTransport transport = new AndroidTransport(this);
                String json = transport.fetch(new FetchRequest(catalogUrl)).body();
                Source source = SourceImporter.parse(json).sources().stream()
                        .filter(item -> item.id().contains(sourceFilter) || item.name().contains(sourceFilter))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("No source: " + sourceFilter));
                Transport observed = request -> {
                    if (request.webView() && webViewDelayOverride >= 0)
                        request = new FetchRequest(request.url(), request.method(), request.headers(), request.body(),
                                request.charset(), true, webViewDelayOverride);
                    FetchResponse response = transport.fetch(request);
                    String body = response.body() == null ? "" : response.body();
                    String title = "";
                    java.util.regex.Matcher titleMatch = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(body);
                    if (titleMatch.find()) title = titleMatch.group(1).replaceAll("<[^>]*>", "");
                    String host;
                    try { host = new java.net.URI(response.url()).getHost(); }
                    catch (Exception ignored) { host = "invalid-url"; }
                    Log.i(TAG, clean("FETCH method=" + request.method() + " webView=" + request.webView()
                            + " status=" + response.status() + " host=" + host
                            + " length=" + body.length() + " title=" + title));
                    return response;
                };
                SourceEngine engine = new SourceEngine(observed);
                Log.i(TAG, clean("START " + source.name() + " " + source.id() + " key=" + keyword));
                List<BookHit> hits = engine.search(source, keyword, 1);
                Log.i(TAG, clean("SEARCH_OK hits=" + hits.size() + (hits.isEmpty() ? "" : " first=" + hits.get(0).name())));
                if (captureFirst && !hits.isEmpty()) {
                    BookDetail detail = engine.detail(source, hits.get(0));
                    Log.i(TAG, "DETAIL_OK tocPresent=" + (detail.tocUrl() != null && !detail.tocUrl().isBlank()));
                    List<Chapter> chapters = engine.chapters(source, detail);
                    Log.i(TAG, clean("CATALOG_OK chapters=" + chapters.size()));
                    Chapter first = chapters.stream().filter(chapter -> !chapter.volume()).findFirst().orElse(null);
                    if (first != null) {
                        var content = engine.content(source, detail, first,
                                chapters.size() > first.index() + 1 ? chapters.get(first.index() + 1).url() : null);
                        Log.i(TAG, clean("CONTENT_OK characters=" + (content.text() == null ? 0 : content.text().length())
                                + " resources=" + content.resources().size()));
                    }
                }
            } catch (Exception error) {
                SourceFailure failure = error instanceof SourceFailure value ? value : null;
                String stage = failure == null ? "" : failure.stage + "." + failure.field + " ";
                Log.e(TAG, clean("ERROR " + stage + error.getClass().getSimpleName() + ": " + error.getMessage()));
            } finally {
                runOnUiThread(this::finish);
            }
        }, "book-source-compatibility-probe").start();
    }

    private static String clean(String text) { return text.replaceAll("[\\r\\n\\t]+", " "); }
}
