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
import org.bookgetter.core.ResponseSignals;
import org.bookgetter.core.RhinoRuntime;
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
        String directBookUrl = getIntent().getStringExtra("directBookUrl");
        boolean captureFirst = getIntent().getBooleanExtra("captureFirst", false);
        boolean catalogFirst = getIntent().getBooleanExtra("catalogFirst", false);
        boolean directCatalogOnly = directBookUrl != null && !captureFirst;
        int readableChapterIndex = getIntent().getIntExtra("chapterIndex", 0);
        long webViewDelayOverride = getIntent().getLongExtra("webViewDelayMillis", -1);
        if (catalogUrl == null || sourceFilter == null || keyword == null || readableChapterIndex < 0
                || (directBookUrl != null && !validHttpBookUrl(directBookUrl))) {
            Log.e(TAG, "ARG_ERROR catalogUrl, sourceFilter, keyword and nonnegative chapterIndex are required");
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
                List<String> responseQualities = new java.util.ArrayList<>();
                Transport observed = request -> {
                    if (request.webView() && webViewDelayOverride >= 0)
                        request = new FetchRequest(request.url(), request.method(), request.headers(), request.body(),
                                request.charset(), true, webViewDelayOverride);
                    FetchResponse response = transport.fetch(request);
                    String body = response.body() == null ? "" : response.body();
                    String title = "";
                    java.util.regex.Matcher titleMatch = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(body);
                    if (titleMatch.find()) title = titleMatch.group(1).replaceAll("<[^>]*>", "");
                    String quality = ResponseSignals.quality(response);
                    responseQualities.addAll(ResponseSignals.probeSignals(request, response));
                    String host;
                    try { host = new java.net.URI(response.url()).getHost(); }
                    catch (Exception ignored) { host = "invalid-url"; }
                    String requestHost;
                    try { requestHost = new java.net.URI(request.url()).getHost(); }
                    catch (Exception ignored) { requestHost = "invalid-url"; }
                    String responsePath;
                    try { responsePath = new java.net.URI(response.url()).getPath(); }
                    catch (Exception ignored) { responsePath = "invalid-url"; }
                    Log.i(TAG, clean("FETCH method=" + request.method() + " webView=" + request.webView()
                            + " status=" + response.status() + " host=" + host
                            + " path=" + responsePath
                            + (host.equals(requestHost) ? "" : " from=" + requestHost)
                            + " length=" + body.length() + " quality=" + quality + " title=" + title));
                    return response;
                };
                java.util.Set<String> warnedFields = new java.util.HashSet<>();
                SourceEngine engine = new SourceEngine(observed, new RhinoRuntime(observed), diagnostic -> {
                    String warning = diagnostic.code() + " " + diagnostic.field();
                    if (diagnostic.severity() == org.bookgetter.core.Models.Severity.INFO)
                        Log.i(TAG, clean("SEARCH_BRANCH " + diagnostic.code()));
                    else if (warnedFields.add(warning))
                        Log.w(TAG, clean("FIELD_WARNING " + warning));
                });
                Log.i(TAG, clean("START " + source.name() + " " + source.id() + " key=" + keyword));
                int searchQualityStart = responseQualities.size();
                List<BookHit> hits = directBookUrl == null ? engine.search(source, keyword, 1)
                        : List.of(new BookHit(source.id(), directBookUrl, directBookUrl,
                                "", "", "", source.kind()));
                boolean suspectSearch = responseQualities.subList(searchQualityStart, responseQualities.size()).stream()
                        .anyMatch(ResponseSignals::suspectEmptySearch);
                String searchStatus = directBookUrl != null ? "SEARCH_SKIPPED_DIRECT_BOOK" : hits.isEmpty()
                        ? (suspectSearch ? "SEARCH_RESPONSE_SUSPECT" : "SEARCH_ZERO_UNVERIFIED") : "SEARCH_OK";
                Log.i(TAG, clean(searchStatus
                        + " hits=" + hits.size() + (hits.isEmpty() ? "" : " first=" + hits.get(0).name())));
                if ((captureFirst || catalogFirst || directCatalogOnly) && !hits.isEmpty()) {
                    int qualityStart = responseQualities.size();
                    BookDetail detail = engine.detail(source, hits.get(0));
                    boolean suspect = responseQualities.subList(qualityStart, responseQualities.size()).stream()
                            .anyMatch(value -> value.equals("BUSINESS_ERROR") || value.equals("CHALLENGE_PAGE")
                                    || value.equals("PARKED_DOMAIN") || value.equals("APP_UPGRADE_REQUIRED")
                                    || value.equals("PAGE_NOT_FOUND"));
                    Log.i(TAG, (suspect ? "DETAIL_SUSPECT" : "DETAIL_OK")
                            + " tocPresent=" + (detail.tocUrl() != null && !detail.tocUrl().isBlank()));
                    List<Chapter> chapters = engine.chapters(source, detail);
                    long vipCount = chapters.stream().filter(Chapter::vip).count();
                    long payCount = chapters.stream().filter(Chapter::isPay).count();
                    long volumeCount = chapters.stream().filter(Chapter::volume).count();
                    long jsPlaceholders = chapters.stream().filter(chapter -> !chapter.volume()
                            && ResponseSignals.javascriptPlaceholderUrl(chapter.url())).count();
                    Log.i(TAG, clean("CATALOG_OK chapters=" + chapters.size()
                            + " volumes=" + volumeCount + " javascriptPlaceholders=" + jsPlaceholders
                            + " vip=" + vipCount + " isPay=" + payCount
                            + (chapters.isEmpty() ? "" : " firstUrl=" + ResponseSignals.urlForLog(chapters.get(0).url()))));
                    if (catalogFirst || directCatalogOnly) return;
                    List<Chapter> readableChapters = chapters.stream().filter(chapter -> !chapter.volume()).toList();
                    if (readableChapterIndex >= readableChapters.size())
                        throw new IllegalArgumentException("Readable chapter index " + readableChapterIndex
                                + " exceeds " + readableChapters.size());
                    Chapter first = readableChapters.get(readableChapterIndex);
                    if (first != null) {
                        Log.i(TAG, clean("CONTENT_SELECTED index=" + readableChapterIndex + " title=" + first.title()));
                        int contentQualityStart = responseQualities.size();
                        var content = engine.content(source, detail, first,
                                SourceEngine.nextReadableUrl(chapters, first));
                        boolean suspectContent = responseQualities.subList(contentQualityStart, responseQualities.size()).stream()
                                .anyMatch(value -> value.equals("BUSINESS_ERROR") || value.equals("APP_UPGRADE_REQUIRED")
                                        || value.equals("CHALLENGE_PAGE") || value.equals("PARKED_DOMAIN"));
                        Log.i(TAG, clean((suspectContent ? "CONTENT_SUSPECT" : "CONTENT_OK")
                                + " rawCharacters=" + (content.text() == null ? 0 : content.text().length())
                                + " visibleCharacters=" + ResponseSignals.visibleCharacters(content.text())
                                + " resources=" + content.resources().size()));
                    }
                }
            } catch (Exception error) {
                SourceFailure failure = error instanceof SourceFailure value ? value : null;
                String stage = failure == null ? "" : failure.stage + "." + failure.field + " ";
                Throwable root = error;
                while (root.getCause() != null) root = root.getCause();
                Log.e(TAG, clean("ERROR " + stage + error.getClass().getSimpleName() + ": " + error.getMessage()
                        + " cause=" + root.getClass().getSimpleName() + ": " + root.getMessage()), error);
            } finally {
                runOnUiThread(this::finish);
            }
        }, "book-source-compatibility-probe").start();
    }

    private static String clean(String text) { return text.replaceAll("[\\r\\n\\t]+", " "); }

    private static boolean validHttpBookUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return (uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }
}
