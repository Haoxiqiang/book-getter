package org.bookgetter.core;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bookgetter.core.Models.BookHit;
import org.bookgetter.core.Models.BookDetail;
import org.bookgetter.core.Models.Chapter;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;
import org.bookgetter.core.Models.Source;
import org.bookgetter.core.Models.SourceFailure;
import org.bookgetter.export.BookExporter;

/** Manual, opt-in live probe. Never part of the deterministic unit-test task. */
public final class LiveSourceProbe {
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    private LiveSourceProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("--catalog") || args[0].equals("--json-url")))
            throw new IllegalArgumentException("Usage: --catalog <catalog-directory> | --json-url <source-json-url> [--source name-or-url] [--capture-first] [queries...]");
        int queryStart = 2;
        String sourceFilter = "";
        if (args.length >= 4 && args[2].equals("--source")) {
            sourceFilter = args[3];
            queryStart = 4;
        }
        boolean captureFirst = args.length > queryStart && args[queryStart].equals("--capture-first");
        if (captureFirst) queryStart++;
        Path exportFirst = null;
        if (args.length > queryStart + 1 && args[queryStart].equals("--export-first")) {
            exportFirst = Path.of(args[queryStart + 1]);
            captureFirst = true;
            queryStart += 2;
        }
        List<String> queries = args.length == queryStart ? List.of("诡秘之主", "无职转生")
                : List.of(args).subList(queryStart, args.length);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(12)).build();
        List<String> responseQualities = new ArrayList<>();
        Transport transport = request -> {
            FetchResponse response = fetch(client, request);
            String body = response.body() == null ? "" : response.body();
            String title = "";
            Matcher titleMatch = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(body);
            if (titleMatch.find()) title = titleMatch.group(1).replaceAll("<[^>]*>", "");
            String quality = ResponseSignals.quality(response);
            responseQualities.add(quality);
            String host;
            try { host = URI.create(response.url()).getHost(); }
            catch (Exception ignored) { host = "invalid-url"; }
            String redirectHost = "";
            if (quality.equals("UNRESOLVED_REDIRECT")) {
                String location = response.headers().getOrDefault("location", "");
                try { redirectHost = URI.create(location).getHost(); }
                catch (Exception ignored) { redirectHost = "invalid-url"; }
            }
            System.out.println(row("FETCH", host, Integer.toString(response.status()),
                    Integer.toString(body.length()), title, quality + (redirectHost.isBlank() ? "" : " to=" + redirectHost)));
            return response;
        };
        String snapshot;
        String sourceJson;
        if (args[0].equals("--catalog")) {
            Path catalog = Path.of(args[1]);
            snapshot = Files.readString(catalog.resolve("CURRENT")).trim();
            Path uniqueFile = catalog.resolve("snapshots").resolve(snapshot).resolve("unique.json");
            sourceJson = Files.readString(uniqueFile);
        } else {
            URI input = URI.create(args[1]);
            if (!input.getScheme().equalsIgnoreCase("https"))
                throw new IllegalArgumentException("Supplemental source JSON must use HTTPS");
            sourceJson = fetch(client, new FetchRequest(input.toString())).body();
            snapshot = "supplemental:" + input.getHost();
        }
        Map<String, Source> unique = new LinkedHashMap<>();
        SourceImporter.parse(sourceJson).sources()
                .forEach(source -> unique.putIfAbsent(source.id(), source));
        System.out.println("INVENTORY\tsnapshot=" + snapshot + "\tuniqueSources=" + unique.size());
        System.out.println("source\tkeyword\tstatus\thits\tfirstTitle\tdetail");
        SourceEngine engine = new SourceEngine(transport);
        for (Source source : unique.values()) {
            if (!source.id().contains(sourceFilter) && !source.name().contains(sourceFilter)) continue;
            for (String keyword : queries) {
                try {
                    List<BookHit> hits = engine.search(source, keyword, 1);
                    String first = hits.isEmpty() ? "" : hits.get(0).name();
                    System.out.println(row(source.name(), keyword, hits.isEmpty() ? "ZERO_UNVERIFIED" : "OK",
                            Integer.toString(hits.size()), first, ""));
                    if (captureFirst && !hits.isEmpty()) {
                        try {
                            int qualityStart = responseQualities.size();
                            BookDetail detail = engine.detail(source, hits.get(0));
                            boolean suspect = responseQualities.subList(qualityStart, responseQualities.size()).stream()
                                    .anyMatch(value -> value.equals("BUSINESS_ERROR") || value.equals("CHALLENGE_PAGE")
                                            || value.equals("PARKED_DOMAIN"));
                            System.out.println(row(source.name(), keyword, suspect ? "DETAIL_SUSPECT" : "DETAIL_OK",
                                    "", detail.hit().name(), detail.tocUrl()));
                            List<Chapter> chapters = engine.chapters(source, detail);
                            System.out.println(row(source.name(), keyword, "CATALOG_OK", Integer.toString(chapters.size()), "", ""));
                            if (!chapters.isEmpty() && exportFirst == null) {
                                var content = engine.content(source, detail, chapters.stream()
                                        .filter(chapter -> !chapter.volume()).findFirst().orElseThrow());
                                System.out.println(row(source.name(), keyword, "CONTENT_OK", "", "",
                                        "characters=" + (content.text() == null ? 0 : content.text().length())));
                            }
                            if (exportFirst != null) {
                                var contents = new ArrayList<org.bookgetter.core.Models.ChapterContent>();
                                for (Chapter chapter : chapters) {
                                    if (chapter.volume()) continue;
                                    contents.add(engine.content(source, detail, chapter));
                                    Thread.sleep(1500);
                                }
                                Files.createDirectories(exportFirst.toAbsolutePath().getParent());
                                Path temporary = exportFirst.resolveSibling(exportFirst.getFileName() + ".part");
                                try (var output = Files.newOutputStream(temporary)) {
                                    new BookExporter().write(BookExporter.Format.TXT, detail, contents, output);
                                }
                                Files.move(temporary, exportFirst, StandardCopyOption.REPLACE_EXISTING);
                                System.out.println(row(source.name(), keyword, "EXPORT_OK", Integer.toString(contents.size()), "",
                                        "bytes=" + Files.size(exportFirst)));
                            }
                        } catch (Exception e) {
                            System.out.println(row(source.name(), keyword, "CAPTURE_ERROR", "", "",
                                    diagnostic(e)));
                        }
                    }
                } catch (Exception e) {
                    System.out.println(row(source.name(), keyword, "ERROR", "", "", diagnostic(e)));
                }
                // Live sites may rate-limit or challenge clients; avoid a burst of requests.
                Thread.sleep(1500);
            }
        }
    }

    static FetchResponse fetch(HttpClient client, FetchRequest request) throws Exception {
        if (request.webView()) throw new UnsupportedOperationException("WebView request needs Android runtime");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(Duration.ofSeconds(15));
        builder.header("User-Agent", "BookGetterLiveProbe/1.0 (manual compatibility test)");
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            String name = header.getKey();
            if (name.equalsIgnoreCase("User-Agent")) continue;
            // JDK HttpClient owns hop-by-hop headers; Android/Legado may accept them.
            if (name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Host")
                    || name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")
                    || name.equalsIgnoreCase("Upgrade") || name.equalsIgnoreCase("Expect")) {
                System.out.println(row("TRANSPORT_NOTE", name, "JDK-managed header omitted"));
                continue;
            }
            builder.header(name, header.getValue());
        }
        if (request.headers().containsKey("User-Agent")) builder.setHeader("User-Agent", request.headers().get("User-Agent"));
        if (request.body() == null) builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        else builder.method(request.method(), HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        Matcher match = CHARSET.matcher(contentType);
        Charset charset = request.charset() == null ? (match.find() ? Charset.forName(match.group(1)) : StandardCharsets.UTF_8)
                : Charset.forName(request.charset());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> headers.put(key, String.join(", ", values)));
        return new FetchResponse(response.uri().toString(), new String(response.body(), charset), response.statusCode(), headers);
    }

    private static String row(String... columns) {
        List<String> clean = new ArrayList<>();
        for (String column : columns) clean.add(column == null ? "" : column.replaceAll("[\\t\\r\\n]+", " "));
        return String.join("\t", clean);
    }

    private static String diagnostic(Throwable error) {
        SourceFailure failure = null;
        Throwable root = error;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SourceFailure sourceFailure) failure = sourceFailure;
            root = current;
        }
        String stage = failure == null ? "" : failure.stage + "." + failure.field + " ";
        StackTraceElement location = null;
        for (StackTraceElement frame : root.getStackTrace()) {
            if (frame.getClassName().startsWith("org.bookgetter.")) { location = frame; break; }
        }
        String where = location == null ? "" : " at " + location.getClassName() + ":" + location.getLineNumber();
        return stage + root.getClass().getSimpleName() + ": " + root.getMessage() + where;
    }
}
