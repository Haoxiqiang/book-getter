package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

final class LiveSourceProbeTest {
    @Test void gzipResponseIsDecodedBeforeRuleParsing() throws Exception {
        byte[] json = "{\"data\":[{\"novelName\":\"Fixture\"}]}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(buffer)) { compressed.write(json); }
        assertArrayEquals(json, LiveSourceProbe.decodeBody(buffer.toByteArray(), "gzip"));
        assertArrayEquals(json, LiveSourceProbe.decodeBody(json, "identity"));
    }
}
