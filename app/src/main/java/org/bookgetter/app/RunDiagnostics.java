package org.bookgetter.app;

import org.bookgetter.core.Models.Diagnostic;
import org.bookgetter.core.Models.SourceFailure;
import org.bookgetter.core.Models.StageTrace;

/** Formats stage reports independently of Activity widgets and task ownership. */
final class RunDiagnostics {
    private RunDiagnostics() {}
    static String formatError(Exception e) {
        if (e instanceof SourceFailure failure) return failure.stage + " / " + failure.field + " / "
                + failure.code + " / " + failure.sourceId + ": " + failure.getMessage();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
    static String diagnosticText(Iterable<Diagnostic> diagnostics) {
        StringBuilder details = new StringBuilder();
        for (Diagnostic diagnostic : diagnostics)
            details.append(diagnostic.stage()).append(" / ").append(diagnostic.field()).append(" / ")
                    .append(diagnostic.code()).append("\n").append(diagnostic.message()).append("\n\n");
        return details.toString();
    }

    static String traceText(Iterable<StageTrace> traces) {
        StringBuilder details = new StringBuilder();
        if (traces == null) return "";
        for (StageTrace trace : traces)
            details.append(trace.sourceId()).append(" / ").append(trace.stage()).append(" / ")
                    .append(trace.field()).append("\n").append(trace.method()).append(" ")
                    .append(trace.requestUrl()).append(" → ").append(trace.responseUrl())
                    .append("\nHTTP ").append(trace.status()).append(" / ")
                    .append(trace.responseQuality()).append("\n\n");
        return details.toString();
    }

}
