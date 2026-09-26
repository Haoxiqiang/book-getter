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
        CookieManager.getInstance().setAcceptCookie(true);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        webView.loadUrl(getIntent().getStringExtra("url"));
    }
    @Override protected void onDestroy() { CookieManager.getInstance().flush(); webView.destroy(); super.onDestroy(); }
}
