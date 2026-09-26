package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import org.bookgetter.core.Models.FetchResponse;

/** Conservative response hints for probes; never a substitute for a source rule. */
public final class ResponseSignals {
    private ResponseSignals() {}

    public static String quality(FetchResponse response) {
        if (response.status() >= 300 && response.status() < 400) return "UNRESOLVED_REDIRECT";
        if (response.status() == 401 || response.status() == 403) return "ACCESS_DENIED";
        if (response.status() >= 400) return "HTTP_ERROR";
        String body = response.body();
        if (body == null || body.isBlank()) return "EMPTY_RESPONSE";
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("domain may be for sale")) return "PARKED_DOMAIN";
        if (lower.contains("<title>just a moment...") || lower.contains("cloudflare challenge")
                || lower.contains("cf-chl-")) return "CHALLENGE_PAGE";
        if (body.stripLeading().startsWith("{")) {
            try {
                JsonElement parsed = JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    JsonObject object = parsed.getAsJsonObject();
                    if (object.has("errno") && object.get("errno").isJsonPrimitive()
                            && object.get("errno").getAsJsonPrimitive().isNumber()
                            && object.get("errno").getAsInt() != 0
                            && (object.has("errmsg") || object.has("message")))
                        return "BUSINESS_ERROR";
                    if (object.has("code") && object.get("code").isJsonPrimitive()
                            && object.get("code").getAsJsonPrimitive().isNumber()
                            && object.get("code").getAsInt() != 0 && object.get("code").getAsInt() != 200
                            && object.has("data") && object.get("data").isJsonNull()
                            && (object.has("message") || object.has("msg")))
                        return "BUSINESS_ERROR";
                }
            } catch (Exception ignored) { /* Not a complete JSON response. */ }
        }
        return "UNCLASSIFIED";
    }
}
