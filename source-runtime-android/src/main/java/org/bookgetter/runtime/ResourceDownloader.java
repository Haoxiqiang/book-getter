package org.bookgetter.runtime;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bookgetter.core.Models.Resource;
import org.bookgetter.core.Models.Source;

/** Explicit user-triggered binary download, using the same login cookies as source fetching. */
public final class ResourceDownloader {
    private final OkHttpClient client = new OkHttpClient.Builder().cookieJar(new SharedCookieJar()).build();
    public void download(Source source, Resource resource, OutputStream output) throws IOException {
        Request.Builder request = new Request.Builder().url(resource.url());
        if (source.string("header") != null) {
            try {
                JsonParser.parseString(source.string("header")).getAsJsonObject().entrySet().forEach(entry -> {
                    request.header(entry.getKey(), entry.getValue().getAsString());
                });
            } catch (Exception ignored) { }
        }
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code() + " for " + resource.url());
            try (InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
                    output.write(buffer, 0, count);
                }
            }
        }
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
