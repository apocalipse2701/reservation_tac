package fr.tac.sad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;

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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Covoiturage : un fichier partagé sur le WebDAV (Synology…), lu et écrit par plusieurs téléphones.
 * Fichier <dossier>/covoiturage-lea.bin : « CVL1 » + sel(16) + iv(12) + AES-256-GCM(gzip(JSON)),
 * clé tirée du code de covoiturage (PBKDF2-HMAC-SHA1, 150 000 tours).
 * Écriture prudente : PUT avec If-Match (ETag) ; si un autre téléphone a écrit entre-temps (412),
 * la page relit et refait sa modification.
 * Tâche de fond (~30 min) : relit le fichier, prévient des changements faits par les autres, met à jour le widget.
 */
public class Carpool extends JobService {

    private static final String SECRET = "carpool";
    private static final String FICHIER = "covoiturage-lea.bin";
    private static final byte[] MAGIQUE = {'C', 'V', 'L', '1'};
    private static final int TOURS = 150000;
    private static final int JOB_ID = 71, NOTIF_ID = 72;
    private static final String CANAL = "covoiturage";

    // clé dérivée gardée en mémoire (le calcul PBKDF2 prend ~1 s)
    private static byte[] cacheSel;
    private static String cacheCode;
    private static SecretKeySpec cacheCle;

    // ------------------------------------------------------------------ réglages

    static JSONObject secret(Context c) throws Exception {
        String s = Vault.getSecret(c, SECRET);
        return s == null ? null : new JSONObject(s);
    }

