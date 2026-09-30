package org.bookgetter.core;

import java.util.Map;
import java.util.function.Consumer;
import org.bookgetter.core.Models.*;

/** Transport, response classification and trace handling shared by capture stages. */
final class StageFetcher {
    private final Transport transport;
    private final Consumer<StageTrace> traces;

    StageFetcher(Transport transport, Consumer<StageTrace> traces) {
        this.transport = transport;
        this.traces = traces;
    }

    FetchResponse fetch(FetchRequest request, Stage stage, String sourceId, String field) {
        FetchResponse response;
        try { response = transport.fetch(request); }
        catch (WebContentTransport.HttpStatusException e) {
            throw browserStatusFailure(request, stage, sourceId, field, e);
        }
        catch (ResponseTooLargeException e) {
            throw new SourceFailure(stage, sourceId, field, "RESPONSE_TOO_LARGE", e.getMessage(), e);
        }
        catch (Exception e) {
            throw new SourceFailure(stage, sourceId, field, "REQUEST_ERROR",
                    "Request failed at " + ResponseSignals.urlForLog(request.url())
                            + " (" + e.getClass().getSimpleName() + ")", e);
        }
        trace(request, response, stage, sourceId, field);
        validateResponse(response, request, stage, sourceId, field);
        return response;
    }

    FetchResponse fetchContent(FetchRequest request, Source source, boolean firstPage) {
        String sourceRegex = firstPage ? source.rule("ruleContent", "sourceRegex") : null;
        String webJs = source.rule("ruleContent", "webJs");
        String browserField = sourceRegex != null && !sourceRegex.isBlank()
                ? "ruleContent.sourceRegex" : "ruleContent.webJs";
        if ((sourceRegex == null || sourceRegex.isBlank()) && (webJs == null || webJs.isBlank()))
            return fetch(request, Stage.CONTENT, source.id(), "ruleContent.content");
        if (!(transport instanceof WebContentTransport browser))
            throw new SourceFailure(Stage.CONTENT, source.id(), browserField,
                    "UNSUPPORTED", "Content rule requires a browser transport");
        try {
            FetchResponse response = browser.fetchContent(request, sourceRegex, webJs);
            trace(request, response, Stage.CONTENT, source.id(), "ruleContent.content");
            validateResponse(response, request, Stage.CONTENT, source.id(), "ruleContent.content");
            return response;
        } catch (SourceFailure e) { throw e; }
        catch (WebContentTransport.HttpStatusException e) {
            throw browserStatusFailure(request, Stage.CONTENT, source.id(), browserField, e);
        }
        catch (WebContentTransport.NoMatchingResourceException e) {
            throw new SourceFailure(Stage.CONTENT, source.id(), "ruleContent.sourceRegex", "SOURCE_REGEX_NO_MATCH",
                    e.getMessage(), e);
        }
        catch (ResponseTooLargeException e) {
            throw new SourceFailure(Stage.CONTENT, source.id(), browserField,
                    "RESPONSE_TOO_LARGE", e.getMessage(), e);
        }
        catch (Exception e) {
            throw new SourceFailure(Stage.CONTENT, source.id(), browserField, "REQUEST_ERROR",
                    "Browser content request failed at " + ResponseSignals.urlForLog(request.url())
                            + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private void trace(FetchRequest request, FetchResponse response, Stage stage, String sourceId, String field) {
        traces.accept(new StageTrace(stage, sourceId, field, request.method(),
                ResponseSignals.urlForLog(request.url()), ResponseSignals.urlForLog(response.url()),
                response.status(), ResponseSignals.quality(response)));
    }

    private SourceFailure browserStatusFailure(FetchRequest request, Stage stage, String sourceId, String field,
                                               WebContentTransport.HttpStatusException error) {
        FetchResponse failed = new FetchResponse(error.url(), "", error.status(), Map.of());
        trace(request, failed, stage, sourceId, field);
        try { validateResponse(failed, request, stage, sourceId, field); }
        catch (SourceFailure failure) { return failure; }
        return new SourceFailure(stage, sourceId, field, "REQUEST_ERROR",
                "Browser reported an unexpected HTTP status " + error.status());
    }

    private static void validateResponse(FetchResponse response, FetchRequest request,
                                         Stage stage, String sourceId, String field) {
        if (response.status() >= 300 && response.status() < 400)
            throw new SourceFailure(stage, sourceId, field, "UNRESOLVED_REDIRECT",
                    "Unresolved HTTP redirect " + response.status() + " at " + ResponseSignals.urlForLog(request.url()));
        if (response.status() >= 400)
            throw new SourceFailure(stage, sourceId, field,
                    response.status() == 401 || response.status() == 403 ? "ACCESS_DENIED" : "HTTP_ERROR",
                    "HTTP " + response.status() + " at " + ResponseSignals.urlForLog(request.url()));
        String quality = ResponseSignals.quality(response);
        if (quality.equals("BUSINESS_ERROR") || quality.equals("EMPTY_RESPONSE")
                || quality.equals("LOGIN_REQUIRED") || quality.equals("CHALLENGE_PAGE")
                || quality.equals("PARKED_DOMAIN") || quality.equals("CLIENT_SIDE_REDIRECT")
                || quality.equals("APP_UPGRADE_REQUIRED") || quality.equals("PAGE_NOT_FOUND"))
            throw new SourceFailure(stage, sourceId, field, quality,
                    "Unusable source response: " + quality + " (HTTP " + response.status() + ")");
    }
}
