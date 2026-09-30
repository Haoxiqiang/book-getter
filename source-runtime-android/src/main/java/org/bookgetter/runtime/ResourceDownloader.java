package org.bookgetter.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    static final long MAX_STAGED_IMAGE_BYTES = 512L * 1024 * 1024;
    private static final long MIN_FREE_BYTES = 32L * 1024 * 1024;
    @FunctionalInterface private interface Destination { OutputStream open(String extension) throws IOException; }
    private final OkHttpClient client;
    private final File temporaryDirectory;
    private final long maxStagedBytes;
    public ResourceDownloader() { this(new File(System.getProperty("java.io.tmpdir", "."))); }
    public ResourceDownloader(File temporaryDirectory) {
        this(new OkHttpClient.Builder().cookieJar(new SharedCookieJar()).build(), temporaryDirectory,
                MAX_STAGED_IMAGE_BYTES);
    }
    static ResourceDownloader withClient(OkHttpClient client) {
        return new ResourceDownloader(client, new File(System.getProperty("java.io.tmpdir", ".")),
                MAX_STAGED_IMAGE_BYTES);
    }
    static ResourceDownloader withClient(OkHttpClient client, File temporaryDirectory, long maxStagedBytes) {
        return new ResourceDownloader(client, temporaryDirectory, maxStagedBytes);
    }
    private ResourceDownloader(OkHttpClient client, File temporaryDirectory, long maxStagedBytes) {
        if (temporaryDirectory == null || maxStagedBytes <= 0)
            throw new IllegalArgumentException("Temporary directory and staging limit are required");
        // Follow redirects ourselves so each hop gets headers for its own origin.
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        this.temporaryDirectory = temporaryDirectory;
        this.maxStagedBytes = maxStagedBytes;
    }
    public void download(Source source, Resource resource, OutputStream output) throws IOException {
        transfer(source, resource, extension -> output, false);
    }
    private String transfer(Source source, Resource resource, Destination destination,
                            boolean closeDestination) throws IOException {
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
                    String extension = validatePayload(source, response, input);
                    OutputStream output = destination.open(extension);
                    if (closeDestination) {
                        try (OutputStream staged = output) { copy(input, staged); }
                    } else copy(input, output);
                    return extension;
                }
            }
        }
        throw new IOException("Too many resource redirects at " + ResponseSignals.urlForLog(target));
    }
    private static void copy(java.io.InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
            output.write(buffer, 0, count);
        }
    }
    private static String validatePayload(Source source, Response response,
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
        if (source.kind() == Kind.IMAGE) {
            String extension = ImageSignatures.extension(java.util.Arrays.copyOf(prefix, count));
            if (extension == null)
                throw new IOException("Resource is not a supported image at " + ResponseSignals.urlForLog(response.request().url().toString()));
            return extension;
        }
        return null;
    }
    public void downloadZip(Source source, java.util.List<Resource> resources, OutputStream output) throws IOException {
        if (source.kind() != Kind.IMAGE) throw new IOException("Image ZIP requires an image source");
        if (!temporaryDirectory.isDirectory() && !temporaryDirectory.mkdirs())
            throw new IOException("Cannot create image staging directory");
        File staging = new File(temporaryDirectory, "cbz-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot create image staging directory");
        List<File> files = new ArrayList<>();
        List<String> extensions = new ArrayList<>();
        long[] stagedBytes = {0};
        Throwable failure = null;
        try {
            for (int i = 0; i < resources.size(); i++) {
                File image = new File(staging, String.format(Locale.ROOT, "%04d.part", i + 1));
                files.add(image);
                String extension = transfer(source, resources.get(i), detected ->
                        new LimitedOutputStream(new FileOutputStream(image), stagedBytes, maxStagedBytes, staging), true);
                extensions.add(extension);
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
            ZipOutputStream zip = new ZipOutputStream(output);
            for (int i = 0; i < files.size(); i++) {
                zip.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "%04d.%s", i + 1, extensions.get(i))));
                try (FileInputStream input = new FileInputStream(files.get(i))) { copy(input, zip); }
                zip.closeEntry();
            }
            zip.finish(); zip.flush();
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            IOException cleanup = cleanup(staging, files);
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long[] stagedBytes;
        private final long limit;
        private final File staging;
        LimitedOutputStream(OutputStream output, long[] stagedBytes, long limit, File staging) {
            super(output);
            this.stagedBytes = stagedBytes;
            this.limit = limit;
            this.staging = staging;
        }
        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > limit - stagedBytes[0])
                throw new IOException("Comic image staging exceeds " + limit + " byte limit");
            long free = staging.getUsableSpace();
            if (free > 0 && free < MIN_FREE_BYTES + length)
                throw new IOException("Insufficient space for comic image staging");
            out.write(bytes, offset, length);
            stagedBytes[0] += length;
        }
    }
    private static IOException cleanup(File staging, List<File> files) {
        IOException failure = null;
        for (File file : files) if (file.exists() && !file.delete()) {
            IOException error = new IOException("Cannot remove staged image: " + file.getName());
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (!staging.delete()) {
            IOException error = new IOException("Cannot remove image staging directory");
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        return failure;
    }
}
