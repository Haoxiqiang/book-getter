package org.bookgetter.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MemorySourceStateStore implements SourceStateStore {
    private final Map<String, Map<String, String>> states = new LinkedHashMap<>();
    public synchronized String get(String sourceKey, String key) {
        return states.getOrDefault(sourceKey, Map.of()).getOrDefault(key, "");
    }
    public synchronized void put(String sourceKey, String key, String value) {
        Map<String, String> state = states.computeIfAbsent(sourceKey, ignored -> new LinkedHashMap<>());
        if (value == null) state.remove(key); else state.put(key, value);
    }
    public synchronized Map<String, String> snapshot(String sourceKey) {
        return Map.copyOf(states.getOrDefault(sourceKey, Map.of()));
    }
}
