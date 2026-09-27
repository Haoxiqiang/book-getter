package org.bookgetter.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Opt-in check of the directory shape and one selected downstream collection. */
public final class MiaogongziDirectoryLiveProbe {
    private MiaogongziDirectoryLiveProbe() {}
    public static void main(String[] args) throws Exception {
        String configUrl = "http://yuedu.miaogongzi.net/shuyuan/miaogongziDY.json";
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        String config = fetch(client, configUrl);
        String page = MiaogongziDirectoryAdapter.directoryPage(config);
        if (page == null) throw new IllegalStateException("The subscription no longer identifies a supported directory");
        var collections = MiaogongziDirectoryAdapter.discover(fetch(client, page));
        System.out.println("DIRECTORY collections=" + collections.size());
        for (var collection : collections) System.out.println("COLLECTION " + collection.name() + " " + URI.create(collection.url()).getHost());
        if (args.length == 0) return;
        var chosen = collections.stream().filter(collection -> collection.name().equals(args[0]))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No collection: " + args[0]));
        var parsed = SourceImporter.parse(fetch(client, chosen.url()));
        if (parsed.sources().isEmpty()) throw new IllegalStateException("Selected collection has no BookSource");
        System.out.println("SELECTED " + chosen.name() + " bookSources=" + parsed.sources().size()
                + " diagnostics=" + parsed.diagnostics().size());
    }
    private static String fetch(HttpClient client, String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode() + " at " + URI.create(url).getHost());
        return response.body();
    }
}
