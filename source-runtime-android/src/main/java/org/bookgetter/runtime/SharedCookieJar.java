package org.bookgetter.runtime;

import android.webkit.CookieManager;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/** Keep OkHttp's redirect requests and WebView login on the same cookie store. */
final class SharedCookieJar implements CookieJar {
    private final CookieManager manager = CookieManager.getInstance();

    @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        for (Cookie cookie : cookies) manager.setCookie(url.toString(), cookie.toString());
        manager.flush();
    }

    @Override public List<Cookie> loadForRequest(HttpUrl url) {
        String header = manager.getCookie(url.toString());
        if (header == null || header.isEmpty()) return List.of();
        List<Cookie> result = new ArrayList<>();
        for (String part : header.split(";\\s*")) {
            Cookie cookie = Cookie.parse(url, part.trim());
            if (cookie != null) result.add(cookie);
        }
        return result;
    }
}
