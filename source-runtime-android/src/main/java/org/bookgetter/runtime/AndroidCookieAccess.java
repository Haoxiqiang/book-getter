package org.bookgetter.runtime;

import android.webkit.CookieManager;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bookgetter.core.CookieAccess;

/** Source cookie host backed by the same browser cookie store as login and HTTP fetching. */
public final class AndroidCookieAccess implements CookieAccess {
    private final CookieManager cookies;
    public AndroidCookieAccess() { this(CookieManager.getInstance()); }
    AndroidCookieAccess(CookieManager cookies) { this.cookies = cookies; }

    @Override public String get(String url) {
        validate(url);
        String value = cookies.getCookie(url);
        return value == null ? "" : value;
    }

    @Override public void set(String url, String value) {
        validate(url);
        if (value == null || value.length() > 65_536 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid cookie value");
        for (String part : value.split(";")) {
            String pair = part.trim();
            if (pair.indexOf('=') > 0) cookies.setCookie(url, pair + "; Path=/");
        }
        cookies.flush();
    }

    @Override public void replace(String url, String value) { remove(url); set(url, value); }

    @Override public void remove(String url) {
        URI uri = validate(url);
        String current = get(url);
        Set<String> paths = new LinkedHashSet<>();
        paths.add("/");
        String path = uri.getPath();
        if (path != null && path.startsWith("/")) {
            paths.add(path);
            for (int i = 1; i < path.length(); i++) if (path.charAt(i) == '/') {
                paths.add(path.substring(0, i));
                paths.add(path.substring(0, i + 1));
            }
        }
        for (String part : current.split(";")) {
            int equal = part.indexOf('=');
            if (equal < 1) continue;
            String name = part.substring(0, equal).trim();
            for (String cookiePath : paths) {
                String expired = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=" + cookiePath;
                if ("https".equalsIgnoreCase(uri.getScheme())) expired += "; Secure";
                cookies.setCookie(url, expired);
                String domain = uri.getHost();
                while (domain != null && domain.contains(".")) {
                    cookies.setCookie(url, expired + "; Domain=" + domain);
                    domain = domain.substring(domain.indexOf('.') + 1);
                }
            }
        }
        cookies.flush();
    }

    private static URI validate(String url) {
        URI uri;
        try { uri = URI.create(url); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Cookie URL must be HTTP(S)"); }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Cookie URL must be HTTP(S)");
        return uri;
    }
}
