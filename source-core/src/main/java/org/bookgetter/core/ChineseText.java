package org.bookgetter.core;

import com.github.liuyueyi.quick.transfer.ChineseUtils;
import com.github.liuyueyi.quick.transfer.constants.TransType;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Shares Legado's converter version and its word exclusions across all script executions. */
final class ChineseText {
    private ChineseText() {}

    private static final class Dictionary {
        static { load(); }
        static void ensureLoaded() {}

        private static void load() {
            try (InputStream stream = ChineseText.class.getResourceAsStream("t2s-exclusions.txt")) {
                if (stream == null) throw new IllegalStateException("Missing t2s exclusion dictionary");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    ChineseUtils.loadExcludeDict(TransType.TRADITIONAL_TO_SIMPLE,
                            reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load t2s exclusion dictionary", e);
            }
        }
    }

    static String t2s(String value) {
        Dictionary.ensureLoaded();
        return ChineseUtils.t2s(value);
    }
}
