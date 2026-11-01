package fr.tac.sad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Garde la trace d'un plantage et l'affiche à la prochaine ouverture (bouton « Copier » pour l'envoyer). */
public class CrashLog {

    private static boolean installed;

    static synchronized void install(final Context c) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            save(c, ex);
            if (prev != null) prev.uncaughtException(th, ex);
        });
    }

    static void save(Context c, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String txt = "Version " + Updater.currentVersion(c) + " · "
                    + new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date()) + "\n"
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE + "\n\n"
                    + sw;
            if (txt.length() > 6000) txt = txt.substring(0, 6000);
            prefs(c).edit().putString("last_crash", txt).commit();
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE);
    }

    static void showIfAny(final Activity a) {
        final String txt = prefs(a).getString("last_crash", null);
        if (txt == null) return;
        prefs(a).edit().remove("last_crash").apply();
        new AlertDialog.Builder(a)
                .setTitle("L'appli a rencontré une erreur")
                .setMessage("Copie ce rapport et envoie-le pour qu'elle soit corrigée :\n\n" + txt)
                .setPositiveButton("Copier", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Rapport d'erreur", txt));
                    Toast.makeText(a, "Rapport copié", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }
}
