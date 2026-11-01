package fr.tac.sad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificat « de confiance » pour un NAS qui utilise son certificat d'origine (non reconnu par Android),
 * comme dans Planning Commandes : l'utilisateur accepte UNE empreinte SHA-256, gardée par serveur,
 * et seul ce certificat-là est ensuite accepté en plus des certificats normalement reconnus.
 */
public class Tls {

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tac_sad", Context.MODE_PRIVATE);
    }

    private static String hostKey(URL u) {
        return u.getHost().toLowerCase() + ":" + (u.getPort() < 0 ? 443 : u.getPort());
    }

    static String pinFor(Context c, URL u) {
        try {
            return new JSONObject(prefs(c).getString("tls_pins", "{}")).optString(hostKey(u), "");
        } catch (Exception e) {
            return "";
        }
    }

    static void trust(Context c, String url, String fp) {
        try {
            URL u = new URL(url.startsWith("https://") ? url : "https://" + url);
            JSONObject o = new JSONObject(prefs(c).getString("tls_pins", "{}"));
            o.put(hostKey(u), fp);
            prefs(c).edit().putString("tls_pins", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Vrai seulement si l'échec vient du certificat (et pas d'une connexion coupée par le serveur). */
    static boolean isCertProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.security.cert.CertificateException || t instanceof java.security.cert.CertPathValidatorException
                    || t instanceof javax.net.ssl.SSLPeerUnverifiedException) return true;
            String m = String.valueOf(t.getMessage()).toLowerCase();
            if (m.contains("trust anchor") || m.contains("certificate") || m.contains("hostname")) return true;
        }
        return false;
    }

    /** Message clair pour une erreur réseau / TLS. */
    static String explain(Throwable e) {
        if (e instanceof javax.net.ssl.SSLException || e instanceof java.net.SocketException || e instanceof java.io.EOFException) {
            if (isCertProblem(e)) return "certificat du serveur non reconnu";
            return "connexion coupée par le serveur. Ton téléphone est peut-être bloqué par le Synology "
                    + "(Panneau de configuration › Sécurité › Protection › Blocage auto › liste des blocages)";
        }
        if (e instanceof java.net.UnknownHostException) return "serveur introuvable (adresse ou connexion internet)";
        if (e instanceof java.net.ConnectException) return "le serveur refuse la connexion (adresse, port, ou téléphone bloqué par le Synology)";
        if (e instanceof java.net.SocketTimeoutException) return "le serveur ne répond pas";
        return null;
    }

    static String sha256(X509Certificate cert) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length; i++) {
            if (i > 0) b.append(':');
            b.append(String.format("%02X", h[i]));
        }
        return b.toString();
    }

    /** Applique l'empreinte acceptée (s'il y en a une) à cette connexion. */
    static void apply(Context c, HttpURLConnection h) {
        if (!(h instanceof HttpsURLConnection)) return;
        final String pin = pinFor(c, h.getURL());
        if (pin.isEmpty()) return;
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            final X509TrustManager def = (X509TrustManager) tmf.getTrustManagers()[0];
            X509TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    def.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        def.checkServerTrusted(chain, authType);
                    } catch (CertificateException e) {
                        try {
                            if (chain != null && chain.length > 0 && pin.equals(sha256(chain[0]))) return;
                        } catch (Exception ignored) {
                        }
                        throw e;
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return def.getAcceptedIssuers();
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            HttpsURLConnection hs = (HttpsURLConnection) h;
            hs.setSSLSocketFactory(ctx.getSocketFactory());
            final HostnameVerifier dv = HttpsURLConnection.getDefaultHostnameVerifier();
            hs.setHostnameVerifier((host, session) -> {
                if (dv.verify(host, session)) return true;
                try {
                    return pin.equals(sha256((X509Certificate) session.getPeerCertificates()[0]));
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * Lit le certificat présenté par le serveur, SANS rien lui envoyer (ni identifiant ni données) :
     * sert seulement à montrer son empreinte à l'utilisateur. "" si impossible.
     */
    static String peek(String url) {
        try {
            URL u = new URL(url);
            TrustManager[] all = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] a, String b) {
                }

                public void checkServerTrusted(X509Certificate[] a, String b) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, all, null);
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(u.getHost(), u.getPort() < 0 ? 443 : u.getPort()), 15000);
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, u.getHost(), u.getPort() < 0 ? 443 : u.getPort(), true)) {
                s.setSoTimeout(15000);
                s.startHandshake();
                return sha256((X509Certificate) s.getSession().getPeerCertificates()[0]);
            }
        } catch (Exception e) {
            return "";
        }
    }
}
