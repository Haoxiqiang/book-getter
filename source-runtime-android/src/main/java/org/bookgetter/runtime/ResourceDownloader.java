package org.bookgetter.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Kind;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.ResponseSignals;
import org.bookgetter.core.ImageSignatures;

/** Explicit user-triggered binary download, using the same login cookies as source fetching. */
public final class ResourceDownloader {
    private static final int MAX_REDIRECTS = 10;
    private final OkHttpClient client;
    public ResourceDownloader() { this(new OkHttpClient.Builder().cookieJar(new SharedCookieJar()).build()); }
    ResourceDownloader(OkHttpClient client) {
        // Follow redirects ourselves so each hop gets headers for its own origin.
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
    }
    public void download(Source source, Resource resource, OutputStream output) throws IOException {
        String target = resource.url();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
            Request.Builder request = new Request.Builder().url(target);
            ResourceRequestHeaders.forUrl(source, target).forEach(request::header);
            try (Response response = client.newCall(request.build()).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    HttpUrl next = location == null ? null : response.request().url().resolve(location);
                    if (next == null || !(next.isHttps() || next.scheme().equals("http")))
                        throw new IOException("Invalid resource redirect at " + ResponseSignals.urlForLog(target));
                    target = next.toString();
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null)
                    throw new IOException("HTTP " + response.code() + " for " + ResponseSignals.urlForLog(target));
                try (PushbackInputStream input = new PushbackInputStream(response.body().byteStream(), 512)) {
                    validatePayload(source, response, input);
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
                        output.write(buffer, 0, count);
                    }
                }
                return;
            }
        }
        throw new IOException("Too many resource redirects at " + ResponseSignals.urlForLog(target));
    }
    private static void validatePayload(Source source, Response response,
                                        PushbackInputStream input) throws IOException {
        String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
        if (type.startsWith("text/html") || type.startsWith("application/xhtml+xml"))
            throw new IOException("Resource returned an HTML page at " + ResponseSignals.urlForLog(response.request().url().toString()));
        if ((source.kind() == Kind.AUDIO || source.kind() == Kind.VIDEO)
                && (type.contains("mpegurl") || response.request().url().encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")))
            throw new IOException("Segmented media playlists are not supported");
        byte[] prefix = new byte[512];
        int count = 0;
        while (count < prefix.length) {
            int read = input.read(prefix, count, prefix.length - count);
            if (read == -1) break;
            count += read;
        }
        if (count > 0) input.unread(prefix, 0, count);
        if (count <= 0) throw new IOException("Empty resource response");
        // Servers sometimes label a login page as application/octet-stream. Inspect the first bytes
        // before writing anything, while preserving them for the streaming copy below.
        String beginning = new String(prefix, 0, count, StandardCharsets.UTF_8)
                .replaceFirst("^\\uFEFF?\\s*", "").toLowerCase(Locale.ROOT);
        if (beginning.startsWith("<!doctype html") || beginning.startsWith("<html")
                || beginning.startsWith("<head") || beginning.startsWith("<body"))
            throw new IOException("Resource returned an HTML page at " + ResponseSignals.urlForLog(response.request().url().toString()));
        if (source.kind() == Kind.IMAGE && ImageSignatures.extension(java.util.Arrays.copyOf(prefix, count)) == null)
            throw new IOException("Resource is not a supported image at " + ResponseSignals.urlForLog(response.request().url().toString()));
    }
    public void downloadZip(Source source, java.util.List<Resource> resources, OutputStream output) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(output);
        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            String path = android.net.Uri.parse(resource.url()).getPath();
            String extension = path != null && path.matches("(?i).*\\.(jpg|jpeg|png|gif|webp|avif)")
                    ? path.substring(path.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT) : ".bin";
            zip.putNextEntry(new ZipEntry(String.format(java.util.Locale.ROOT, "%04d%s", i + 1, extension)));
            download(source, resource, zip);
            zip.closeEntry();
        }
        zip.finish(); zip.flush();
    }
}