    /** Réglages sans les secrets : {actif, nom, id, adresse, identifiant, dossier, motDePasse(bool), code(bool)}. */
    static String settings(Context c) {
        try {
            JSONObject o = secret(c);
            if (o == null) return "{}";
            JSONObject p = new JSONObject();
            p.put("actif", true);
            for (String k : new String[]{"nom", "badge", "id", "adresse", "identifiant", "dossier"}) p.put(k, o.optString(k));
            p.put("motDePasse", !o.optString("motDePasse").isEmpty());
            p.put("code", !o.optString("code").isEmpty());
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
                c.getSharedPreferences("tac_sad", MODE_PRIVATE).edit().remove("carpool_401").apply();
                unschedule(c);
                return;
            }
            JSONObject o = secret(c);
            if (o == null) o = new JSONObject();
            if (o.optString("id").isEmpty()) o.put("id", randomId());
            for (String k : new String[]{"nom", "badge", "adresse", "identifiant", "dossier"}) o.put(k, n.optString(k, "").trim());
            for (String k : new String[]{"motDePasse", "code"}) {
                String v = n.optString(k, "");
                if (!v.isEmpty()) o.put(k, v);
            }
            Vault.putSecret(c, SECRET, o.toString());
            c.getSharedPreferences("tac_sad", MODE_PRIVATE).edit().remove("carpool_401").apply();
            synchronized (Carpool.class) {
                cacheCode = null;
            }
            schedule(c);
        } catch (Exception ignored) {
        }
    }

    private static String randomId() {
        String a = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(a.charAt(r.nextInt(a.length())));
        return b.toString();
    }

    /** Code de covoiturage proposé : 12 caractères faciles à recopier (XXXX-XXXX-XXXX). */
    static String newCode() {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) b.append('-');
            b.append(a.charAt(r.nextInt(a.length())));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ lecture / écriture

    private static String url(JSONObject o) throws IOException {
        return PlanningLink.racine(o.optString("adresse")) + segments(o.optString("dossier", "Covoiturage")) + FICHIER;
    }

    private static HttpURLConnection open(Context c, JSONObject o, String method) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url(o)).openConnection();
        Tls.apply(c, h);
        h.setConnectTimeout(20000);
        h.setReadTimeout(30000);
        h.setRequestMethod(method);
        String auth = o.optString("identifiant") + ":" + o.optString("motDePasse");
        h.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        return h;
    }

    /** {ok, status (200 ou 404 = pas encore créé), data (JSON en clair), etag} ou {ok:false, erreur}. */
    static String fetch(Context c) {
        JSONObject r = new JSONObject();
        try {
            JSONObject o = secret(c);
            if (o == null) throw new IOException("covoiturage non réglé");
            guard(c);
            HttpURLConnection h = open(c, o, "GET");
            int code = h.getResponseCode();
            refused(c, code);
            if (code == 404) {
                r.put("ok", true).put("status", 404).put("data", "").put("etag", "");
                h.disconnect();
                return r.toString();
            }
            check(code, "lecture");
            byte[] d;
            try (InputStream in = h.getInputStream()) {
                d = readAll(in, 10 * 1024 * 1024);
            }
            String etag = h.getHeaderField("ETag");
            h.disconnect();
            String json = new String(decrypt(d, o.optString("code")), StandardCharsets.UTF_8);
            c.getSharedPreferences("tac_sad", MODE_PRIVATE).edit().putString("carpool_cache", json).apply();
            r.put("ok", true).put("status", 200).put("data", json).put("etag", etag == null ? "" : etag);
        } catch (Exception e) {
            error(r, e);
            certInfo(c, r, e);
        }
        return r.toString();
    }

    /** arg = {data, etag} ; {ok, status, etag} ; status 412 = quelqu'un a écrit entre-temps. */
    static String put(Context c, String arg) {
        JSONObject r = new JSONObject();
        try {
            JSONObject a = new JSONObject(arg);
            JSONObject o = secret(c);
            if (o == null) throw new IOException("covoiturage non réglé");
            guard(c);
            byte[] body = encrypt(a.optString("data").getBytes(StandardCharsets.UTF_8), o.optString("code"));
            HttpURLConnection h = open(c, o, "PUT");
            String etag = a.optString("etag", "");
            if (!etag.isEmpty()) h.setRequestProperty("If-Match", etag);
            else if (a.optBoolean("creer")) h.setRequestProperty("If-None-Match", "*");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/octet-stream");
            h.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = h.getOutputStream()) {
                out.write(body);
            }
            int code = h.getResponseCode();
            String newTag = h.getHeaderField("ETag");
            refused(c, code);
            h.disconnect();
            if (code == 412) {
                r.put("ok", false).put("status", 412);
                return r.toString();
            }
            if (code == 409 || code == 404) throw new IOException("le dossier « " + o.optString("dossier", "Covoiturage")
                    + " » n'existe pas sur le Synology (ou n'a pas exactement ce nom). Crée-le dans File Station, ou corrige le nom dans ⚙️ Réglages");
            if (code == 403) throw new IOException("le compte « " + o.optString("identifiant") + " » n'a pas le droit d'écrire dans le dossier « "
                    + o.optString("dossier", "Covoiturage") + " » (Panneau de configuration › Dossier partagé › Permissions : Lecture/Écriture)");
            if (code == 405) throw new IOException("le Synology refuse l'enregistrement : le dossier partagé « " + o.optString("dossier", "Covoiturage")
                    + " » n'existe pas, n'a pas exactement ce nom (majuscules comprises), ou le compte « " + o.optString("identifiant")
                    + " » n'y a pas accès en Lecture/Écriture (Panneau de configuration › Dossier partagé)");
            check(code, "enregistrement");
            c.getSharedPreferences("tac_sad", MODE_PRIVATE).edit().putString("carpool_cache", a.optString("data")).apply();
            r.put("ok", true).put("status", code).put("etag", newTag == null ? "" : newTag);
            updateWidget(c, a.optString("data"));
        } catch (Exception e) {
            error(r, e);
            certInfo(c, r, e);
        }
        return r.toString();
    }

    static final String BLOCKED_MSG = "identifiant ou mot de passe refusé par le Synology. Corrige-les dans ⚙️ Réglages : "
            + "l'appli ne réessaie plus d'ici là, pour que le Synology ne bloque pas ton téléphone.";

    private static void guard(Context c) throws IOException {
        if (c.getSharedPreferences("tac_sad", MODE_PRIVATE).getBoolean("carpool_401", false)) throw new IOException(BLOCKED_MSG);
    }

    private static void refused(Context c, int code) throws IOException {
        if (code == 401) {
            c.getSharedPreferences("tac_sad", MODE_PRIVATE).edit().putBoolean("carpool_401", true).apply();
            throw new IOException(BLOCKED_MSG);
        }
    }

    private static void check(int code, String what) throws IOException {
        if (code >= 200 && code < 300) return;
        if (code == 401) throw new IOException("identifiant ou mot de passe refusé par le serveur");
        if (code == 403) throw new IOException("accès refusé à ce dossier (droits du compte sur le NAS)");
        if (code == 404) throw new IOException("dossier introuvable sur le serveur");
        throw new IOException("réponse " + code + " du serveur (" + what + ")");
    }

    /** Certificat refusé : on joint l'empreinte présentée par le serveur, pour la proposer à l'utilisateur. */
    static void certInfo(Context c, JSONObject r, Exception e) {
        if (!(e instanceof javax.net.ssl.SSLException) || !Tls.isCertProblem(e)) return;
        try {
            JSONObject o = secret(c);
            String root = PlanningLink.racine(o.optString("adresse"));
            r.put("certificat", Tls.peek(root));
            r.put("serveur", root);
        } catch (Exception ignored) {
        }
    }

    private static void error(JSONObject r, Exception e) {
        try {
            String m = Tls.explain(e);
            if (m == null) m = e.getMessage();
            r.put("ok", false).put("erreur", m == null ? e.getClass().getSimpleName() : m);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ chiffrement

    private static synchronized SecretKeySpec key(String code, byte[] sel) throws Exception {
        if (cacheCle != null && code.equals(cacheCode) && Arrays.equals(sel, cacheSel)) return cacheCle;
        String norm = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(new PBEKeySpec(norm.toCharArray(), sel, TOURS, 256)).getEncoded();
        cacheCode = code;
        cacheSel = sel.clone();
        cacheCle = new SecretKeySpec(k, "AES");
        return cacheCle;
    }

    static byte[] encrypt(byte[] clear, String code) throws Exception {
        byte[] sel;
        synchronized (Carpool.class) {
            sel = (cacheSel != null && code.equals(cacheCode)) ? cacheSel.clone() : null;
        }
        if (sel == null) {
            sel = new byte[16];
            new SecureRandom().nextBytes(sel);
        }
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(code, sel), new GCMParameterSpec(128, iv));
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(gz)) {
            z.write(clear);
        }
        byte[] ct = c.doFinal(gz.toByteArray());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(MAGIQUE);
        b.write(sel);
        b.write(iv);
        b.write(ct);
        return b.toByteArray();
    }

    static byte[] decrypt(byte[] d, String code) throws IOException {
        if (d.length < 4 + 16 + 12 + 16) throw new IOException("fichier de covoiturage abîmé");
        for (int i = 0; i < 4; i++) if (d[i] != MAGIQUE[i]) throw new IOException("ce n'est pas un fichier de covoiturage");
        byte[] sel = Arrays.copyOfRange(d, 4, 20), iv = Arrays.copyOfRange(d, 20, 32);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(code, sel), new GCMParameterSpec(128, iv));
            byte[] gz = c.doFinal(d, 32, d.length - 32);
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                return readAll(in, 10 * 1024 * 1024);
            }
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IOException("code de covoiturage incorrect");
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

    // ------------------------------------------------------------------ widget

    /** Écrit dans les données du widget : jour -> « Maman 17:00 » / « ? 17:00 ». */
    static void updateWidget(Context c, String json) {
        try {
            JSONObject d = new JSONObject(json);
            JSONObject days = d.optJSONObject("days"), members = d.optJSONObject("members"), defs = d.optJSONObject("defaults");
            SharedPreferences p = c.getSharedPreferences("tac_sad", MODE_PRIVATE);
            JSONObject w = new JSONObject(p.getString("widget_cal", "{}"));
            JSONObject cov = new JSONObject();
            Calendar cal = Calendar.getInstance();
            for (int i = 0; i < 21; i++, cal.add(Calendar.DAY_OF_MONTH, 1)) {
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue;
                String k = String.format(Locale.FRANCE, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
                JSONObject day = days == null ? null : days.optJSONObject(k);
                if (day != null && day.optBoolean("off")) continue;
                String h = day != null ? day.optString("heure", "") : "";
                String qui = day != null ? day.optString("qui", "") : "";
                JSONObject edt = d.optJSONObject("edt");
                if (h.isEmpty() && edt != null) {
                    if (edt.optBoolean("vac", true) && noSchool(k)) { if (qui.isEmpty()) continue; }
                    else h = edtHour(edt, cal, dow - 1);
                } else if (h.isEmpty() && defs != null) h = defs.optString(String.valueOf(dow == Calendar.SUNDAY ? 7 : dow - 1), "");
                String nom = "?";
                if (!qui.isEmpty() && members != null && members.optJSONObject(qui) != null) nom = members.optJSONObject(qui).optString("nom", "?");
                if (h.isEmpty() && qui.isEmpty()) continue;
                cov.put(k, nom);
            }
            w.put("covoit", cov);
            org.json.JSONArray noms = new org.json.JSONArray();
            if (members != null) for (Iterator<String> it = members.keys(); it.hasNext(); ) {
                JSONObject m = members.optJSONObject(it.next());
                if (m != null && !m.optString("nom").isEmpty())
                    noms.put(new JSONObject().put("nom", m.optString("nom")).put("badge", m.optString("badge", "")));
            }
            w.put("covoitNoms", noms);
            p.edit().putString("widget_cal", w.toString()).apply();
            CalendarWidget.refreshAll(c);
        } catch (Exception ignored) {
        }
    }

    /** Vacances scolaires zone B et jours fériés en semaine (mêmes dates que l'écran Covoiturage). */
    private static final String[][] VACANCES = {{"2026-10-17", "2026-11-01"}, {"2026-12-19", "2027-01-03"}, {"2027-02-20", "2027-03-07"},
            {"2027-04-17", "2027-05-02"}, {"2027-05-06", "2027-05-09"}, {"2027-07-03", "2027-08-31"}};
    private static final String FERIES = "2026-11-11 2027-03-29 2027-05-17";

    static boolean noSchool(String k) {
        if (FERIES.contains(k)) return true;
        for (String[] v : VACANCES) if (k.compareTo(v[0]) >= 0 && k.compareTo(v[1]) <= 0) return true;
        return false;
    }

    /** Heure de sortie d'après l'emploi du temps (semaines A/B alternées). */
    static String edtHour(JSONObject edt, Calendar day, int n) {
        try {
            String[] r = edt.optString("ref").split("-");
            Calendar ref = Calendar.getInstance();
            ref.clear(); ref.set(Integer.parseInt(r[0]), Integer.parseInt(r[1]) - 1, Integer.parseInt(r[2]), 12, 0);
            Calendar mon = (Calendar) day.clone();
            mon.set(Calendar.HOUR_OF_DAY, 12); mon.set(Calendar.MINUTE, 0); mon.set(Calendar.SECOND, 0); mon.set(Calendar.MILLISECOND, 0);
            mon.add(Calendar.DAY_OF_MONTH, -(n - 1));
            long w = Math.round((mon.getTimeInMillis() - ref.getTimeInMillis()) / 864e5 / 7);
            boolean a = (w % 2 == 0) == !"B".equals(edt.optString("refL"));
            JSONObject A = edt.optJSONObject("A"), B = edt.optJSONObject("B");
            String k = String.valueOf(n), h = "";
            if (!a && B != null) h = B.optString(k, "");
            if (h.isEmpty() && A != null) h = A.optString(k, "");
            return h;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ tâche de fond et notifications

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, Carpool.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Throwable t) {
            CrashLog.save(c, t);
        }
    }

    static void unschedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    /** Changements déjà vus sur ce téléphone (la page l'appelle après chaque lecture). */
    static void markSeen(Context c, long at) {
        SharedPreferences p = c.getSharedPreferences("tac_sad", MODE_PRIVATE);
        if (at > p.getLong("carpool_seen", 0)) p.edit().putLong("carpool_seen", at).apply();
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        CrashLog.install(getApplicationContext());
        new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (Throwable t) {
                CrashLog.save(getApplicationContext(), t);
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static void check(Context c) throws Exception {
        JSONObject r = new JSONObject(fetch(c));
        if (!r.optBoolean("ok") || r.optInt("status") != 200) return;
        String json = r.optString("data");
        updateWidget(c, json);
        JSONObject me = secret(c);
        String myId = me == null ? "" : me.optString("id");
        SharedPreferences p = c.getSharedPreferences("tac_sad", MODE_PRIVATE);
        long seen = p.getLong("carpool_seen", 0), max = seen;
        JSONObject days = new JSONObject(json).optJSONObject("days");
        if (days == null) return;
        List<String> lines = new ArrayList<>();
        for (Iterator<String> it = days.keys(); it.hasNext(); ) {
            JSONObject day = days.optJSONObject(it.next());
            JSONObject maj = day == null ? null : day.optJSONObject("maj");
            if (maj == null) continue;
            long at = maj.optLong("at");
            if (at > seen && !myId.equals(maj.optString("by"))) {
                lines.add(maj.optString("txt"));
                if (at > max) max = at;
            }
        }
        if (lines.isEmpty()) return;
        p.edit().putLong("carpool_seen", max).apply();
        notify(c, lines);
    }

    private static void notify(Context c, List<String> lines) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Covoiturage", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra("page", "covoit");
        PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = String.join("\n", lines);
        Notification n = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Covoiturage pour " + Perso.covKids(c))
                .setContentText(lines.size() == 1 ? lines.get(0) : lines.size() + " changements")
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID, n);
    }
}
