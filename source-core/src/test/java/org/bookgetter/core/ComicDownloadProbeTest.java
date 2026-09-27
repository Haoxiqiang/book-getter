package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ComicDownloadProbeTest {
    @Test void binaryValidationRejectsHtmlAndAcceptsKnownImageSignatures() {
        assertNull(ImageSignatures.extension("<html>blocked</html>".getBytes(StandardCharsets.UTF_8)));
        byte[] jpeg = new byte[64];
        jpeg[0] = (byte) 0xff; jpeg[1] = (byte) 0xd8; jpeg[2] = (byte) 0xff;
        assertEquals("jpg", ImageSignatures.extension(jpeg));
        byte[] webp = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
        assertEquals("webp", ImageSignatures.extension(webp));
    }
}
