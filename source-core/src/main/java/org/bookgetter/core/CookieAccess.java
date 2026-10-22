package org.bookgetter.core;

/** Cookie storage supplied by the platform; never exposed as a Java object to scripts. */
public interface CookieAccess {
    String get(String url);
    void set(String url, String value);
    void replace(String url, String value);
    void remove(String url);

    CookieAccess NONE = new CookieAccess() {
        public String get(String url) { return ""; }
        public void set(String url, String value) { throw new UnsupportedOperationException("Cookies unavailable"); }
        public void replace(String url, String value) { throw new UnsupportedOperationException("Cookies unavailable"); }
        public void remove(String url) { throw new UnsupportedOperationException("Cookies unavailable"); }
    };
}
