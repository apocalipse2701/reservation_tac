package fr.tac.sad;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Suivi des demandes de cantine : toutes les heures environ, l'appli relit le planning du portail famille
 * (en se reconnectant seule si besoin), repère les demandes « en attente » devenues acceptées ou refusées,
 * met à jour le widget et prévient (notification, ou fenêtre si l'appli est ouverte).
 */
public class CantineWatch extends JobService {

    private static final int JOB_ID = 81, NOTIF_ID = 82;
    private static final String CANAL = "cantine";
    private static final String ACT = "Restauration Scolaire";

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return;   // déjà programmé : ne pas relancer à chaque ouverture
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, CantineWatch.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable t) {
            CrashLog.save(c, t);
        }
    }

    // ------------------------------------------------------------------ comparaison (partagée avec la page)

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE);
    }

    private static String code(JSONObject e) {
        String t = e.optString("typePlanningDemande", "");
        if (t.equals("null")) t = "";
        if (!t.isEmpty()) return "wait";
        switch (e.optString("etatPresence", "")) {
            case "RESERVATION_OK":
            case "PRESENT_OK":
                return "ok";
            case "ABSENCE_OK":
            case "ABSENCE_FACTUREE":
                return "abs";
            case "LISTEATTENTE_OK":
                return "wait";
            default:
                return "no";
        }
    }

    private static String cap(String s) {
        s = s == null ? "" : s.toLowerCase(Locale.FRANCE);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String dayLabel(String day) {
        try {
            return new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day));
        } catch (Exception e) {
            return day;
        }
    }

    /**
     * Compare les créneaux lus avec les demandes en attente déjà connues ; met à jour le widget.
     * Renvoie les messages « acceptée / refusée ».
     */
    static synchronized List<String> observe(Context c, List<JSONObject> events) {
        List<String> news = new ArrayList<>();
        try {
            SharedPreferences p = prefs(c);
            JSONObject pending = new JSONObject(p.getString("cantine_pending", "{}"));
            JSONObject w = new JSONObject(p.getString("widget_cal", "{}"));
            JSONObject cant = w.optJSONObject("cantine");
            if (cant == null) cant = new JSONObject();
            for (JSONObject e : events) {
                if (!ACT.equals(e.optString("title"))) continue;
                String id = e.optString("id"), day = e.optString("start").substring(0, 10), who = cap(e.optString("individu"));
                String t = e.optString("typePlanningDemande", "");
                if (t.equals("null")) t = "";
                JSONObject d = cant.optJSONObject(day);
                if (d == null) cant.put(day, d = new JSONObject());
                d.put(who, code(e));
                if (!t.isEmpty()) {
                    pending.put(id, new JSONObject().put("t", t).put("d", day).put("w", who));
                } else if (pending.has(id)) {
                    String old = pending.getJSONObject(id).optString("t");
                    String st = e.optString("etatPresence");
                    boolean resa = "RESERVATION".equals(old);
                    boolean accepted = resa ? (st.equals("RESERVATION_OK") || st.equals("PRESENT_OK"))
                            : (st.equals("ABSENCE_OK") || st.equals("ABSENCE_FACTUREE"));
                    news.add("Cantine de " + who + " : " + (resa ? "réservation" : "annulation") + " du " + dayLabel(day)
                            + (accepted ? " acceptée ✅" : " refusée ❌"));
                    pending.remove(id);
                }
            }
            // ménage : demandes de jours passés
            Calendar lim = Calendar.getInstance();
            lim.add(Calendar.DAY_OF_MONTH, -2);
            String limK = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(lim.getTime());
            List<String> old = new ArrayList<>();
            for (Iterator<String> it = pending.keys(); it.hasNext(); ) {
                String k = it.next();
                if (pending.optJSONObject(k) == null || pending.getJSONObject(k).optString("d").compareTo(limK) < 0) old.add(k);
            }
            for (String k : old) pending.remove(k);
            w.put("cantine", cant);
            p.edit().putString("cantine_pending", pending.toString()).putString("widget_cal", w.toString()).apply();
            CalendarWidget.refreshAll(c);
        } catch (Exception ignored) {
        }
        return news;
    }

    /** Messages pas encore montrés (lus une seule fois par la page). */
    static synchronized String takeNews(Context c) {
        String s = prefs(c).getString("cantine_news", "[]");
        prefs(c).edit().remove("cantine_news").apply();
        return s;
    }

    private static synchronized void keepNews(Context c, List<String> news) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("cantine_news", "[]"));
            for (String n : news) a.put(n);
            prefs(c).edit().putString("cantine_news", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ tâche de fond

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context c = getApplicationContext();
        CrashLog.install(c);
        Perso.refresh(c);
        if (LoginActivity.PORTAL.isEmpty()) return false;
        try {
            if (!Vault.has(c)) return false;
            // le gestionnaire de cookies de WebView doit être démarré sur le fil principal (appli fermée)
            CookieManager.getInstance();
        } catch (Throwable t) {
            CrashLog.save(c, t);
            return false;
        }
        new Thread(() -> {
            try {
                if (readAndObserve(c)) {
                    jobFinished(params, false);
                    return;
                }
                // pas connecté : connexion invisible avec les identifiants enregistrés, puis nouvel essai
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        headlessLogin(c, ok -> new Thread(() -> {
                            try {
                                if (ok) readAndObserve(c);
                            } catch (Throwable t) {
                                CrashLog.save(c, t);
                            }
                            jobFinished(params, false);
                        }).start());
                    } catch (Throwable t) {
                        CrashLog.save(c, t);
                        jobFinished(params, false);
                    }
                });
            } catch (Throwable t) {
                CrashLog.save(c, t);
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** Lit le planning (semaine en cours et suivantes) ; false si pas connecté. */
    private static boolean readAndObserve(Context c) {
        String[] r = Portal.request("GET", "activites/planning", null, null, null);
        if (!Portal.isPlanning(r)) return false;
        List<JSONObject> evs = new ArrayList<>(Portal.parseEvents(r[2]));
        String csrf = Portal.csrf(r[2]);
        if (csrf != null) {
            Calendar mon = Calendar.getInstance();
            mon.add(Calendar.DAY_OF_MONTH, -((mon.get(Calendar.DAY_OF_WEEK) + 5) % 7));
            String body = "_csrf=" + java.net.URLEncoder.encode(csrf) + "&defaultView=fiveDayGrid&defaultDate="
                    + java.net.URLEncoder.encode(new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(mon.getTime()));
            String[] r2 = Portal.request("POST", "activites/planning", body, "application/x-www-form-urlencoded", null);
            if (Portal.isPlanning(r2)) evs.addAll(Portal.parseEvents(r2[2]));
        }
        List<String> news = observe(c, evs);
        if (!news.isEmpty()) {
            MainActivity a = MainActivity.foreground;
            if (a != null) a.showCantineNews(news);
            else {
                keepNews(c, news);
                notify(c, news);
            }
        }
        return true;
    }

    interface Done {
        void run(boolean ok);
    }

    /** Connexion sans rien afficher : la vraie page du portail, remplie avec les identifiants enregistrés. */
    @SuppressLint("SetJavaScriptEnabled")
    private static void headlessLogin(final Context c, final Done done) {
        final String[] saved = Vault.load(c);
        if (saved == null) {
            done.run(false);
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        final WebView w;
        try {
            w = new WebView(c);
        } catch (Exception e) {
            done.run(false);
            return;
        }
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void formSeen(boolean s) {
            }

            @JavascriptInterface
            public void captured(String a, String b) {
            }
        }, "LoginBridge");
        final boolean[] sent = {false}, over = {false};
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith(LoginActivity.PORTAL)) {
                    view.evaluateJavascript(LoginActivity.autoFillJs(saved[0], saved[1], !sent[0]), null);
                    sent[0] = true;
                }
            }
        });
        w.loadUrl(LoginActivity.PORTAL + "activites/planning");
        // vérification toutes les 4 s, 40 s au plus
        final int[] tries = {0};
        final Runnable[] check = new Runnable[1];
        check[0] = () -> new Thread(() -> {
            boolean ok = Portal.isPlanning(Portal.request("GET", "activites/planning", null, null, null));
            ui.post(() -> {
                if (over[0]) return;
                if (ok || ++tries[0] >= 10) {
                    over[0] = true;
                    CookieManager.getInstance().flush();
                    w.destroy();
                    done.run(ok);
                } else ui.postDelayed(check[0], 4000);
            });
        }).start();
        ui.postDelayed(check[0], 6000);
    }

    private static void notify(Context c, List<String> lines) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Cantine", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra("page", "cantine");
        PendingIntent pi = PendingIntent.getActivity(c, 8, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Cantine")
                .setContentText(lines.size() == 1 ? lines.get(0) : lines.size() + " réponses du portail")
                .setStyle(new Notification.BigTextStyle().bigText(String.join("\n", lines)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }
}
