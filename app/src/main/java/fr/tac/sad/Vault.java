package fr.tac.sad;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Identifiants du portail famille, chiffrés avec une clé du coffre-fort Android (Keystore). */
public class Vault {

    private static final String ALIAS = "portail_famille";
    private static final String PREF = "portal_login";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    static boolean save(Context c, String login, String password) {
        try {
            JSONObject o = new JSONObject();
            o.put("u", login);
            o.put("p", password);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = cipher.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            String v = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().putString(PREF, v).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Renvoie {identifiant, mot de passe} ou null. */
    static String[] load(Context c) {
        try {
            String v = c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getString(PREF, null);
            if (v == null) return null;
            String[] parts = v.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            JSONObject o = new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
            return new String[]{o.getString("u"), o.getString("p")};
        } catch (Exception e) {
            return null;
        }
    }

    /** Texte quelconque chiffré sous le nom donné (ex. réglages du lien avec le planning). */
    static boolean putSecret(Context c, String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String v = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().putString("secret_" + name, v).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String getSecret(Context c, String name) {
        try {
            String v = c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).getString("secret_" + name, null);
            if (v == null) return null;
            String[] parts = v.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void removeSecret(Context c, String name) {
        c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().remove("secret_" + name).apply();
    }

    static boolean has(Context c) {
        return c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).contains(PREF);
    }

    static void clear(Context c) {
        c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE).edit().remove(PREF).apply();
    }
}
