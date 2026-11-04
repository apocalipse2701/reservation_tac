package fr.tac.sad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Réponses de la compagnie de bus par mail : chaque demande envoyée depuis l'appli attend une réponse ;
 * un mail de l'expéditeur réglé arrivé APRÈS une demande y répond (la plus ancienne encore en attente),
 * « prise en compte / confirmée » → acceptée, « refus / impossible / complet / annul » → refusée.
 * Lecture seule de la boîte (repris de Planning Commandes).
 */
public class BusMail {

    static final String SECRET = "mail";
    static final String DEFAULT_SENDER = "tad-charleville@pretapartir.fr";
    private static final String CANAL = "bus";
    private static final int NOTIF_ID = 92;

    /** Serveurs IMAP connus (mêmes fournisseurs que Planning Commandes). */
    private static final String[][] SERVEURS = {
            {"free.fr freesbee.fr libertysurf.fr", "imap.free.fr"},
            {"orange.fr wanadoo.fr", "imap.orange.fr"},
            {"sfr.fr neuf.fr cegetel.net club-internet.fr", "imap.sfr.fr"},
            {"bbox.fr", "imap.bbox.fr"},
            {"laposte.net", "imap.laposte.net"},
            {"gmail.com googlemail.com", "imap.gmail.com"},
            {"yahoo.fr yahoo.com ymail.com rocketmail.com", "imap.mail.yahoo.com"},
            {"icloud.com me.com mac.com", "imap.mail.me.com"},
            {"gmx.fr gmx.com gmx.net gmx.de", "imap.gmx.net"},
            {"ik.me etik.com ikmail.com", "mail.infomaniak.com"},
    };

