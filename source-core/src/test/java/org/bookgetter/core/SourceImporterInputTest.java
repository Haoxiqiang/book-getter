package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bookgetter.core.Models.Severity;
import org.junit.jupiter.api.Test;

class SourceImporterInputTest {
    @Test void bookTypeMustNotBeTruncatedOrWrappedIntoAnotherValidType() {
        for (String value : List.of("1.5", "4294967296", "-1", "5", "null", "[]", "{}")) {
            var result = SourceImporter.parse("{\"bookSourceUrl\":\"book\",\"bookSourceType\":" + value + "}");
            assertTrue(result.sources().isEmpty(), value);
            assertTrue(result.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR), value);
        }
    }

    @Test void scalarBookIdentityAndNameRejectSingletonArrays() {
        for (String json : List.of("{\"bookSourceUrl\":[\"book\"]}",
                "{\"bookSourceUrl\":\"book\",\"bookSourceName\":[\"name\"]}")) {
            var result = SourceImporter.parse(json);
            assertTrue(result.sources().isEmpty(), json);
            assertTrue(result.diagnostics().stream().anyMatch(item -> item.severity() == Severity.ERROR));
        }
    }
}
