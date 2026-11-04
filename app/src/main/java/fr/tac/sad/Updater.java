package fr.tac.sad;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mise à jour automatique depuis la « Release » GitHub du projet. */
public class Updater {

    private static final String API = "https://api.github.com/repos/apocalipse2701/reservation_tac/releases/tags/apk";

    /** Numéro lisible (version.txt), ex. « 2.0.0 ». */
    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Markdown simple → texte lisible dans une boîte de dialogue. */
    static String plain(String md) {
        return md.replaceAll("\\*\\*", "").replaceAll("(?m)^#+\\s*", "").replaceAll("(?m)^- ", "• ").replace("`", "").trim();
    }

    /** Notes de la version installée (page v<version> sur GitHub). */
    static void showNotes(final Activity a) {
        final String v = versionName(a);
        new Thread(() -> {
            String body = "";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/apocalipse2701/reservation_tac/releases/tags/v" + v).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "GestionEnfants");
                if (c.getResponseCode() == 200) body = new JSONObject(read(c.getInputStream())).optString("body", "").split("\n---")[0];
                c.disconnect();
            } catch (Exception ignored) {
            }
            final String txt = body.trim().isEmpty() ? "Notes indisponibles (pas de connexion ?)." : plain(body);
            a.runOnUiThread(() -> new AlertDialog.Builder(a).setTitle("Nouveautés de la version " + v).setMessage(txt)
                    .setPositiveButton("OK", null).show());
        }).start();
    }

    static int currentVersion(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Vérifie s'il existe une version plus récente. silent = ne rien dire s'il n'y en a pas. */
    static void check(final Activity a, final boolean silent) {
        new Thread(() -> {
            int latest = -1;
            String apkUrl = null, notes = "", name = "";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "GestionEnfants");
                if (c.getResponseCode() == 200) {
                    JSONObject rel = new JSONObject(read(c.getInputStream()));
                    name = rel.optString("name", "");
                    Matcher m = Pattern.compile("(\\d+)").matcher(name);         // « Version 49 (2.0.0) » : n° de compilation
                    if (m.find()) latest = Integer.parseInt(m.group(1));
                    notes = rel.optString("body", "").split("\n---")[0].trim();
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) for (int i = 0; i < assets.length(); i++) {
                        JSONObject as = assets.getJSONObject(i);
                        if (as.optString("name").equals(BuildConfig.APK_NAME)) apkUrl = as.optString("browser_download_url");
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            final int newest = latest;
            final String url = apkUrl, relNotes = notes;
            Matcher vm = Pattern.compile("\\((\\d+\\.\\d+\\.\\d+)\\)").matcher(name);
            final String newName = vm.find() ? vm.group(1) : String.valueOf(latest);
            final int mine = currentVersion(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (newest > mine && url != null) {
                    new AlertDialog.Builder(a)
                            .setTitle("Mise à jour disponible")
                            .setMessage("Version " + newName + " disponible (tu as la " + versionName(a) + ").\n\n"
                                    + (relNotes.isEmpty() ? "" : "Nouveautés :\n" + plain(relNotes) + "\n\n")
                                    + "Tes informations enregistrées sont conservées.")
                            .setPositiveButton("Mettre à jour", (d, w) -> start(a, url))
                            .setNegativeButton("Plus tard", null)
                            .show();
                } else if (!silent) {
                    new AlertDialog.Builder(a)
                            .setMessage(newest < 0 ? "Impossible de vérifier les mises à jour (pas de connexion ?)."
                                    : "L'appli est à jour (version " + versionName(a) + ", compilation " + mine + ").")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    private static void start(final Activity a, final String url) {
        // Android demande une autorisation unique pour que l'appli puisse installer ses mises à jour
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("Autorisation nécessaire")
                    .setMessage("Pour se mettre à jour, l'appli a besoin de l'autorisation « Installer des applis inconnues ».\n\n"
                            + "Active-la sur l'écran suivant, puis reviens et touche à nouveau « Mettre à jour ».")
                    .setPositiveButton("Ouvrir les réglages", (d, w) -> a.startActivity(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))))
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setMessage("Téléchargement de la mise à jour…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            try {
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(a.getPackageName());
                int sid = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(sid)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    c.setRequestProperty("User-Agent", "GestionEnfants");
                    // GitHub redirige vers un autre serveur : on suit les redirections à la main si besoin
                    int hops = 0;
                    while ((c.getResponseCode() == 301 || c.getResponseCode() == 302 || c.getResponseCode() == 307) && hops++ < 5) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        c = (HttpURLConnection) new URL(loc).openConnection();
                        c.setRequestProperty("User-Agent", "GestionEnfants");
                    }
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("update.apk", 0, c.getContentLength())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    c.disconnect();
                    Intent cb = new Intent(a, UpdateReceiver.class);
                    PendingIntent p = PendingIntent.getBroadcast(a, sid, cb,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(p.getIntentSender());
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (err != null) new AlertDialog.Builder(a)
                        .setMessage("La mise à jour a échoué : " + err + "\nRéessaie plus tard.")
                        .setPositiveButton("OK", null).show();
            });
        }).start();
    }

    private static String read(InputStream is) throws java.io.IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Reçoit le résultat de l'installation ; affiche la confirmation Android quand elle est requise. */
    public static class UpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.widget.Toast.makeText(context, "Mise à jour non installée" + (msg != null ? " : " + msg : ""),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
