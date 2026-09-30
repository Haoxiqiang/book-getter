package org.bookgetter.app;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.Test;

public final class PendingCaptureExportTest {
    private static CaptureJobStore.Job job(boolean epub, Chapter selected) {
        BookHit hit = new BookHit("source", "https://test/book", "Title", "", "", "", Kind.TEXT);
        return new CaptureJobStore.Job("source", "hash", new BookDetail(hit, hit.url(), List.of()),
                List.of(selected), List.of(selected), epub, 0, Map.of());
    }

    @Test public void pickerSelectionSurvivesRecreationAndRejectsReplacedJobOrWrongFormat() {
        Chapter one = new Chapter("https://test/book", "https://test/one", "One", 0, false, false);
        CaptureJobStore.Job original = job(false, one);
        PendingCaptureExport restored = PendingCaptureExport.restore(PendingCaptureExport.forJob(original).serialize());
        assertNotNull(restored);
        assertTrue(restored.matches(original, false));
        assertFalse(restored.matches(original, true));
        assertFalse(restored.matches(job(false, one), false));
        assertFalse(restored.matches(job(true, one), true));
    }

    @Test public void invalidSavedSelectionIsRejected() {
        assertNull(PendingCaptureExport.restore(""));
        assertNull(PendingCaptureExport.restore("{\"generation\":\"\"}"));
        assertNull(PendingCaptureExport.restore("invalid"));
    }

    @Test public void legacyJobWithoutTokenKeepsPickerIdentityAcrossProgress() {
        Chapter one = new Chapter("https://test/book", "https://test/one", "One", 0, false, false);
        CaptureJobStore.Job current = job(false, one);
        CaptureJobStore.Job legacy = new CaptureJobStore.Job(current.sourceId(), current.sourceHash(),
                current.book(), current.catalog(), current.selected(), false, 0, Map.of(), null);
        CaptureJobStore.Job progressed = new CaptureJobStore.Job(current.sourceId(), current.sourceHash(),
                current.book(), current.catalog(), current.selected(), false, 1, Map.of(), null);
        assertTrue(PendingCaptureExport.forJob(legacy).matches(progressed, false));
    }
}
