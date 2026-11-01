package fr.tac.sad;

import android.content.Context;

import org.json.JSONObject;

/** Réglages personnels (prénoms, adresse du portail) saisis dans l'appli et gardés sur le téléphone. */
public class Perso {

    static JSONObject get(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getString("perso", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Recharge l'adresse du portail famille (à appeler au démarrage et après un changement). */
    static void refresh(Context c) {
        String u = get(c).optString("portal", "").trim();
        if (!u.isEmpty() && !u.endsWith("/")) u += "/";
        LoginActivity.PORTAL = u.startsWith("https://") ? u : "";
    }

    static String busKid(Context c) {
        return get(c).optString("busKid", "");
    }

    static String covKids(Context c) {
        String k = get(c).optString("covKids", "");
        return k.isEmpty() ? "les enfants" : k;
    }
}