    static String serverFor(String address) {
        int at = address.lastIndexOf('@');
        if (at < 0) return "";
        String dom = address.substring(at + 1).toLowerCase(Locale.ROOT).trim();
        for (String[] s : SERVEURS) if (Arrays.asList(s[0].split(" ")).contains(dom)) return s[1];
        return "";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ réglages

    static String settings(Context c) {
        try {
            String s = Vault.getSecret(c, SECRET);
            JSONObject o = s == null ? new JSONObject() : new JSONObject(s);
            JSONObject p = new JSONObject();
            p.put("actif", s != null);
            p.put("adresse", o.optString("adresse"));
            p.put("serveur", o.optString("serveur"));
            p.put("expediteur", o.optString("expediteur", DEFAULT_SENDER));
            p.put("motDePasse", !o.optString("motDePasse").isEmpty());
            p.put("attente", pending(c).length());
            return p.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static void save(Context c, String json) {
        try {
            JSONObject n = new JSONObject(json);
            if (n.optBoolean("oublier")) {
                Vault.removeSecret(c, SECRET);
                return;
            }
            String old = Vault.getSecret(c, SECRET);
            JSONObject o = old == null ? new JSONObject() : new JSONObject(old);
            String adr = n.optString("adresse", "").trim();
            o.put("adresse", adr);
            String srv = n.optString("serveur", "").trim();
            o.put("serveur", srv.isEmpty() ? serverFor(adr) : srv);
            String exp = n.optString("expediteur", "").trim();
            o.put("expediteur", exp.isEmpty() ? DEFAULT_SENDER : exp);
            if (!n.optString("motDePasse", "").isEmpty()) o.put("motDePasse", n.optString("motDePasse"));
            Vault.putSecret(c, SECRET, o.toString());
            prefs(c).edit().remove("busmail_seen").remove("busmail_validite").apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ demandes en attente

    static JSONArray pending(Context c) {
        try {
            return new JSONArray(prefs(c).getString("bus_pending", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Une demande vient d'être envoyée depuis l'appli. */
    static synchronized void sent(Context c, String day, String aller) {
        try {
            JSONArray p = pending(c);
            p.put(new JSONObject().put("day", day).put("aller", aller).put("at", System.currentTimeMillis()));
            prefs(c).edit().putString("bus_pending", p.toString()).apply();
            setWidget(c, day, "wait");
        } catch (Exception ignored) {
        }
    }

    /** Réponse saisie à la main (mail jamais reçu) : la demande envoyée à l'instant « at » est réglée. */
    static synchronized boolean resolve(Context c, long at, String st) {
        try {
            JSONArray p = pending(c);
            for (int i = 0; i < p.length(); i++) {
                JSONObject d = p.getJSONObject(i);
                if (d.optLong("at") != at) continue;
                p.remove(i);
                prefs(c).edit().putString("bus_pending", p.toString()).apply();
                setWidget(c, d.optString("day"), st);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void setWidget(Context c, String day, String st) {
        try {
            JSONObject w = new JSONObject(prefs(c).getString("widget_cal", "{}"));
            JSONObject bus = w.optJSONObject("bus");
            if (bus == null) w.put("bus", bus = new JSONObject());
            JSONObject d = bus.optJSONObject(day);
            if (d == null) bus.put(day, d = new JSONObject());
            d.put("st", st);
            prefs(c).edit().putString("widget_cal", w.toString()).apply();
            CalendarWidget.refreshAll(c);
        } catch (Exception ignored) {
        }
    }

    private static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** "ok", "ko" ou null (mail sans réponse claire). */
    static String classify(String text) {
        String t = norm(text);
        if (t.matches("(?s).*(refus|impossible|pas pu etre|n.a pas pu|complet|annul|pas possible|ne peut pas|ne pourra pas|ne pourrons pas|pas ete pris|n.est pas pris|n.a pas ete).*")) return "ko";
        if (t.matches("(?s).*(prise en compte|pris en compte|confirm|valide|accept|enregistr).*")) return "ok";
        return null;
    }

    private static String dayLabel(String day) {
        try {
            return new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day));
        } catch (Exception e) {
            return day;
        }
    }

    // ------------------------------------------------------------------ relevé

    /** Relève la boîte et renvoie les messages « acceptée / refusée » trouvés. Lève une exception en cas d'échec. */
    static synchronized List<String> check(Context c) throws Exception {
        List<String> news = new ArrayList<>();
        JSONArray p = pending(c);
        String s = Vault.getSecret(c, SECRET);
        if (p.length() == 0 || s == null) return news;
        JSONObject o = new JSONObject(s);
        if (o.optString("serveur").isEmpty()) throw new java.io.IOException("serveur de messagerie inconnu : indique-le dans ⚙️ Réglages");
        long depuis = Long.MAX_VALUE;
        for (int i = 0; i < p.length(); i++) depuis = Math.min(depuis, p.getJSONObject(i).optLong("at"));
        Set<Long> deja = new HashSet<>();
        for (String x : prefs(c).getString("busmail_seen", "").split(",")) if (!x.isEmpty()) deja.add(Long.parseLong(x));
        long[] validite = {prefs(c).getLong("busmail_validite", 0)};
        String exp = o.optString("expediteur", DEFAULT_SENDER);
        List<MailImap.Retour> msgs = new MailImap().avis(o.optString("serveur"), 993, o.optString("adresse"), o.optString("motDePasse"),
                java.util.Collections.singletonList(exp), depuis - 3600000L, validite, deja);
        msgs.sort((a, b) -> Long.compare(a.uid, b.uid));
        String kid = Perso.busKid(c);
        for (MailImap.Retour r : msgs) {
            deja.add(r.uid);
            MailImap.Mime.Message m = MailImap.Mime.lire(r.brut);
            if (!MailImap.expediteurAccepte(m.de, java.util.Collections.singletonList(exp))) continue;
            String st = classify(m.sujet + "\n" + m.texte);
            if (st == null) continue;
            long t = m.date.isEmpty() ? System.currentTimeMillis() : MailImap.dateMessage(m.date);
            if (t <= 0) t = System.currentTimeMillis();
            // la plus ancienne demande envoyée avant ce mail (5 min de marge d'horloge)
            int best = -1;
            for (int i = 0; i < p.length(); i++) {
                if (p.getJSONObject(i).optLong("at") <= t + 300000L && (best < 0 || p.getJSONObject(i).optLong("at") < p.getJSONObject(best).optLong("at"))) best = i;
            }
            if (best < 0) continue;
            JSONObject d = p.getJSONObject(best);
            p.remove(best);
            setWidget(c, d.optString("day"), st);
            news.add("Bus" + (kid.isEmpty() ? "" : " de " + kid) + " : demande du " + dayLabel(d.optString("day"))
                    + (d.optString("aller").isEmpty() ? "" : " (" + d.optString("aller") + ")")
                    + ("ok".equals(st) ? " prise en compte ✅" : " refusée ❌"));
        }
        StringBuilder sb = new StringBuilder();
        for (Long u : deja) sb.append(u).append(',');
        prefs(c).edit().putString("bus_pending", p.toString()).putString("busmail_seen", sb.toString())
                .putLong("busmail_validite", validite[0]).apply();
        return news;
    }

    /** Relevé en arrière-plan : notification, ou fenêtre si l'appli est ouverte. */
    static void checkAndTell(Context c) {
        try {
            List<String> news = check(c);
            if (news.isEmpty()) return;
            MainActivity a = MainActivity.foreground;
            if (a != null) a.showCantineNews(news);
            else notify(c, news);
        } catch (Throwable t) {
            // boîte injoignable : nouvel essai au prochain passage
        }
    }

    private static void notify(Context c, List<String> lines) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Bus", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(c, 9, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIF_ID, new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Demande de bus")
                .setContentText(lines.get(0))
                .setStyle(new Notification.BigTextStyle().bigText(String.join("\n", lines)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }
}
