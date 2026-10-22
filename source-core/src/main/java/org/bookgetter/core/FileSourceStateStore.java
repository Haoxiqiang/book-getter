package org.bookgetter.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Atomic, bounded per-source state stored beneath an application-private directory. */
public final class FileSourceStateStore implements SourceStateStore {
    private static final Object LOCK = new Object();
    private static final int MAX_BYTES = 1024 * 1024;
    private final File directory;
    public FileSourceStateStore(File directory) {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create state directory");
    }
    public String get(String sourceKey, String key) { return snapshot(sourceKey).getOrDefault(key, ""); }
    public Map<String, String> snapshot(String sourceKey) { synchronized (LOCK) {
        File file = file(sourceKey);
        if (!file.isFile()) return Map.of();
        if (file.length() > MAX_BYTES) throw new IllegalStateException("Source state exceeds limit");
        try (Reader input = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(input).getAsJsonObject();
            Map<String, String> state = new LinkedHashMap<>();
            json.entrySet().forEach(entry -> state.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(state);
        } catch (Exception e) { throw new IllegalStateException("Cannot read source state", e); }
    } }
    public void put(String sourceKey, String key, String value) { synchronized (LOCK) {
        if (key == null || key.length() > 4096) throw new IllegalArgumentException("Invalid state key");
        Map<String, String> state = new LinkedHashMap<>(snapshot(sourceKey));
        if (value == null) state.remove(key); else state.put(key, value);
        byte[] bytes = new Gson().toJson(state).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Source state exceeds limit");
        File target = file(sourceKey), temp = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes); output.getFD().sync();
        } catch (IOException e) { throw new IllegalStateException("Cannot save source state", e); }
        if (!temp.renameTo(target)) throw new IllegalStateException("Cannot commit source state");
    } }
    private File file(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : digest) name.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return new File(directory, name + ".json");
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
