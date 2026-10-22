package org.bookgetter.app;

import org.bookgetter.business.ChapterStore;

import static org.junit.Assert.*;
import java.util.List;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.SourceImporter;
import org.junit.Test;

public final class PendingResourceDownloadTest {
    @Test public void selectionSurvivesDocumentPickerActivityRecreation() {
        Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://source.test\"}").sources().get(0);
        List<Resource> resources = List.of(new Resource("https://cdn.test/page.jpg", "Page", "image/*"));
        PendingResourceDownload original = PendingResourceDownload.forSource(source, resources);
        PendingResourceDownload restored = PendingResourceDownload.restore(original.serialize());
        assertEquals(original, restored);
        assertEquals(source.id(), restored.sourceId());
        assertEquals(ChapterStore.sourceHash(source), restored.sourceHash());
    }

    @Test public void incompleteRestoredSelectionIsRejected() {
        assertNull(PendingResourceDownload.restore("{\"sourceId\":\"https://source.test\",\"resources\":[]}"));
        assertNull(PendingResourceDownload.restore("{\"sourceId\":\"https://source.test\","
                + "\"sourceHash\":\"hash\",\"resources\":[{\"url\":\"javascript:alert(1)\"}]}"));
        assertNull(PendingResourceDownload.restore("not json"));
    }
}
