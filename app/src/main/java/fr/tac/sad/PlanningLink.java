package fr.tac.sad;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lecture du planning partagé par l'appli « Planning Commandes » (même principe que sa version « lecture ») :
 * fichier <dossier>/planning-partage.bin sur le WebDAV (kDrive, Synology…), chiffré AES-256-GCM avec une clé
 * tirée du code de partage (PBKDF2-HMAC-SHA1, 150 000 tours). Rien n'est écrit sur le serveur.
 */
public class PlanningLink {

    private static final String SECRET = "planning_link";
    private static final String FICHIER = "planning-partage.bin";
    private static final byte[] MAGIQUE = {'P', 'C', 'P', '1'};

    /** Réglages {adresse, identifiant, motDePasse, dossier, code} (chiffrés sur le téléphone). */
    static String settings(Context c) {
        String s = Vault.getSecret(c, SECRET);
        if (s == null) return "{}";
        try {
            JSONObject o = new JSONObject(s);
            JSONObject pub = new JSONObject();
            pub.put("adresse", o.optString("adresse"));
            pub.put("identifiant", o.optString("identifiant"));
            pub.put("dossier", o.optString("dossier"));
            pub.put("motDePasse", !o.optString("motDePasse").isEmpty());
            pub.put("code", !o.optString("code").isEmpty());
            pub.put("actif", true);
            return pub.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Enregistre ; un mot de passe ou code vide garde l'ancien. */
    static void save(Context c, String json) {
        try {
            JSONObject n = new JSONObject(json);
            if (n.optBoolean("oublier")) {
                Vault.removeSecret(c, SECRET);
                c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().remove("planning_401").apply();
                return;
            }
            String old = Vault.getSecret(c, SECRET);
            JSONObject o = old == null ? new JSONObject() : new JSONObject(old);
            for (String k : new String[]{"adresse", "identifiant", "dossier"}) o.put(k, n.optString(k, "").trim());
            for (String k : new String[]{"motDePasse", "code"}) {
                String v = n.optString(k, "");
                if (!v.isEmpty()) o.put(k, v);
            }
            Vault.putSecret(c, SECRET, o.toString());
            c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().remove("planning_401").apply();
        } catch (Exception ignored) {
        }
    }

    /** Télécharge et déchiffre ; renvoie {ok, widget (texte JSON du planning), envoye} ou {ok:false, erreur}. */
    static String fetch(Context c) {
        JSONObject r = new JSONObject();
        try {
            String s = Vault.getSecret(c, SECRET);
            if (s == null) throw new IOException("lien avec le planning non réglé");
            if (c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getBoolean("planning_401", false))
                throw new IOException("identifiant ou mot de passe refusé par le serveur. Corrige-les dans ⚙️ Réglages : l'appli ne réessaie plus d'ici là, pour que le serveur ne bloque pas ton téléphone.");
            JSONObject o = new JSONObject(s);
            String url = racine(o.optString("adresse")) + segments(o.optString("dossier", "Planning")) + FICHIER;
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            Tls.apply(c, h);
            h.setConnectTimeout(20000);
            h.setReadTimeout(30000);
            String auth = o.optString("identifiant") + ":" + o.optString("motDePasse");
            h.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            int code = h.getResponseCode();
            if (code == 401) {
                c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().putBoolean("planning_401", true).apply();
                throw new IOException("identifiant ou mot de passe refusé par le serveur (kDrive : mot de passe d'application). Corrige-les dans ⚙️ Réglages : l'appli ne réessaie plus d'ici là.");
            }
            if (code == 404) throw new IOException("aucun planning partagé trouvé dans ce dossier");
            if (code != 200) throw new IOException("réponse " + code + " du serveur");
            byte[] data;
            try (InputStream in = h.getInputStream()) {
                data = readAll(in, 30 * 1024 * 1024);
            }
            h.disconnect();
            JSONObject paquet = new JSONObject(new String(dechiffrer(data, o.optString("code")), StandardCharsets.UTF_8));
            r.put("ok", true);
            r.put("widget", paquet.optString("widget", ""));
            r.put("envoye", paquet.optString("envoye", ""));
        } catch (Exception e) {
            try {
                r.put("ok", false);
                String m = e.getMessage();
                String ex = Tls.explain(e);
                if (ex != null) m = ex;
                if (e instanceof javax.net.ssl.SSLException && Tls.isCertProblem(e)) {
                    m = "certificat du serveur non reconnu";
                    String s = Vault.getSecret(c, SECRET);
                    if (s != null) {
                        String root = racine(new JSONObject(s).optString("adresse"));
                        r.put("certificat", Tls.peek(root));
                        r.put("serveur", root);
                    }
                }

                r.put("erreur", m == null ? e.getClass().getSimpleName() : m);
            } catch (Exception ignored) {
            }
        }
        return r.toString();
    }

    // --- mêmes règles que Planning Commandes (PartagePlanning.java) ---

    static String racine(String adresse) throws IOException {
        String a = adresse.trim();
        if (a.matches("\\d{3,12}")) return "https://" + a + ".connect.kdrive.infomaniak.com/";
        if (a.toLowerCase(Locale.ROOT).startsWith("http://")) throw new IOException("adresse en http:// refusée : utilise https://");
        if (a.matches("(?i)[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d{1,5})?/?")) {
            a = a.replaceAll("/$", "");
            return "https://" + a + (a.contains(":") ? "" : ":5006") + "/";
        }
        if (!a.toLowerCase(Locale.ROOT).startsWith("https://")) throw new IOException("adresse incorrecte");
        return a.endsWith("/") ? a : a + "/";
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

    private static byte[] dechiffrer(byte[] d, String code) throws IOException {
        if (d.length < 4 + 16 + 12 + 16) throw new IOException("fichier de partage abîmé");
        for (int i = 0; i < 4; i++) if (d[i] != MAGIQUE[i]) throw new IOException("ce n'est pas un planning partagé");
        byte[] sel = Arrays.copyOfRange(d, 4, 20), iv = Arrays.copyOfRange(d, 20, 32);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            String norm = code == null ? "" : code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            byte[] k = f.generateSecret(new PBEKeySpec(norm.toCharArray(), sel, 150000, 256)).getEncoded();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
            byte[] gz = c.doFinal(d, 32, d.length - 32);
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                return readAll(in, 20 * 1024 * 1024);
            }
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IOException("code de partage incorrect");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("déchiffrement impossible");
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > max) throw new IOException("fichier trop gros");
        }
        return b.toByteArray();
    }
}
