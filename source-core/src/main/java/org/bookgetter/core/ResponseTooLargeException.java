package org.bookgetter.core;

import java.io.IOException;

/** The transport stopped reading before a remote response exhausted memory. */
public final class ResponseTooLargeException extends IOException {
    public ResponseTooLargeException(int maxBytes) {
        super("Response exceeds " + (maxBytes / (1024 * 1024)) + " MiB limit");
    }
}
