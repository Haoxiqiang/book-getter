package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;

/** Model scalars must not use Gson's singleton-array coercion or lossy integer conversion. */
final class SourceFields {
    private SourceFields() {}
    static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Invalid scalar source field");
        return value.getAsString();
    }
    static int integer(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Invalid source type");
        try { return new BigDecimal(value.getAsString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException("Invalid source type"); }
    }
}
