package org.bookgetter.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bookgetter.core.Models.*;

/** Source failures are isolated; hits retain their exact source and URL identity. */
public final class SearchCoordinator {
    private final SourceEngine engine;
    private final Executor executor;
    public SearchCoordinator(SourceEngine engine, Executor executor) { this.engine = engine; this.executor = executor; }
    public SearchResult search(List<Source> sources, String keyword, int page) {
        Map<String, CompletableFuture<List<BookHit>>> pending = new LinkedHashMap<>();
        for (Source source : sources) pending.put(source.id(), CompletableFuture.supplyAsync(() -> engine.search(source, keyword, page), executor));
        List<BookHit> hits = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<List<BookHit>>> task : pending.entrySet()) {
            try { hits.addAll(task.getValue().join()); }
            catch (Exception error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof SourceFailure failure)
                    errors.put(task.getKey(), failure.stage + "/" + failure.field + ": " + failure.getMessage());
                else errors.put(task.getKey(), cause.toString());
            }
        }
        return new SearchResult(hits, errors);
    }
}
