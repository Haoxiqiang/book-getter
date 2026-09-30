package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import org.bookgetter.core.Models.Stage;
import org.bookgetter.core.Models.SourceFailure;
import org.junit.jupiter.api.Test;

class SourceImporterTest {
    @Test void logHostIsPartialAtItsRuleField() {
        var result = SourceImporter.parse("{\"bookSourceUrl\":\"https://books.test\","
                + "\"ruleSearch\":{\"bookList\":\"@js:java.log(result); result\"}}");
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().equals("PARTIAL_JS_HOST")
                && "ruleSearch.bookList".equals(item.field()) && item.message().contains("log")));
        assertFalse(result.diagnostics().stream().anyMatch(item -> item.code().equals("UNSUPPORTED_JS_HOST")
                && "ruleSearch.bookList".equals(item.field())));
    }

    @Test void malformedCollectionEntryDoesNotDiscardValidNeighbors() {
        var result = SourceImporter.parse("""
                [{"bookSourceUrl":"https://first.test","bookSourceName":[]},
                 {"bookSourceUrl":"https://second.test","bookSourceName":"Second"}]
                """);
        assertEquals(1, result.sources().size());
        assertEquals("Second", result.sources().get(0).name());
        assertEquals("INVALID_ENTRY", result.diagnostics().get(0).code());
        assertEquals(0, result.diagnostics().get(0).message().indexOf("Entry 0"));
    }

    @Test void malformedSourceUrlsReportsImportField() {
        SourceFailure failure = assertThrows(SourceFailure.class,
                () -> SourceImporter.parse("{\"sourceUrls\":{\"url\":\"https://example.test\"}}"));
        assertEquals(Stage.IMPORT, failure.stage);
        assertEquals("sourceUrls", failure.field);
        assertThrows(SourceFailure.class,
                () -> SourceImporter.parse("{\"sourceUrls\":[\"https://example.test\",9]}"));
        assertThrows(SourceFailure.class,
                () -> SourceImporter.parse("{\"sourceUrls\":[\"https://\"]}"));
        assertThrows(SourceFailure.class,
                () -> SourceImporter.parse("{\"sourceUrls\":[\"https://user:pass@example.test/list.json\"]}"));
    }
}
