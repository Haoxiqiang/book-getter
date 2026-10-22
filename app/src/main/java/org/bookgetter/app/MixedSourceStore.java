package org.bookgetter.app;

import android.content.Context;
import android.content.SharedPreferences;

/** One durable commit for book/RSS state; model stores only mutate a transaction buffer. */
final class MixedSourceStore {
    record Snapshot(String books, String bookSubscriptions, String bookManual,
                    String rss, String rssSubscriptions, String rssManual) {}
    interface Storage { Snapshot read(); boolean write(Snapshot snapshot); }
    @FunctionalInterface interface Action<T> { T run(SourceStore books, RssStore rss) throws Exception; }
    private final Storage storage;
    MixedSourceStore(Context context) { this(preferences(context.getApplicationContext())); }
    MixedSourceStore(Storage storage) { this.storage = storage; }
    Snapshot snapshot() { synchronized (SourceStore.COMMIT_LOCK) { return storage.read(); } }
    <T> T transaction(Action<T> action) throws Exception { return transaction(null, action); }
    <T> T transaction(Snapshot expectedFeeds, Action<T> action) throws Exception {
        synchronized (SourceStore.COMMIT_LOCK) {
            Snapshot latest = storage.read();
            if (expectedFeeds != null && (!latest.bookSubscriptions().equals(expectedFeeds.bookSubscriptions())
                    || !latest.rssSubscriptions().equals(expectedFeeds.rssSubscriptions())))
                throw new IllegalStateException("Subscriptions changed during update; retry");
            Buffer buffer = new Buffer(latest);
            T result = action.run(new SourceStore(buffer.books()), new RssStore(buffer.rss()));
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Source import cancelled");
            if (!storage.write(buffer.value)) throw new IllegalStateException("Cannot save mixed source transaction");
            return result;
        }
    }
    SourceStore.Storage bookStorage() {
        return new SourceStore.Storage() {
            @Override public String read() { return snapshot().books(); }
            @Override public String readSubscriptions() { return snapshot().bookSubscriptions(); }
            @Override public String readManualSources() { return snapshot().bookManual(); }
            @Override public boolean write(String books, String feeds, String manual) {
                synchronized (SourceStore.COMMIT_LOCK) {
                    Snapshot old = storage.read();
                    return storage.write(new Snapshot(books, feeds, manual, old.rss(), old.rssSubscriptions(), old.rssManual()));
                }
            }
        };
    }
    RssStore.Storage rssStorage() {
        return new RssStore.Storage() {
            @Override public String read() { return snapshot().rss(); }
            @Override public String readSubscriptions() { return snapshot().rssSubscriptions(); }
            @Override public String readManual() { return snapshot().rssManual(); }
            @Override public boolean write(String rss, String manual, String feeds) {
                synchronized (SourceStore.COMMIT_LOCK) {
                    Snapshot old = storage.read();
                    return storage.write(new Snapshot(old.books(), old.bookSubscriptions(), old.bookManual(), rss, feeds, manual));
                }
            }
        };
    }
    private static Storage preferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("sources", 0);
        return new Storage() {
            @Override public Snapshot read() {
                return new Snapshot(preferences.getString("json", "[]"), preferences.getString("subscriptions", "[]"),
                        preferences.getString("manualJson", null), preferences.getString("rssJson", "[]"),
                        preferences.getString("rssSubscriptions", "[]"), preferences.getString("rssManualJson", null));
            }
            @Override public boolean write(Snapshot value) {
                return preferences.edit().putString("json", value.books()).putString("subscriptions", value.bookSubscriptions())
                        .putString("manualJson", value.bookManual()).putString("rssJson", value.rss())
                        .putString("rssSubscriptions", value.rssSubscriptions()).putString("rssManualJson", value.rssManual()).commit();
            }
        };
    }
    private static final class Buffer {
        Snapshot value;
        Buffer(Snapshot value) { this.value = value; }
        SourceStore.Storage books() {
            return new SourceStore.Storage() {
                @Override public String read() { return value.books(); }
                @Override public String readSubscriptions() { return value.bookSubscriptions(); }
                @Override public String readManualSources() { return value.bookManual(); }
                @Override public boolean write(String books, String feeds, String manual) {
                    value = new Snapshot(books, feeds, manual, value.rss(), value.rssSubscriptions(), value.rssManual()); return true;
                }
            };
        }
        RssStore.Storage rss() {
            return new RssStore.Storage() {
                @Override public String read() { return value.rss(); }
                @Override public String readSubscriptions() { return value.rssSubscriptions(); }
                @Override public String readManual() { return value.rssManual(); }
                @Override public boolean write(String rss, String manual, String feeds) {
                    value = new Snapshot(value.books(), value.bookSubscriptions(), value.bookManual(), rss, feeds, manual); return true;
                }
            };
        }
    }
}
