package fr.tac.sad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String API_PREFIX = "https://ardenne-metropole-tad.nuamouv.com/api/";
    private WebView web;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(getApplicationContext());
        Perso.refresh(this);
        prefs = getSharedPreferences("tac_sad", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        // Affiche les boîtes de dialogue JavaScript (confirm / prompt)
        // confirmations et saisies : fenêtre sans le titre « La page file:// indique »
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setMessage(msg).setPositiveButton("OK", (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.confirm()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, android.webkit.JsResult r) {
                new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setMessage(msg).setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Annuler", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def, android.webkit.JsPromptResult r) {
                final android.widget.EditText in = new android.widget.EditText(MainActivity.this);
                in.setText(def == null ? "" : def);
                in.setSelection(in.getText().length());
                android.widget.FrameLayout f = new android.widget.FrameLayout(MainActivity.this);
                int p = (int) (20 * getResources().getDisplayMetrics().density);
                f.setPadding(p, 0, p, 0);
                f.addView(in);
                new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setMessage(msg).setView(f)
                        .setPositiveButton("OK", (d, w) -> r.confirm(in.getText().toString()))
                        .setNegativeButton("Annuler", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });
        // Les liens (ex. règlement PDF) s'ouvrent dans le navigateur du téléphone
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else {
            String page = getIntent() != null ? getIntent().getStringExtra("page") : null;
            web.loadUrl("file:///android_asset/index.html" + ("covoit".equals(page) ? "#covoit" : "cantine".equals(page) ? "#cantine" : ""));
            if (Vault.has(this)) CantineWatch.schedule(this);
            if (Vault.getSecret(this, "carpool") != null) Carpool.schedule(this);  // vérifications du covoiturage
            Updater.check(this, true); // recherche discrète d'une nouvelle version
            CrashLog.showIfAny(this);  // plantage précédent : on l'affiche pour pouvoir l'envoyer
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    private static final int REQ_LOGIN = 42;

    /** Appli au premier plan (pour afficher une fenêtre plutôt qu'une notification). */
    static volatile MainActivity foreground;

    @Override
    protected void onResume() {
        super.onResume();
        foreground = this;
        if (web != null) web.evaluateJavascript("window.checkNews && window.checkNews()", null);
        // une demande de bus attend sa réponse : coup d'œil à la boîte mail
        if (BusMail.pending(this).length() > 0 && Vault.getSecret(this, BusMail.SECRET) != null) {
            final android.content.Context c = getApplicationContext();
            new Thread(() -> BusMail.checkAndTell(c)).start();
        }
    }

    @Override
    protected void onPause() {
        foreground = null;
        super.onPause();
    }

    void showCantineNews(java.util.List<String> news) {
        final String js = "window.showNews && window.showNews(" + new org.json.JSONArray(news).toString() + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String pg = intent.getStringExtra("page");
        if ("covoit".equals(pg) || "cantine".equals(pg)) web.evaluateJavascript("location.hash='" + pg + "'", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN) web.evaluateJavascript("window.onPortalLogin && window.onPortalLogin()", null);
        if ((requestCode == REQ_PHOTO_CAM || requestCode == REQ_PHOTO_GAL) && resultCode == RESULT_OK && data != null) {
            final Intent d = data;
            final boolean cam = requestCode == REQ_PHOTO_CAM;
            new Thread(() -> {
                String url = "";
                try {
                    android.graphics.Bitmap bm = cam ? (android.graphics.Bitmap) d.getExtras().get("data") : loadImage(d.getData());
                    if (bm != null) url = "data:image/jpeg;base64," + avatar(bm);
                } catch (Throwable t) {
                    CrashLog.save(getApplicationContext(), t);
                }
                final String js = "window.__photoDone && window.__photoDone(" + JSONObject.quote(url) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }

    private static final int REQ_PHOTO_CAM = 51, REQ_PHOTO_GAL = 52;

    /** Image de la galerie, réduite et remise à l'endroit (orientation EXIF). */
    private android.graphics.Bitmap loadImage(android.net.Uri uri) throws Exception {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        int s = 1;
        while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= 400) s *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = s;
        android.graphics.Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bm = android.graphics.BitmapFactory.decodeStream(in, null, o);
        }
        int rot = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int or = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
        } catch (Exception ignored) {
        }
        if (bm != null && rot != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rot);
            bm = android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }
        return bm;
    }

    /** Carré centré de 192 px, JPEG, en base64 (quelques Ko). */
    private static String avatar(android.graphics.Bitmap bm) {
        int side = Math.min(bm.getWidth(), bm.getHeight());
        android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(bm, (bm.getWidth() - side) / 2, (bm.getHeight() - side) / 2, side, side);
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(sq, 192, 192, true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    @Override
    public void onBackPressed() {
        // d'abord fermer une sous-page ouverte (réglages du covoiturage, du planning…)
        web.evaluateJavascript("window.closeTopSheet ? window.closeTopSheet() : false", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) web.goBack();
            else MainActivity.super.onBackPressed();
        });
    }

    /** Pont appelé depuis la page : requêtes réseau (sans restriction CORS) et stockage local. */
    private class Bridge {

        @JavascriptInterface
        public String getPref(String key) {
            return prefs.getString(key, null);
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            prefs.edit().putString(key, value).apply();
        }

        /** Lien avec Planning Commandes : réglages (sans secrets), enregistrement, lecture du planning partagé. */
        /** Couleur de la barre d'état du téléphone, assortie au thème choisi. */
        @JavascriptInterface
        public void setBarColor(final String hex) {
            runOnUiThread(() -> {
                try {
                    getWindow().setStatusBarColor(android.graphics.Color.parseColor(hex));
                } catch (Exception ignored) {
                }
            });
        }

        // ---------------- Réponses de la compagnie de bus par mail
        @JavascriptInterface
        public void busSent(String day, String aller) {
            BusMail.sent(MainActivity.this, day, aller);
            if (Vault.getSecret(MainActivity.this, BusMail.SECRET) != null) BusMailWatch.schedule(MainActivity.this);
        }

        /** Demandes de bus sans réponse : [{day, aller, at}]. */
        @JavascriptInterface
        public String busPending() {
            return BusMail.pending(MainActivity.this).toString();
        }

        /** Validation (ok) ou refus (ko) à la main. */
        @JavascriptInterface
        public boolean busResolve(String at, String st) {
            try {
                return BusMail.resolve(MainActivity.this, Long.parseLong(at), "ko".equals(st) ? "ko" : "ok");
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void busForce(String day, String aller, String st) {
            BusMail.force(MainActivity.this, day, aller, st);
        }

        @JavascriptInterface
        public String mailSettings() {
            return BusMail.settings(MainActivity.this);
        }

        @JavascriptInterface
        public String mailServerFor(String address) {
            return BusMail.serverFor(address);
        }

        @JavascriptInterface
        public void mailSave(String json) {
            BusMail.save(MainActivity.this, json);
            if (BusMail.pending(MainActivity.this).length() > 0) BusMailWatch.schedule(MainActivity.this);
        }

        /** Relève tout de suite ; {ok, news[], erreur}. */
        @JavascriptInterface
        public void mailCheck(final String id) {
            new Thread(() -> {
                JSONObject r = new JSONObject();
                try {
                    r.put("ok", true).put("news", new org.json.JSONArray(BusMail.check(MainActivity.this)));
                } catch (Throwable t) {
                    try {
                        String m = t.getMessage();
                        if (t instanceof java.net.UnknownHostException) m = "serveur de messagerie introuvable";
                        r.put("ok", false).put("erreur", m == null ? t.getClass().getSimpleName() : m);
                    } catch (Exception ignored) {
                    }
                }
                answer("__mailDone", id, r.toString());
            }).start();
        }

        /** Réglages personnels modifiés : adresse du portail, widget. */
        @JavascriptInterface
        public void persoChanged() {
            Perso.refresh(MainActivity.this);
            CalendarWidget.refreshAll(getApplicationContext());
        }

        /** Nombre de widgets posés sur l'écran d'accueil. */
        @JavascriptInterface
        public int widgetCount() {
            return CalendarWidget.widgetIds(MainActivity.this).length;
        }

        /** Ouvre les réglages du widget (le n-ième s'il y en a plusieurs). */
        @JavascriptInterface
        public void configureWidget(int index) {
            int[] ids = CalendarWidget.widgetIds(MainActivity.this);
            if (ids.length == 0) return;
            int id = ids[Math.max(0, Math.min(ids.length - 1, index))];
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, WidgetConfigActivity.class)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)));
        }

        /** L'utilisateur fait confiance au certificat de son NAS (empreinte SHA-256). */
        @JavascriptInterface
        public void trustCertificate(String server, String fingerprint) {
            Tls.trust(MainActivity.this, server, fingerprint);
        }

        // ---------------- Covoiturage
        @JavascriptInterface
        public String carpoolSettings() {
            return Carpool.settings(MainActivity.this);
        }

        @JavascriptInterface
        public void carpoolSave(String json) {
            Carpool.save(MainActivity.this, json);
        }

        /** Code du covoiturage de CE téléphone (pour le comparer avec celui des autres). */
        @JavascriptInterface
        public String carpoolShowCode() {
            try {
                org.json.JSONObject o = Carpool.secret(MainActivity.this);
                return o == null ? "" : o.optString("code");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String carpoolNewCode() {
            return Carpool.newCode();
        }

        @JavascriptInterface
        public void carpoolSeen(String at) {
            try {
                Carpool.markSeen(MainActivity.this, Long.parseLong(at));
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void carpoolFetch(final String id, final String unused) {
            new Thread(() -> answer("__carpoolDone", id, Carpool.fetch(MainActivity.this))).start();
        }

        @JavascriptInterface
        public void carpoolPut(final String id, final String arg) {
            new Thread(() -> answer("__carpoolDone", id, Carpool.put(MainActivity.this, arg))).start();
        }

        /** Autorisation d'afficher les notifications (Android 13 et plus). */
        @JavascriptInterface
        public void askNotifications() {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5));
            }
        }

        @JavascriptInterface
        public boolean hasPlanningLink() {
            return BuildConfig.PLANNING;
        }

        @JavascriptInterface
        public String planningSettings() {
            return PlanningLink.settings(MainActivity.this);
        }

        /** Photo de profil : « camera » (appareil photo) ou « gallery » (images du téléphone). */
        @JavascriptInterface
        public void pickPhoto(final String source) {
            runOnUiThread(() -> {
                try {
                    if ("camera".equals(source)) {
                        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra("android.intent.extras.CAMERA_FACING", 1);          // caméra avant si possible
                        i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
                        i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
                        startActivityForResult(i, REQ_PHOTO_CAM);
                    } else {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        startActivityForResult(Intent.createChooser(i, "Choisir une photo"), REQ_PHOTO_GAL);
                    }
                } catch (Exception e) {
                    android.widget.Toast.makeText(MainActivity.this, "Impossible d'ouvrir l'appareil photo ou la galerie", android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Copie un texte dans le presse-papiers du téléphone. */
        @JavascriptInterface
        public void copyText(String text) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Code", text));
        }

        /** Code de partage du planning enregistré sur ce téléphone. */
        @JavascriptInterface
        public String planningShowCode() {
            try {
                String s = Vault.getSecret(MainActivity.this, "planning_link");
                return s == null ? "" : new JSONObject(s).optString("code");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void planningSave(String json) {
            PlanningLink.save(MainActivity.this, json);
        }

        @JavascriptInterface
        public void planningFetch(final String id) {
            if (!BuildConfig.PLANNING) return;
            new Thread(() -> {
                String res = PlanningLink.fetch(MainActivity.this);
                final String js = "window.__planningDone(" + JSONObject.quote(id) + "," + JSONObject.quote(res) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /** Identifiant du portail retenu (jamais le mot de passe). */
        @JavascriptInterface
        public String savedLoginName() {
            String[] l = Vault.load(MainActivity.this);
            return l == null ? "" : l[0];
        }

        /** Enregistre l'identifiant et le mot de passe du portail (mot de passe vide = garder l'ancien). */
        @JavascriptInterface
        public boolean saveLogin(String login, String password) {
            String[] old = Vault.load(MainActivity.this);
            String p = (password == null || password.isEmpty()) && old != null ? old[1] : password;
            if (login == null || login.trim().isEmpty() || p == null || p.isEmpty()) return false;
            boolean ok = Vault.save(MainActivity.this, login.trim(), p);
            if (ok) CantineWatch.schedule(MainActivity.this);
            return ok;
        }

        /** La page signale les créneaux de cantine lus : renvoie les réponses (acceptée / refusée) détectées. */
        @JavascriptInterface
        public String cantineObserve(String json) {
            java.util.List<JSONObject> evs = new java.util.ArrayList<>();
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                for (int i = 0; i < a.length(); i++) evs.add(a.getJSONObject(i));
            } catch (Exception ignored) {
            }
            return new org.json.JSONArray(CantineWatch.observe(MainActivity.this, evs)).toString();
        }

        /** Réponses trouvées en arrière-plan pendant que l'appli était fermée. */
        @JavascriptInterface
        public String cantineNews() {
            return CantineWatch.takeNews(MainActivity.this);
        }

        @JavascriptInterface
        public boolean hasSavedLogin() {
            return Vault.has(MainActivity.this);
        }

        @JavascriptInterface
        public void forgetLogin() {
            Vault.clear(MainActivity.this);
        }

        @JavascriptInterface
        public int appVersion() {
            return Updater.currentVersion(MainActivity.this);
        }

        @JavascriptInterface
        public String appVersionName() {
            return Updater.versionName(MainActivity.this);
        }

        @JavascriptInterface
        public void showWhatsNew() {
            runOnUiThread(() -> Updater.showNotes(MainActivity.this));
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> Updater.check(MainActivity.this, false));
        }

        /** Met à jour le widget d'écran d'accueil. */
        @JavascriptInterface
        public void updateWidget() {
            CalendarWidget.refreshAll(getApplicationContext());
        }

        /** Ouvre l'écran de connexion au portail famille (cantine). */
        @JavascriptInterface
        public void portalLogin() {
            runOnUiThread(() -> startActivityForResult(new Intent(MainActivity.this, LoginActivity.class), REQ_LOGIN));
        }

        /** Requête vers le portail famille avec les cookies de connexion de l'appli. */
        @JavascriptInterface
        public void portal(final String id, final String method, final String path, final String body,
                           final String contentType, final String csrf) {
            new Thread(() -> {
                String[] r = Portal.request(method, path, body, contentType, csrf);
                final String js = "window.__portalDone(" + JSONObject.quote(id) + "," + r[0] + ","
                        + JSONObject.quote(r[1]) + "," + JSONObject.quote(r[2]) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void request(final String id, final String method, final String url, final String body) {
            new Thread(() -> {
                int status = 0;
                String text = "";
                // Sécurité : uniquement l'API du service TAC
                if (url != null && url.startsWith(API_PREFIX)) {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestMethod(method);
                        c.setRequestProperty("Accept", "application/json");
                        if (body != null && !body.isEmpty()) {
                            c.setDoOutput(true);
                            c.setRequestProperty("Content-Type", "application/json");
                            try (OutputStream os = c.getOutputStream()) {
                                os.write(body.getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        status = c.getResponseCode();
                        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                        text = readAll(is);
                    } catch (Exception e) {
                        status = 0;
                        text = "";
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
                final String js = "window.__httpDone(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")";
                web.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }

    private void answer(String fn, String id, String res) {
        final String js = "window." + fn + "(" + JSONObject.quote(id) + "," + JSONObject.quote(res) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
