package org.bookgetter.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;

/** Conservative response hints for probes; never a substitute for a source rule. */
public final class ResponseSignals {
    private ResponseSignals() {}

    public static int visibleCharacters(String htmlOrText) {
        return htmlOrText == null ? 0 : org.jsoup.Jsoup.parseBodyFragment(htmlOrText).text().length();
    }

    /** Diagnostic hints only: a host migration or non-200 success does not prove a bad rule. */
    public static List<String> probeSignals(FetchRequest request, FetchResponse response) {
        List<String> signals = new ArrayList<>();
        signals.add(quality(response));
        if (response.status() >= 200 && response.status() < 300 && response.status() != 200)
            signals.add("NON_200_SUCCESS");
        try {
            URI requested = URI.create(request.url());
            URI returned = URI.create(response.url());
            String before = requested.getHost();
            String after = returned.getHost();
            if (before != null && after != null && !before.equalsIgnoreCase(after))
                signals.add("CROSS_HOST_REDIRECT");
            String requestedPath = requested.getPath();
            String returnedPath = returned.getPath();
            if (requestedPath != null && !requestedPath.isBlank() && !requestedPath.equals("/")
                    && (returnedPath == null || returnedPath.isBlank() || returnedPath.equals("/")))
                signals.add("SEARCH_ENDPOINT_TO_ROOT");
        } catch (IllegalArgumentException ignored) { /* Invalid URLs are diagnosed by the transport. */ }
        return signals;
    }

    public static boolean suspectEmptySearch(String signal) {
        return signal.equals("BUSINESS_ERROR") || signal.equals("CHALLENGE_PAGE")
                || signal.equals("PARKED_DOMAIN") || signal.equals("APP_UPGRADE_REQUIRED")
                || signal.equals("PAGE_NOT_FOUND")
                || signal.equals("UNRESOLVED_REDIRECT") || signal.equals("EMPTY_RESPONSE")
                || signal.equals("CROSS_HOST_REDIRECT") || signal.equals("SEARCH_ENDPOINT_TO_ROOT")
                || signal.equals("NON_200_SUCCESS");
    }

    /** Catalog-only diagnostic: a JavaScript link is not a directly fetchable chapter URL. */
    public static boolean javascriptPlaceholderUrl(String url) {
        return url != null && url.stripLeading().toLowerCase(Locale.ROOT).startsWith("javascript:");
    }

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
        if (lower.contains("<title>提示信息</title>") && body.contains("所访问的页面不存在"))
            return "PAGE_NOT_FOUND";
        if (body.contains("当前版本过低") && body.contains("请升级到最新版")) return "APP_UPGRADE_REQUIRED";
        if (body.stripLeading().startsWith("{")) {
            try {
                JsonElement parsed = JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    JsonObject object = parsed.getAsJsonObject();
                    if (object.has("chapter") && object.get("chapter").isJsonObject()) {
                        JsonObject chapter = object.getAsJsonObject("chapter");
                        if (chapter.has("body") && chapter.get("body").isJsonPrimitive()
                                && upgradeNotice(chapter.get("body").getAsString()))
                            return "APP_UPGRADE_REQUIRED";
                    }
                    if (object.has("errno") && object.get("errno").isJsonPrimitive()
                            && object.get("errno").getAsJsonPrimitive().isNumber()
                            && object.get("errno").getAsInt() != 0
                            && (object.has("errmsg") || object.has("message")))
                        return "BUSINESS_ERROR";
                    if (object.has("code") && object.get("code").isJsonPrimitive()
                            && object.get("code").getAsJsonPrimitive().isNumber()
                            && object.get("code").getAsInt() != 0 && object.get("code").getAsInt() != 200
                            && (object.get("code").getAsInt() < 0
                                    || (object.has("data") && (object.get("data").isJsonNull()
                                            || (object.get("data").isJsonArray()
                                                    && object.getAsJsonArray("data").isEmpty()))))
                            && (object.has("message") || object.has("msg")))
                        return "BUSINESS_ERROR";
                }
            } catch (Exception ignored) { /* Not a complete JSON response. */ }
        }
        return "UNCLASSIFIED";
    }

    private static boolean upgradeNotice(String value) {
        return value.contains("当前版本过低") && value.contains("请升级到最新版");
    }
}
