package org.bookgetter.core;

import java.util.Map;

/** Private persistent state. Values are not included in source JSON or diagnostics. */
public interface SourceStateStore {
    String get(String sourceKey, String key);
    void put(String sourceKey, String key, String value);
    Map<String, String> snapshot(String sourceKey);
}
