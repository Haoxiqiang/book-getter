package org.bookgetter.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Manual login shares the same Android CookieManager as AndroidTransport. */
public final class LoginActivity extends Activity {
    private WebView webView;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        if (!org.bookgetter.core.ResourceUrls.isHttp(url)) { finish(); return; }
        CookieManager.getInstance().setAcceptCookie(true);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        webView.loadUrl(url);
    }
    @Override protected void onDestroy() { CookieManager.getInstance().flush(); if (webView != null) webView.destroy(); super.onDestroy(); }
}
