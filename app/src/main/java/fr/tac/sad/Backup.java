package fr.tac.sad;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sauvegarde chiffrée de tous les réglages (préférences + secrets déchiffrés), protégée par un mot de passe choisi.
 * Format : "GEB1" + sel(16) + iv(12) + AES-256-GCM(gzip(JSON)), clé PBKDF2-HMAC-SHA256 (200 000 tours).
 * Sur le Synology : dans le dossier du covoiturage, fichier dont le nom dépend du mot de passe (personne d'autre ne le retrouve).
 */
final class Backup {
    private Backup() {
    }

    private static final byte[] MAGIC = {'G', 'E', 'B', '1'};
    private static final int ITER = 200_000;
    private static final String PREFS = "tac_sad";

    // ------------------------------------------------------------------ contenu

    static byte[] build(Context c, String password) throws Exception {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.startsWith("secret_") || k.equals("portal_login") || v == null) continue;
            String t = v instanceof String ? "s" : v instanceof Long ? "l" : v instanceof Integer ? "i" : v instanceof Boolean ? "b" : v instanceof Float ? "f" : null;
            if (t == null) continue;
            prefs.put(k, new JSONArray().put(t).put(v));
        }
        JSONObject secrets = new JSONObject();
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("secret_")) continue;
            String name = k.substring(7), v = Vault.getSecret(c, name);
            if (v != null) secrets.put(name, v);
        }
        String[] login = Vault.load(c);
        if (login != null) secrets.put("__portal", new JSONArray().put(login[0]).put(login[1]));
        JSONObject all = new JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("app", BuildConfig.APPLICATION_ID)
                .put("prefs", prefs).put("secrets", secrets);
        return encrypt(all.toString().getBytes(StandardCharsets.UTF_8), password);
    }

    /** Remet tous les réglages ; renvoie le nombre de réglages restaurés. */
    static int restore(Context c, byte[] data, String password) throws Exception {
        JSONObject all = new JSONObject(new String(decrypt(data, password), StandardCharsets.UTF_8));
        JSONObject prefs = all.optJSONObject("prefs"), secrets = all.optJSONObject("secrets");
        SharedPreferences.Editor ed = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        int n = 0;
        if (prefs != null) for (Iterator<String> it = prefs.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray tv = prefs.optJSONArray(k);
            if (tv == null || tv.length() < 2) continue;
            switch (tv.optString(0)) {
                case "s": ed.putString(k, tv.optString(1)); break;
                case "l": ed.putLong(k, tv.optLong(1)); break;
                case "i": ed.putInt(k, tv.optInt(1)); break;
                case "b": ed.putBoolean(k, tv.optBoolean(1)); break;
                case "f": ed.putFloat(k, (float) tv.optDouble(1)); break;
                default: continue;
            }
            n++;
        }
        ed.commit();
        if (secrets != null) for (Iterator<String> it = secrets.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.equals("__portal")) {
                JSONArray l = secrets.optJSONArray(k);
                if (l != null && l.length() == 2) Vault.save(c, l.optString(0), l.optString(1));
            } else Vault.putSecret(c, k, secrets.optString(k));
            n++;
        }
        // relancer les vérifications de fond
        Perso.refresh(c);
        try {
            if (Vault.has(c)) CantineWatch.schedule(c);
            if (Vault.getSecret(c, "carpool") != null) Carpool.schedule(c);
            if (Vault.getSecret(c, BusMail.SECRET) != null) BusMailWatch.schedule(c);
        } catch (Exception ignored) {
        }
        CalendarWidget.refreshAll(c);
        return n;
    }

    // ------------------------------------------------------------------ Synology (réglages du covoiturage)

    private static HttpURLConnection open(Context c, String password, String method) throws Exception {
        JSONObject o = Carpool.secret(c);
        if (o == null) throw new IOException("règle d'abord le covoiturage (adresse du Synology, identifiant, mot de passe, dossier)");
        String url = PlanningLink.racine(o.optString("adresse")) + segments(o.optString("dossier", "Covoiturage")) + fileName(password);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        Tls.apply(c, h);
        h.setConnectTimeout(20000);
        h.setReadTimeout(30000);
        h.setRequestMethod(method);
        String auth = o.optString("identifiant") + ":" + o.optString("motDePasse");
        h.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        return h;
    }

    /** Nom du fichier : dépend du mot de passe, pour que chaque famille ait le sien dans le dossier partagé. */
    static String fileName(String password) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(("gestion-enfants-sauvegarde:" + password).getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder("sauvegarde-reglages-");
        for (int i = 0; i < 6; i++) b.append(String.format("%02x", h[i]));
        return b.append(".bin").toString();
    }

    static String toSynology(Context c, String password) {
        JSONObject r = new JSONObject();
        try {
            byte[] body = build(c, password);
            HttpURLConnection h = open(c, password, "PUT");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/octet-stream");
            h.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = h.getOutputStream()) {
                out.write(body);
            }
            int code = h.getResponseCode();
            h.disconnect();
            if (code == 401) throw new IOException("identifiant ou mot de passe du Synology refusé");
            if (code == 403 || code == 405) throw new IOException("le Synology refuse l'enregistrement dans le dossier du covoiturage (droits d'écriture ?)");
            if (code == 404 || code == 409) throw new IOException("le dossier du covoiturage n'existe pas sur le Synology");
            if (code >= 300) throw new IOException("le Synology a répondu " + code);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("backup_at", System.currentTimeMillis()).apply();
            r.put("ok", true);
        } catch (Exception e) {
            err(r, e);
        }
        return r.toString();
    }

    static String fromSynology(Context c, String password) {
        JSONObject r = new JSONObject();
        try {
            HttpURLConnection h = open(c, password, "GET");
            int code = h.getResponseCode();
            if (code == 404) throw new IOException("aucune sauvegarde trouvée avec ce mot de passe dans le dossier du covoiturage");
            if (code == 401) throw new IOException("identifiant ou mot de passe du Synology refusé");
            if (code != 200) throw new IOException("le Synology a répondu " + code);
            byte[] d;
            try (InputStream in = h.getInputStream()) {
                d = readAll(in);
            }
            h.disconnect();
            r.put("ok", true).put("n", restore(c, d, password));
        } catch (Exception e) {
            err(r, e);
        }
        return r.toString();
    }

    private static void err(JSONObject r, Exception e) {
        try {
            String m = e.getMessage();
            if (e instanceof javax.net.ssl.SSLException) m = "connexion sécurisée au Synology impossible (certificat ?)";
            else if (e instanceof java.net.UnknownHostException) m = "Synology introuvable (adresse ou connexion internet)";
            r.put("ok", false).put("erreur", m == null ? e.getClass().getSimpleName() : m);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ chiffrement

    private static SecretKeySpec key(String password, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, ITER, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    static byte[] encrypt(byte[] clear, String password) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom r = new SecureRandom();
        r.nextBytes(salt);
        r.nextBytes(iv);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(gz)) {
            z.write(clear);
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(gz.toByteArray());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(MAGIC);
        b.write(salt);
        b.write(iv);
        b.write(ct);
        return b.toByteArray();
    }

    static byte[] decrypt(byte[] d, String password) throws IOException {
        if (d.length < 4 + 16 + 12 + 16) throw new IOException("fichier de sauvegarde abîmé");
        for (int i = 0; i < 4; i++) if (d[i] != MAGIC[i]) throw new IOException("ce n'est pas une sauvegarde de l'appli");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(password, Arrays.copyOfRange(d, 4, 20)), new GCMParameterSpec(128, Arrays.copyOfRange(d, 20, 32)));
            byte[] gz = c.doFinal(d, 32, d.length - 32);
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                return readAll(in);
            }
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IOException("mot de passe de sauvegarde incorrect");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("déchiffrement impossible");
        }
    }

    private static String segments(String dossier) throws IOException {
        StringBuilder b = new StringBuilder();
        for (String s : dossier.split("/")) {
            s = s.trim();
            if (s.isEmpty() || s.equals(".") || s.equals("..")) continue;
            b.append(URLEncoder.encode(s, "UTF-8").replace("+", "%20")).append('/');
        }
        return b.toString();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > 20 * 1024 * 1024) throw new IOException("fichier trop gros");
        }
        return o.toByteArray();
    }
}
