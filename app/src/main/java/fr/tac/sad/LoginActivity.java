package fr.tac.sad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Connexion au portail famille.
 * - Identifiants retenus : connexion automatique, sans afficher le site.
 * - Sinon (ou si un code de vérification est demandé) : la page officielle s'affiche,
 *   puis l'appli propose de retenir les identifiants saisis.
 */
public class LoginActivity extends Activity {

    /** Adresse du portail famille, saisie dans ⚙️ Réglages (vide tant qu'elle n'est pas réglée). */
    static volatile String PORTAL = "";

    private WebView web;
    private TextView checking;
    private boolean finished = false;
    private boolean autoTried = false;
    private String[] saved;              // identifiants retenus (déchiffrés), ou null
    private volatile String capLogin, capPassword;  // identifiants tapés par l'utilisateur
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Repère les identifiants tapés (au moment de l'envoi du formulaire) pour proposer de les retenir
    private static final String CAPTURE_JS =
            "(function(){if(window.__capHooked)return;window.__capHooked=1;" +
            "function grab(){var a=document.querySelector('input[name=j_username]'),b=document.querySelector('input[name=j_password]');" +
            "if(a&&b&&a.value&&b.value)LoginBridge.captured(a.value,b.value);}" +
            "document.addEventListener('submit',grab,true);" +
            "document.addEventListener('click',function(e){var t=e.target&&e.target.closest&&e.target.closest('button,input[type=submit]');if(t)grab();},true);" +
            "document.addEventListener('keydown',function(e){if(e.key==='Enter')grab();},true);})();";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Perso.refresh(this);
        if (PORTAL.isEmpty()) {
            android.widget.Toast.makeText(this, "Indique d'abord l'adresse de ton portail famille dans ⚙️ Réglages", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        saved = Vault.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#0A6E4F"));
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad / 2, pad / 2, pad / 2);

        Button back = new Button(this);
        back.setText("‹ Retour");
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("Connexion au portail famille");
        title.setPadding(pad / 2, 0, 0, 0);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button done = new Button(this);
        done.setText("Terminé");
        done.setOnClickListener(v -> checkLoggedIn());
        bar.addView(done);

        checking = new TextView(this);
        checking.setText(saved != null ? "Connexion automatique…" : "Chargement…");
        checking.setTextSize(16);
        checking.setGravity(Gravity.CENTER);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSaveFormData(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new LoginBridge(), "LoginBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (url != null && url.startsWith(PORTAL)) {
                    view.evaluateJavascript(CAPTURE_JS, null);
                    // le formulaire peut être sur n'importe quelle page (ex. l'accueil du portail) : on le cherche
                    if (saved != null) autoFill(view, !autoTried);
                }
                if (url == null || isLoginPage(url)) {
                    if (!autoPending()) showWeb(true);
                    return;
                }
                // On a quitté les pages de connexion : on masque le site et on vérifie l'accès
                checking.setText("Connexion réussie, retour à l'appli…");
                showWeb(false);
                checkLoggedIn();
            }
        });

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(checking, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.setVisibility(View.GONE);
        setContentView(root);

        web.loadUrl(PORTAL + "activites/planning");
    }

    /**
     * Remplit les champs identifiant / mot de passe chaque fois qu'ils apparaissent vides (le portail peut
     * redessiner son formulaire), et l'envoie automatiquement une seule fois (submit = true).
     */
    private void autoFill(WebView view, boolean submit) {
        String js = autoFillJs(saved[0], saved[1], submit);
        view.evaluateJavascript(js, null);
    }

    /** Script qui remplit (et envoie au besoin) le formulaire de connexion du portail, où qu'il soit sur la page. */
    static String autoFillJs(String login, String password, boolean submit) {
        String js = "(function(u,p,submit){" +
                "function set(el,v){el.focus();el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));el.blur();}" +
                "function fill(){var a=document.querySelector('input[name=j_username],input[id^=username]'),b=document.querySelector('input[name=j_password],input[type=password]');" +
                "if(!a||!b)return false;if(!a.value)set(a,u);if(!b.value)set(b,p);" +
                "if(!window.__formSeen){window.__formSeen=1;LoginBridge.formSeen(!!submit);}" +
                "if(submit&&!window.__autoSent){window.__autoSent=1;var f=a.form;" +
                "var btn=(f&&f.querySelector('button[type=submit],input[type=submit]'))||document.querySelector('button[type=submit]');" +
                "setTimeout(function(){if(btn)btn.click();else if(f&&f.requestSubmit)f.requestSubmit();else if(f)f.submit();},300);}" +
                "return true;}" +
                "fill();" +
                "if(!window.__fillObs){window.__fillObs=new MutationObserver(function(){fill();});" +
                "window.__fillObs.observe(document.documentElement,{childList:true,subtree:true});}" +
                "})(" + JSONObject.quote(login) + "," + JSONObject.quote(password) + "," + submit + ");";
        return js;
    }

    private long autoAt = 0;

    private boolean autoPending() {
        return System.currentTimeMillis() - autoAt < 9000;
    }

    /** Formulaire trouvé et rempli : envoi automatique (1re fois, page masquée) ou simple pré-remplissage. */
    private void onFormSeen(boolean submitted) {
        if (finished) return;
        if (submitted) {
            autoTried = true;
            autoAt = System.currentTimeMillis();
            checking.setText("Connexion automatique…");
            showWeb(false);
            // Si rien ne se passe (code de vérification demandé, mot de passe changé…), on montre la page
            ui.postDelayed(() -> {
                if (!finished && !web.isShown()) showWeb(true);
            }, 9000);
        } else {
            showWeb(true);
        }
    }

    /** Pages où l'utilisateur doit agir (identifiants, code de vérification, consentement…). */
    private static boolean isLoginPage(String url) {
        if (!url.startsWith(PORTAL)) return true; // ex. FranceConnect
        String rest = url.substring(PORTAL.length()).toLowerCase();
        return rest.isEmpty() || rest.startsWith("?") || rest.startsWith("#")
                || rest.contains("login") || rest.contains("connexion") || rest.contains("consentement")
                || rest.contains("compteutilisateur") || rest.contains("logout") || rest.contains("error");
    }

    private void showWeb(boolean visible) {
        web.setVisibility(visible ? View.VISIBLE : View.GONE);
        checking.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    /** Vérifie en arrière-plan que la page Planning est accessible avec les cookies actuels. */
    private void checkLoggedIn() {
        CookieManager.getInstance().flush();
        new Thread(() -> {
            boolean ok = false;
            try {
                String u = PORTAL + "activites/planning";
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                String ck = CookieManager.getInstance().getCookie(u);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                if (c.getResponseCode() == 200) {
                    java.io.InputStream in = c.getInputStream();
                    byte[] buf = new byte[8192];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    while ((n = in.read(buf)) > 0 && sb.length() < 2000000) sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    in.close();
                    ok = sb.indexOf("getEventsList") >= 0;
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            final boolean logged = ok;
            runOnUiThread(() -> {
                if (logged) onLoggedIn();
                else if (!finished && !autoPending()) showWeb(true); // pas encore connecté : on réaffiche la page
            });
        }).start();
    }

    /** Connecté : propose de retenir les identifiants s'ils viennent d'être tapés. */
    private void onLoggedIn() {
        if (finished) return;
        final String l = capLogin, p = capPassword;
        boolean isNew = l != null && p != null && (saved == null || !l.equals(saved[0]) || !p.equals(saved[1]));
        if (!isNew) {
            close();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Retenir mes identifiants ?")
                .setMessage("L'appli pourra se reconnecter toute seule au portail famille.\n\n"
                        + "Ils sont chiffrés et gardés uniquement sur ce téléphone.")
                .setPositiveButton("Oui, retenir", (d, w) -> {
                    Vault.save(this, l, p);
                    CantineWatch.schedule(this);
                    close();
                })
                .setNegativeButton("Non", (d, w) -> close())
                .setCancelable(false)
                .show();
    }

    private void close() {
        if (finished) return;
        finished = true;
        CookieManager.getInstance().flush();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else close();
    }

    private class LoginBridge {
        @JavascriptInterface
        public void formSeen(boolean submitted) {
            runOnUiThread(() -> onFormSeen(submitted));
        }

        @JavascriptInterface
        public void captured(String login, String password) {
            capLogin = login;
            capPassword = password;
        }
    }
}
