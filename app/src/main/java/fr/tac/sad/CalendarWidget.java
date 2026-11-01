package fr.tac.sad;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;

/** Widget d'écran d'accueil réglable : prochains jours d'école, cantine, bus, covoiturage. */
public class CalendarWidget extends AppWidgetProvider {

    private static final String[] JOURS = {"Dim", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam"};
    private static final String[] MOIS = {"janv", "févr", "mars", "avr", "mai", "juin", "juil", "août", "sept", "oct", "nov", "déc"};

    /** Réglages d'un widget (valeurs par défaut si jamais réglé). */
    static JSONObject config(Context c, int id) {
        JSONObject d = new JSONObject();
        try {
            d.put("days", 5).put("opacity", 95).put("theme", "light").put("text", "m")
                    .put("cantine", true).put("bus", true).put("covoit", true)
                    .put("title", true).put("legend", true).put("highlight", true).put("tomorrowAfter", true);
            String raw = c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getString("widget_cfg_" + id, null);
            if (raw != null) {
                JSONObject o = new JSONObject(raw);
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    d.put(k, o.get(k));
                }
            }
        } catch (Exception ignored) {
        }
        return d;
    }

    static void saveConfig(Context c, int id, JSONObject cfg) {
        c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().putString("widget_cfg_" + id, cfg.toString()).apply();
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(context, config(context, id)));
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) context.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().remove("widget_cfg_" + id).apply();
    }

    /** Appelé par l'appli quand les données changent. */
    static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, CalendarWidget.class));
        if (ids == null) return;
        for (int id : ids) mgr.updateAppWidget(id, build(context, config(context, id)));
    }

    /** Titre : prénoms connus (bus, covoiturage, cantine), sinon « Ma famille ». */
    static String title(Context c) {
        java.util.LinkedHashSet<String> n = new java.util.LinkedHashSet<>();
        String b = Perso.busKid(c);
        if (!b.isEmpty()) n.add(b);
        String k = Perso.get(c).optString("covKids", "");
        for (String x : k.split("\\s*(&|,| et )\\s*")) if (!x.trim().isEmpty()) n.add(x.trim());
        try {
            JSONObject w = new JSONObject(c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getString("widget_cal", "{}"));
            JSONObject cant = w.optJSONObject("cantine");
            if (cant != null) for (Iterator<String> it = cant.keys(); it.hasNext(); ) {
                JSONObject d = cant.optJSONObject(it.next());
                if (d != null) for (Iterator<String> j = d.keys(); j.hasNext(); ) n.add(j.next());
            }
        } catch (Exception ignored) {
        }
        if (n.isEmpty()) return "Ma famille";
        java.util.List<String> l = new java.util.ArrayList<>(n);
        if (l.size() == 1) return l.get(0);
        return String.join(", ", l.subList(0, l.size() - 1)) + " & " + l.get(l.size() - 1);
    }

    static int[] widgetIds(Context context) {
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, CalendarWidget.class));
        return ids == null ? new int[0] : ids;
    }

    static RemoteViews build(Context context, JSONObject cfg) {
        boolean dark = "dark".equals(cfg.optString("theme"));
        int ink = dark ? 0xFFECEFF3 : 0xFF1B2430, soft = dark ? 0xFFA9B2BD : 0xFF8A94A0, brand = dark ? 0xFF7BD3A8 : 0xFF0A6E4F;
        int warn = dark ? 0xFFFFB454 : 0xFFC2410C;
        float size = "s".equals(cfg.optString("text")) ? 11f : "l".equals(cfg.optString("text")) ? 16f : 13f;

        RemoteViews root = new RemoteViews(context.getPackageName(), R.layout.widget);
        root.removeAllViews(R.id.rows);
        // fond : couleur du thème et opacité réglable
        root.setInt(R.id.bg, "setColorFilter", dark ? 0xFF202428 : 0xFFFFFFFF);
        root.setInt(R.id.bg, "setImageAlpha", Math.round(255 * Math.max(0, Math.min(100, cfg.optInt("opacity", 95))) / 100f));
        root.setViewVisibility(R.id.title, cfg.optBoolean("title", true) ? android.view.View.VISIBLE : android.view.View.GONE);
        root.setTextViewText(R.id.title, title(context));
        root.setTextColor(R.id.title, brand);
        root.setTextViewTextSize(R.id.title, android.util.TypedValue.COMPLEX_UNIT_SP, size + 2);
        boolean showC = cfg.optBoolean("cantine", true), showB = cfg.optBoolean("bus", true), showK = cfg.optBoolean("covoit", true);
        StringBuilder legend = new StringBuilder();
        if (showC) legend.append("✅ réservé  ✗ non réservé  ⏳ attente  ❌ absente");
        if (showK) legend.append(legend.length() > 0 ? "  ·  " : "").append("🚗 initiale = qui récupère");
        root.setTextViewText(R.id.legend, legend);
        root.setViewVisibility(R.id.legend, cfg.optBoolean("legend", true) && legend.length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        root.setTextColor(R.id.legend, soft);
        root.setTextViewTextSize(R.id.legend, android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(9f, size - 3));

        JSONObject data;
        try {
            data = new JSONObject(context.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getString("widget_cal", "{}"));
        } catch (Exception e) {
            data = new JSONObject();
        }
        JSONObject cantine = data.optJSONObject("cantine");
        JSONObject bus = data.optJSONObject("bus");
        JSONObject covoit = data.optJSONObject("covoit");
        java.util.Map<String, String> labels = labels(data.optJSONArray("covoitNoms"));

        Calendar c = Calendar.getInstance();
        // après 18 h, la journée est finie : on commence au lendemain
        if (cfg.optBoolean("tomorrowAfter", true) && c.get(Calendar.HOUR_OF_DAY) >= 18) c.add(Calendar.DAY_OF_MONTH, 1);
        Calendar today = Calendar.getInstance();
        int nb = Math.max(1, Math.min(10, cfg.optInt("days", 5)));
        int shown = 0;
        for (int guard = 0; shown < nb && guard < 21; guard++, c.add(Calendar.DAY_OF_MONTH, 1)) {
            int dow = c.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue;
            String key = String.format(Locale.FRANCE, "%04d-%02d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            boolean isToday = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
            Calendar tmw = (Calendar) today.clone();
            tmw.add(Calendar.DAY_OF_MONTH, 1);
            boolean isTomorrow = c.get(Calendar.YEAR) == tmw.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == tmw.get(Calendar.DAY_OF_YEAR);

            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
            String label = (isToday ? "Auj." : isTomorrow ? "Dem." : JOURS[dow - 1]) + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MOIS[c.get(Calendar.MONTH)];
            row.setTextViewText(R.id.day, label);
            row.setTextColor(R.id.day, isToday ? brand : ink);
            row.setTextViewTextSize(R.id.day, android.util.TypedValue.COMPLEX_UNIT_SP, size);

            if (showC) {
                row.setTextViewText(R.id.cantine, cantineText(cantine == null ? null : cantine.optJSONObject(key)));
                row.setTextColor(R.id.cantine, ink);
                row.setTextViewTextSize(R.id.cantine, android.util.TypedValue.COMPLEX_UNIT_SP, size);
            } else row.setViewVisibility(R.id.cantine, android.view.View.GONE);

            // Bus : seulement si une demande a été faite via l'appli ; covoiturage : initiale de qui s'en occupe
            String b = showB ? busText(Perso.busKid(context), bus == null ? null : bus.optJSONObject(key)) : "";
            String cv = showK && covoit != null ? label(labels, covoit.optString(key, "")) : "";
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(b);
            if (!cv.isEmpty()) {
                if (sb.length() > 0) sb.append("  ");
                sb.append("🚗 ");
                int st = sb.length();
                sb.append(cv);
                if ("?".equals(cv) && cfg.optBoolean("highlight", true)) {
                    // personne pour aller chercher les filles : mis en évidence
                    sb.setSpan(new android.text.style.ForegroundColorSpan(warn), st, sb.length(), 0);
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length(), 0);
                }
            }
            if (showB || showK) {
                row.setTextViewText(R.id.bus, sb);
                row.setTextColor(R.id.bus, ink);
                row.setTextViewTextSize(R.id.bus, android.util.TypedValue.COMPLEX_UNIT_SP, size);
            } else row.setViewVisibility(R.id.bus, android.view.View.GONE);
            root.addView(R.id.rows, row);
            shown++;
        }

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        root.setOnClickPendingIntent(R.id.widget_root, pi);
        return root;
    }

    private static String cantineText(JSONObject day) {
        if (day == null || day.length() == 0) return "🍽️ —";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = day.keys();
        while (it.hasNext()) {
            String who = it.next();
            String st = day.optString(who);
            String ico;
            switch (st) {
                case "ok": ico = "✅"; break;
                case "wait": ico = "⏳"; break;
                case "abs": ico = "❌"; break;
                default: ico = "✗"; break;
            }
            if (sb.length() > 0) sb.append("  ");
            sb.append("🍽️ ").append(who).append(" ").append(ico);
        }
        return sb.toString();
    }

    private static String busText(String kid, JSONObject day) {
        if (day == null) return "";
        String s = "🚌 " + (kid.isEmpty() ? "" : kid + " ") + day.optString("aller", "");
        String r = day.optString("retour", "");
        if (!r.isEmpty() && !"null".equals(r)) s += " / " + r;
        return s;
    }

    /** Nom du jour (« Maman », ou ancien format « Maman 17:00 ») → son libellé court ; « ? » si personne. */
    private static String label(java.util.Map<String, String> labels, String v) {
        v = v.replaceAll("\\s+\\d{1,2}:\\d{2}$", "").trim();
        if (v.isEmpty()) return "";
        if (v.equals("?")) return "?";
        String l = labels.get(v);
        return l != null ? l : first(v, 1);
    }

    /** Les n premières lettres : 1re en majuscule, 2e en minuscule (« Pauline » → « P » ou « Pa »). */
    static String first(String name, int n) {
        int[] cps = name.trim().codePoints().toArray();
        if (cps.length == 0) return "";
        StringBuilder b = new StringBuilder().appendCodePoint(Character.toUpperCase(cps[0]));
        if (n > 1 && cps.length > 1) b.appendCodePoint(Character.toLowerCase(cps[1]));
        return b.toString();
    }

    /**
     * Libellé court de chaque membre :
     * 1) ses initiales choisies dans les réglages, s'il en a mis ;
     * 2) sinon son initiale ; si plusieurs prénoms commencent par la même lettre, le plus court garde une lettre
     *    et les plus longs en prennent deux (« Paul » → P, « Pauline » → Pa) ;
     * 3) s'il reste deux libellés identiques (« Marc » / « Maud » → Ma), on prend la première lettre qui les
     *    distingue (Mr / Mu) : deux personnes n'ont jamais le même libellé.
     */
    static java.util.Map<String, String> labels(org.json.JSONArray names) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (names == null) return out;
        java.util.List<String> auto = new java.util.ArrayList<>();
        for (int i = 0; i < names.length(); i++) {
            JSONObject o = names.optJSONObject(i);
            String n = (o != null ? o.optString("nom", "") : names.optString(i, "")).trim();
            String badge = o != null ? o.optString("badge", "").trim() : "";
            if (n.isEmpty() || out.containsKey(n) || auto.contains(n)) continue;
            if (!badge.isEmpty()) out.put(n, badge);
            else auto.add(n);
        }
        java.util.Map<String, java.util.List<String>> groups = new java.util.HashMap<>();
        for (String n : auto) {
            String k = first(n, 1);
            java.util.List<String> g = groups.get(k);
            if (g == null) groups.put(k, g = new java.util.ArrayList<>());
            g.add(n);
        }
        for (java.util.List<String> g : groups.values()) {
            if (g.size() == 1) {
                out.put(g.get(0), first(g.get(0), 1));
                continue;
            }
            int min = Integer.MAX_VALUE, nbMin = 0;
            for (String n : g) {
                int len = n.codePointCount(0, n.length());
                if (len < min) { min = len; nbMin = 1; } else if (len == min) nbMin++;
            }
            for (String n : g) {
                boolean shortest = n.codePointCount(0, n.length()) == min && nbMin == 1;
                out.put(n, first(n, shortest ? 1 : 2));
            }
        }
        // initiale automatique déjà choisie par quelqu'un d'autre comme initiales personnelles : deux lettres
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (String n : out.keySet()) if (!auto.contains(n)) taken.add(out.get(n));
        for (String n : auto) if (taken.contains(out.get(n))) out.put(n, first(n, 2));
        // libellés encore identiques : première lettre qui distingue
        for (String a : auto) for (String b : auto) {
            if (a.equals(b) || !out.get(a).equals(out.get(b))) continue;
            int[] x = a.toLowerCase().codePoints().toArray(), y = b.toLowerCase().codePoints().toArray();
            int k = 1;
            while (k < x.length && k < y.length && x[k] == y[k]) k++;
            if (k < x.length) out.put(a, first(a, 1) + new String(Character.toChars(x[k])));
            if (k < y.length) out.put(b, first(b, 1) + new String(Character.toChars(y[k])));
        }
        return out;
    }
}
