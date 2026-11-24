package fr.tac.sad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Vacances scolaires (zone B, académie de Reims) téléchargées depuis l'open data de l'Éducation nationale,
 * et jours fériés calculés. Les dates sont gardées dans les préférences ("vacances") :
 * [[premier jour sans classe, dernier jour sans classe, nom], …] (dates incluses, format AAAA-MM-JJ).
 * En cas d'échec, on garde la dernière liste téléchargée, puis la liste intégrée.
 */
final class Holidays {
    private Holidays() {
    }

    static final String PREF = "vacances", PREF_AT = "vacances_at";
    private static final String ACADEMIE = "Reims";
    private static final String API = "https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/fr-en-calendrier-scolaire/records";

    /** Liste intégrée (repli) : zone B 2026-2027. */
    static final String BUILTIN = "[[\"2026-10-17\",\"2026-11-01\",\"Vacances de la Toussaint\"],[\"2026-12-19\",\"2027-01-03\",\"Vacances de Noël\"],"
            + "[\"2027-02-20\",\"2027-03-07\",\"Vacances d'hiver\"],[\"2027-04-17\",\"2027-05-02\",\"Vacances de printemps\"],"
            + "[\"2027-05-06\",\"2027-05-09\",\"Pont de l'Ascension\"],[\"2027-07-03\",\"2027-08-31\",\"Vacances d'été\"]]";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE);
    }

    /** Liste à utiliser (téléchargée si possible, sinon intégrée). */
    static String list(Context c) {
        String s = prefs(c).getString(PREF, null);
        return s == null || s.length() < 5 ? BUILTIN : s;
    }

    /** Télécharge si la dernière mise à jour date de plus de 7 jours (ou si force). Ne bloque pas l'appelant. */
    static void refreshAsync(final Context c, final boolean force) {
        long at = prefs(c).getLong(PREF_AT, 0);
        if (!force && System.currentTimeMillis() - at < 7L * 864e5) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> refresh(app)).start();
    }

    /** true si la liste a été mise à jour. */
    static synchronized boolean refresh(Context c) {
        HttpURLConnection h = null;
        try {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
            Calendar from = Calendar.getInstance();
            from.add(Calendar.DAY_OF_MONTH, -60);
            String where = "location=\"" + ACADEMIE + "\" and end_date>=\"" + day.format(from.getTime()) + "\"";
            String url = API + "?where=" + URLEncoder.encode(where, "UTF-8") + "&order_by=start_date&limit=60";
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Accept", "application/json");
            if (h.getResponseCode() != 200) return false;
            String body;
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && o.size() < 2_000_000) o.write(b, 0, n);
                body = new String(o.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray res = new JSONObject(body).optJSONArray("results");
            if (res == null || res.length() == 0) return false;
            JSONArray out = new JSONArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < res.length(); i++) {
                JSONObject r = res.getJSONObject(i);
                String pop = r.optString("population", "");
                if (pop.toLowerCase(Locale.ROOT).contains("enseignant")) continue;   // dates propres aux enseignants
                String[] span = span(r.optString("start_date"), r.optString("end_date"));
                if (span == null) continue;
                String k = span[0] + span[1];
                if (!seen.add(k)) continue;
                out.put(new JSONArray().put(span[0]).put(span[1]).put(r.optString("description", "Vacances")));
            }
            if (out.length() == 0) return false;
            prefs(c).edit().putString(PREF, out.toString()).putLong(PREF_AT, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /**
     * Dates incluses sans classe. Début : le lendemain du jour indiqué (départ « après la classe »).
     * Fin : la veille de la reprise ; la reprise est le premier jour de semaine à partir de la veille de la date de fin
     * (la source donne selon les cas le jour de reprise ou le lendemain).
     */
    static String[] span(String start, String end) {
        Calendar s = local(start), e = local(end);
        if (s == null || e == null) return null;
        s.add(Calendar.DAY_OF_MONTH, 1);
        Calendar rep = (Calendar) e.clone();
        rep.add(Calendar.DAY_OF_MONTH, -1);
        while (rep.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || rep.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) rep.add(Calendar.DAY_OF_MONTH, 1);
        rep.add(Calendar.DAY_OF_MONTH, -1);
        if (rep.before(s)) return null;
        return new String[]{key(s), key(rep)};
    }

    private static Calendar local(String iso) {
        try {
            if (iso == null || iso.length() < 10) return null;
            Date d;
            if (iso.length() > 10) {
                String t = iso.replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2").replace("Z", "+0000");
                d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).parse(t);
            } else d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso);
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
            c.setTime(d);
            Calendar r = Calendar.getInstance();
            r.clear();
            r.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), 12, 0);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(Calendar c) {
        return String.format(Locale.FRANCE, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Jour férié (calcul : dates fixes + Pâques, Ascension, Pentecôte). */
    static String ferie(String k) {
        int y = Integer.parseInt(k.substring(0, 4));
        String md = k.substring(5);
        switch (md) {
            case "01-01": return "Jour de l'an";
            case "05-01": return "Fête du travail";
            case "05-08": return "Victoire 1945";
            case "07-14": return "Fête nationale";
            case "08-15": return "Assomption";
            case "11-01": return "Toussaint";
            case "11-11": return "Armistice";
            case "12-25": return "Noël";
        }
        Calendar p = easter(y);
        Calendar t = (Calendar) p.clone();
        t.add(Calendar.DAY_OF_MONTH, 1);
        if (key(t).equals(k)) return "Lundi de Pâques";
        t = (Calendar) p.clone();
        t.add(Calendar.DAY_OF_MONTH, 39);
        if (key(t).equals(k)) return "Ascension";
        t = (Calendar) p.clone();
        t.add(Calendar.DAY_OF_MONTH, 50);
        if (key(t).equals(k)) return "Lundi de Pentecôte";
        return null;
    }

    static Calendar easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        Calendar r = Calendar.getInstance();
        r.clear();
        r.set(y, month - 1, day, 12, 0);
        return r;
    }

    /** Jour sans école (vacances ou férié) ? */
    static boolean noSchool(Context c, String k) {
        if (ferie(k) != null) return true;
        try {
            JSONArray a = new JSONArray(list(c));
            for (int i = 0; i < a.length(); i++) {
                JSONArray v = a.getJSONArray(i);
                if (k.compareTo(v.getString(0)) >= 0 && k.compareTo(v.getString(1)) <= 0) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
