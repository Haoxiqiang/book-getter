package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bookgetter.core.Models.Severity;
import org.bookgetter.core.Models.SourceFailure;
import org.junit.jupiter.api.Test;

/** Malformed model values must be rejected before they enter a durable source snapshot. */
class RssImporterInputTest {
    @Test void mediaTypeMustBeAnExactInRangeInteger() {
        for (String value : List.of("1.5", "4294967296", "-1", "3", "null", "[]", "{}")) {
            var result = RssImporter.parse("{\"sourceUrl\":\"rss\",\"type\":" + value + "}");
            assertTrue(result.sources().isEmpty(), value);
            assertTrue(result.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR), value);
        }
    }

    @Test void scalarModelFieldsRejectSingletonArrays() {
        for (String json : List.of("{\"sourceUrl\":[\"rss\"]}",
                "{\"sourceUrl\":\"rss\",\"sourceName\":[\"name\"]}")) {
            var result = RssImporter.parse(json);
            assertTrue(result.sources().isEmpty(), json);
            assertTrue(result.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR));
        }
    }

    @Test void invalidOptionalScriptIsNotAddedBeforeItsErrorIsReported() {
        for (String field : List.of("loginCheckJs", "coverDecodeJs")) {
            var result = RssImporter.parse("[{\"sourceUrl\":\"invalid\",\"" + field + "\":{}},{\"sourceUrl\":\"valid\"}]");
            assertEquals(List.of("valid"), result.sources().stream().map(source -> source.id()).toList());
            assertTrue(result.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR));
        }
    }

    @Test void byteLimitAppliesToUtf8RatherThanOnlyUtf16Length() {
        String name = "中".repeat((16 * 1024 * 1024) / 3 + 1);
        String json = "{\"sourceUrl\":\"rss\",\"sourceName\":\"" + name + "\"}";
        assertTrue(json.length() < 16 * 1024 * 1024);
        assertThrows(SourceFailure.class, () -> RssImporter.parse(json));
    }

    @Test void sourceCountLimitIsCheckedBeforeAcceptingEntries() {
        String json = "[" + "{\"sourceUrl\":\"duplicate\"},".repeat(10_000) + "{\"sourceUrl\":\"last\"}]";
        assertThrows(SourceFailure.class, () -> RssImporter.parse(json));
    }

    @Test void unknownFieldsRemainPreservedAndValidPeersSurviveInvalidEntries() {
        var result = RssImporter.parse("[{\"sourceUrl\":\"rss\",\"type\":2,\"extension\":{\"nested\":[1,2]}},false]");
        assertEquals(1, result.sources().size());
        assertEquals(2, result.sources().get(0).json().getAsJsonObject("extension").getAsJsonArray("nested").size());
        assertEquals(1, result.diagnostics().stream().filter(item -> item.severity() == Severity.ERROR).count());
    }
}
