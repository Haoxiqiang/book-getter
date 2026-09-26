package org.bookgetter.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Offline, independent audit of the live probe's saved books. Never fetches a source URL. */
public final class BookDownloadAudit {
    private BookDownloadAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("booksDirectory sourceId minimumCount");
        Path books = Path.of(args[0]);
        String sourceId = args[1];
        int minimum = Integer.parseInt(args[2]);
        int valid = 0;
        Set<String> urls = new HashSet<>();
        try (var stream = Files.list(books)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted().toList()) {
                String problem = check(directory, sourceId, urls);
                if (problem == null) valid++;
                else if (Files.isRegularFile(directory.resolve("manifest.json")))
                    System.out.println("INVALID\t" + directory.getFileName() + "\t" + problem);
            }
        }
        System.out.println("AUDIT\tvalid=" + valid + "\tdistinctUrls=" + urls.size() + "\tminimum=" + minimum);
        if (valid < minimum) throw new IllegalStateException("Only " + valid + " independently audited books");
    }

    private static String check(Path directory, String sourceId, Set<String> urls) {
        try {
            Path manifestFile = directory.resolve("manifest.json");
            Path bookFile = directory.resolve("book.txt");
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(bookFile)) return "missing manifest or TXT";
            JsonObject manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
            if (!sourceId.equals(manifest.get("sourceId").getAsString())) return "wrong source";
            String url = manifest.get("bookUrl").getAsString();
            if (!directory.getFileName().toString().equals(sha256(url.getBytes(StandardCharsets.UTF_8)))) return "wrong URL directory";
            int expected = manifest.get("chapterCount").getAsInt();
            if (expected < 1) return "no chapters";
            Path chapters = directory.resolve("chapters");
            if (!Files.isDirectory(chapters)) return "missing chapter cache";
            List<Path> files;
            try (var stream = Files.list(chapters)) {
                files = stream.filter(file -> file.getFileName().toString().endsWith(".txt")).toList();
            }
            if (files.size() != expected) return "chapter cache count " + files.size() + "/" + expected;
            for (Path file : files) {
                if (SourceEngine.invalidContent(Files.readString(file))) return "invalid chapter " + file.getFileName();
            }
            byte[] bytes = Files.readAllBytes(bookFile);
            if (bytes.length != manifest.get("bytes").getAsLong()) return "wrong TXT length";
            if (!sha256(bytes).equals(manifest.get("sha256").getAsString())) return "wrong TXT hash";
            if (!urls.add(url)) return "duplicate URL";
            return null;
        } catch (Exception error) { return error.getClass().getSimpleName() + ": " + error.getMessage(); }
    }

    private static String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
}
