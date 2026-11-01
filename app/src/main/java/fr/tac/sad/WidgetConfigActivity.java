package fr.tac.sad;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/** Réglages d'un widget, avec aperçu en direct. */
public class WidgetConfigActivity extends Activity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private JSONObject cfg;
    private FrameLayout preview;
    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        Bundle ex = getIntent().getExtras();
        if (ex != null) widgetId = ex.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        cfg = CalendarWidget.config(this, widgetId);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(16), px(16), px(16), px(24));

        TextView h = text("Personnaliser le widget", 20, true);
        h.setTextColor(Color.parseColor("#0A6E4F"));
        col.addView(h);

        // Aperçu sur un fond « fond d'écran »
        preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.parseColor("#5B7F95"));
        preview.setPadding(px(10), px(10), px(10), px(10));
        col.addView(preview, lp(-1, -2, 12));

        // Nombre de jours
        final TextView daysLbl = text("", 15, true);
        col.addView(daysLbl, lp(-1, -2, 16));
        SeekBar days = new SeekBar(this);
        days.setMax(9);
        days.setProgress(cfg.optInt("days", 5) - 1);
        daysLbl.setText("Nombre de jours affichés : " + cfg.optInt("days", 5));
        days.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                put("days", v + 1);
                daysLbl.setText("Nombre de jours affichés : " + (v + 1));
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(days);

        // Opacité
        col.addView(text("Opacité du fond", 15, true), lp(-1, -2, 16));
        col.addView(radios("opacity", new String[]{"0 %", "25 %", "50 %", "75 %", "100 %"},
                new Object[]{0, 25, 50, 75, 95}, cfg.optInt("opacity", 95)));

        // Thème
        col.addView(text("Couleurs", 15, true), lp(-1, -2, 16));
        col.addView(radios("theme", new String[]{"Clair", "Sombre"}, new Object[]{"light", "dark"}, cfg.optString("theme", "light")));

        // Taille du texte
        col.addView(text("Taille du texte", 15, true), lp(-1, -2, 16));
        col.addView(radios("text", new String[]{"Petite", "Normale", "Grande"}, new Object[]{"s", "m", "l"}, cfg.optString("text", "m")));

        // Contenu
        col.addView(text("Contenu", 15, true), lp(-1, -2, 16));
        col.addView(check("cantine", "Cantine"));
        col.addView(check("bus", "Bus (jours réservés via l'appli)"));
        col.addView(check("covoit", "Covoiturage (initiale de qui récupère)"));
        col.addView(check("highlight", "Mettre en évidence les jours sans personne (🚗 ?)"));
        col.addView(check("title", "Afficher le titre"));
        col.addView(check("legend", "Afficher la légende"));
        col.addView(check("tomorrowAfter", "Après 18 h, commencer au lendemain"));

        Button ok = new Button(this);
        ok.setText("Enregistrer");
        ok.setOnClickListener(v -> save());
        col.addView(ok, lp(-1, -2, 20));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        refresh();
    }

    private void save() {
        CalendarWidget.saveConfig(this, widgetId, cfg);
        AppWidgetManager.getInstance(this).updateAppWidget(widgetId, CalendarWidget.build(this, cfg));
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }

    private void put(String k, Object v) {
        try {
            cfg.put(k, v);
        } catch (Exception ignored) {
        }
        refresh();
    }

    private void refresh() {
        preview.removeAllViews();
        try {
            RemoteViews rv = CalendarWidget.build(this, cfg);
            View v = rv.apply(this, preview);
            preview.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception e) {
            preview.addView(text("Aperçu indisponible", 14, false));
        }
    }

    private RadioGroup radios(final String key, String[] labels, final Object[] values, Object current) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setId(View.generateViewId());
            final Object val = values[i];
            g.addView(b);
            if (String.valueOf(val).equals(String.valueOf(current))) b.setChecked(true);
            b.setOnCheckedChangeListener((btn, on) -> {
                if (on) put(key, val);
            });
        }
        return g;
    }

    private CheckBox check(final String key, String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(cfg.optBoolean(key, true));
        c.setOnCheckedChangeListener((b, on) -> put(key, on));
        return c;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = px(topDp);
        return p;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
