package org.bookgetter.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import org.bookgetter.business.MixedSourceStore;

/** Android persistence adapter. Retains the existing app preference file and keys. */
public final class AndroidSourceStorage {
    private AndroidSourceStorage() {}

    public static MixedSourceStore.Storage mixedStorage(Context context) {
        SharedPreferences preferences = context.getApplicationContext().getSharedPreferences("sources", 0);
        return new MixedSourceStore.Storage() {
            @Override public MixedSourceStore.Snapshot read() {
                return new MixedSourceStore.Snapshot(preferences.getString("json", "[]"), preferences.getString("subscriptions", "[]"),
                        preferences.getString("manualJson", null), preferences.getString("rssJson", "[]"),
                        preferences.getString("rssSubscriptions", "[]"), preferences.getString("rssManualJson", null));
            }
            @Override public boolean write(MixedSourceStore.Snapshot value) {
                return preferences.edit().putString("json", value.books()).putString("subscriptions", value.bookSubscriptions())
                        .putString("manualJson", value.bookManual()).putString("rssJson", value.rss())
                        .putString("rssSubscriptions", value.rssSubscriptions()).putString("rssManualJson", value.rssManual()).commit();
            }
        };
    }
}
