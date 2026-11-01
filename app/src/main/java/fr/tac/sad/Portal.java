package fr.tac.sad;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requêtes vers le portail famille avec les cookies de connexion de l'appli (partagés avec les pages web). */
public class Portal {

    /** {status, adresse finale, contenu}. Suit les redirections en gardant les cookies. */
    static String[] request(String method, String path, String body, String contentType, String csrf) {
        int status = 0;
        String text = "", finalUrl = "";
        try {
            if (LoginActivity.PORTAL.isEmpty()) return new String[]{"0", "", ""};
            String host = new URL(LoginActivity.PORTAL).getHost();
            String url = LoginActivity.PORTAL + path;
            String m = method, b = body;
            CookieManager cm = CookieManager.getInstance();
            for (int hop = 0; hop < 6; hop++) {
                URL u = new URL(url);
                if (!"https".equals(u.getProtocol()) || !host.equalsIgnoreCase(u.getHost())) break;   // uniquement le portail réglé
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestMethod(m);
                String ck = cm.getCookie(url);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                if (csrf != null && !csrf.isEmpty()) c.setRequestProperty("X-CSRF-TOKEN", csrf);
                if (b != null && !b.isEmpty()) {
                    c.setDoOutput(true);
                    if (contentType != null && !contentType.isEmpty()) c.setRequestProperty("Content-Type", contentType);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(b.getBytes(StandardCharsets.UTF_8));
                    }
                }
                status = c.getResponseCode();
                Map<String, List<String>> h = c.getHeaderFields();
                for (Map.Entry<String, List<String>> e : h.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                        for (String v : e.getValue()) cm.setCookie(url, v);
                    }
                }
                finalUrl = url;
                if (status >= 300 && status < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    m = "GET";
                    b = null;
                    c.disconnect();
                    continue;
                }
                InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                text = readAll(is);
                c.disconnect();
                break;
            }
            cm.flush();
        } catch (Exception e) {
            status = 0;
            text = "";
        }
        return new String[]{String.valueOf(status), finalUrl, text};
    }

    static String readAll(InputStream is) throws java.io.IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static boolean isPlanning(String[] r) {
        return "200".equals(r[0]) && r[2].contains("getEventsList");
    }

    static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** Créneaux de la page Planning (même lecture que la page de l'appli, sans exécuter le code du site). */
    static List<JSONObject> parseEvents(String html) {
        List<JSONObject> out = new ArrayList<>();
        String[] parts = html.split("eventItem\\s*=\\s*\\{");
        Pattern re = Pattern.compile("(\\w+)\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|-?\\d+(?:\\.\\d+)?|true|false|null)\\s*,");
        for (int i = 1; i < parts.length; i++) {
            String block = parts[i].split("\\};")[0];
            JSONObject ev = new JSONObject();
            Matcher m = re.matcher(block);
            try {
                while (m.find()) {
                    String v = m.group(2);
                    Object val;
                    if (v.startsWith("\"")) val = new JSONObject("{\"v\":" + v + "}").get("v");
                    else if (v.startsWith("'")) val = v.substring(1, v.length() - 1);
                    else if (v.equals("null")) val = JSONObject.NULL;
                    else if (v.equals("true") || v.equals("false")) val = Boolean.parseBoolean(v);
                    else val = v.contains(".") ? Double.parseDouble(v) : Long.parseLong(v);
                    ev.put(m.group(1), val);
                }
            } catch (Exception ignored) {
            }
            if (ev.has("start") && ev.has("title")) out.add(ev);
        }
        return out;
    }
}
