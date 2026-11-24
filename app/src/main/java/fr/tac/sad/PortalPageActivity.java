package fr.tac.sad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Page du portail famille affichée telle quelle (ex. les menus), avec la session de l'appli. */
public class PortalPageActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Perso.refresh(this);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (url == null || !allowed(url)) {
            finish();
            return;
        }
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackgroundColor(Color.parseColor("#0A6E4F"));
        Button back = new Button(this);
        back.setText("‹ Retour");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView t = new TextView(this);
        t.setText(title == null ? "Portail famille" : title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setPadding(pad, 0, 0, 0);
        bar.addView(t);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (allowed(u.toString()) && !u.getPath().toLowerCase().endsWith(".pdf")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));   // PDF, autres sites : navigateur du téléphone
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        web.loadUrl(url);
    }

    /** Uniquement le site du portail famille réglé dans l'appli. */
    static boolean allowed(String url) {
        try {
            String p = LoginActivity.PORTAL;
            if (p == null || p.isEmpty()) return false;
            return Uri.parse(url).getHost() != null && Uri.parse(url).getHost().equalsIgnoreCase(Uri.parse(p).getHost())
                    && url.startsWith("https://");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
