package org.bookgetter.core;

import java.util.*;
import org.bookgetter.core.Models.Source;

/** One thread's nested rule data. Shared by selectors and their nested JS host calls. */
final class RuleBindings {
    private static final class Frame {
        final String sourceKey;
        final String prefix;
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, String> metadata = new LinkedHashMap<>();
        Frame(String key, String prefix, Map<String, String> values) {
            this.sourceKey = key; this.prefix = prefix; this.values.putAll(values);
        }
    }
    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private RuleBindings() {}
    static String key(Source source) { return SourcePolicy.of(source).stateKey(); }
    static String bookPrefix(Source source, String url) {
        return ("rss".equals(SourcePolicy.of(source).model()) ? "article:" : "book:") + url + "\u0000";
    }
    static void enter(SourceStateStore states, Source source, String prefix, Map<String, String> inherited) {
        Map<String, String> values = new LinkedHashMap<>(inherited == null ? Map.of() : inherited);
        if (prefix != null) states.snapshot(key(source)).forEach((name, value) -> {
            if (name.startsWith(prefix)) values.put(name.substring(prefix.length()), value);
        });
        FRAMES.get().push(new Frame(key(source), prefix, values));
    }
    static void enterItem() { FRAMES.get().push(new Frame(null, null, Map.of())); }
    static void leave() {
        Deque<Frame> stack = FRAMES.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) FRAMES.remove();
    }
    static Map<String, String> item() { return FRAMES.get().isEmpty() ? Map.of() : Map.copyOf(FRAMES.get().peek().values); }
    static Map<String, String> snapshot(SourceStateStore states, Source source, String prefix) {
        for (Frame frame : FRAMES.get()) if (key(source).equals(frame.sourceKey) && Objects.equals(prefix, frame.prefix))
            return Map.copyOf(frame.values);
        Map<String, String> values = new LinkedHashMap<>();
        states.snapshot(key(source)).forEach((name, value) -> {
            if (name.startsWith(prefix)) values.put(name.substring(prefix.length()), value);
        });
        return values;
    }
    static Map<String, String> currentBook() {
        for (Frame frame : FRAMES.get()) if (frame.prefix != null
                && (frame.prefix.startsWith("book:") || frame.prefix.startsWith("article:"))) return Map.copyOf(frame.values);
        return Map.of();
    }
    static boolean hasFrame(Source source) {
        return FRAMES.get().stream().anyMatch(frame -> frame.sourceKey == null || key(source).equals(frame.sourceKey));
    }
    static void metadata(Source source, Map<String, String> values) {
        for (Frame frame : FRAMES.get()) if (key(source).equals(frame.sourceKey)) {
            frame.metadata.putAll(values); return;
        }
    }
    static String metadataValue(Source source, String name) {
        for (Frame frame : FRAMES.get()) if (frame.sourceKey == null || key(source).equals(frame.sourceKey)) {
            if (frame.metadata.containsKey(name)) return frame.metadata.get(name);
        }
        return null;
    }
    static String scoped(Source source, String name) {
        String metadata = metadataValue(source, name);
        if (metadata != null) return metadata;
        for (Frame frame : FRAMES.get()) if (frame.sourceKey == null || key(source).equals(frame.sourceKey)) {
            String value = frame.values.get(name);
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
    static String get(SourceStateStore states, Source source, String name) {
        String metadata = metadataValue(source, name);
        if (metadata != null) return metadata;
        String scoped = scoped(source, name);
        return scoped.isEmpty() ? states.get(key(source), "value:" + name) : scoped;
    }
    static void put(SourceStateStore states, Source source, String name, String value) {
        for (Frame frame : FRAMES.get()) if (frame.sourceKey == null || key(source).equals(frame.sourceKey)) {
            write(states, source, frame, name, value); return;
        }
        states.put(key(source), "value:" + name, value);
    }
    static void putBook(SourceStateStore states, Source source, String url, String name, String value) {
        String prefix = bookPrefix(source, url);
        for (Frame frame : FRAMES.get()) if (key(source).equals(frame.sourceKey) && prefix.equals(frame.prefix)) {
            write(states, source, frame, name, value); return;
        }
        states.put(key(source), prefix + name, value);
    }
    private static void write(SourceStateStore states, Source source, Frame frame, String name, String value) {
        if (frame.prefix != null) states.put(key(source), frame.prefix + name, value);
        if (value == null) frame.values.remove(name); else frame.values.put(name, value);
    }
    static Map<String, String> view(SourceStateStore states, Source source) {
        return new AbstractMap<>() {
            public String get(Object key) { return RuleBindings.get(states, source, String.valueOf(key)); }
            public String put(String key, String value) {
                String previous = get(key); RuleBindings.put(states, source, key, value); return previous;
            }
            public Set<Entry<String, String>> entrySet() { return item().entrySet(); }
        };
    }
}
