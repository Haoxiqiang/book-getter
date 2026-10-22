package org.bookgetter.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Source;

/**
 * Blocking downloads invoked on a worker thread. Implementations honor interruption,
 * source request policies and redirect credential boundaries. The caller owns the output
 * stream; neither operation closes it. ZIP staging belongs to the implementation.
 */
public interface ResourceDownloadService {
    void download(Source source, Resource resource, OutputStream output) throws IOException;
    void downloadZip(Source source, List<Resource> resources, OutputStream output) throws IOException;
}
