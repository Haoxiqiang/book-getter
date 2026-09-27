package org.bookgetter.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.bookgetter.core.Models.*;

/** Source failures are isolated; hits retain their exact source and URL identity. */
public final class SearchCoordinator {
    private final SourceEngine engine;
    private final ExecutorService executor;
    public SearchCoordinator(SourceEngine engine, ExecutorService executor) { this.engine = engine; this.executor = executor; }
    public SearchResult search(List<Source> sources, String keyword, int page) {
        List<Callable<List<BookHit>>> requests = new ArrayList<>();
        for (Source source : sources) requests.add(() -> engine.search(source, keyword, page));
        List<Future<List<BookHit>>> pending;
        try {
            // invokeAll cancels child requests when the coordinating UI task is interrupted.
            pending = executor.invokeAll(requests);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Search cancelled");
        }
        List<BookHit> hits = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, SourceFailure> failures = new LinkedHashMap<>();
        for (int index = 0; index < pending.size(); index++) {
            String sourceId = sources.get(index).id();
            try { hits.addAll(pending.get(index).get()); }
            catch (InterruptedException e) {
                pending.forEach(task -> task.cancel(true));
                Thread.currentThread().interrupt();
                throw new CancellationException("Search cancelled");
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof SourceFailure failure) {
                    failures.put(sourceId, failure);
                    errors.put(sourceId, failure.stage + "/" + failure.field + "/" + failure.code + ": " + failure.getMessage());
                }
                else errors.put(sourceId, cause.toString());
            }
        }
        return new SearchResult(hits, errors, failures);
    }
}
