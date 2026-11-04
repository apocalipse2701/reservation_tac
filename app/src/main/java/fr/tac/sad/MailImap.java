package fr.tac.sad;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Repris de Planning Commandes (CourrierImap) : lecture d'une boîte mail en IMAP, ici pour les réponses
 * de la compagnie de transport aux demandes de bus. Ajout : texte des messages (Mime.Message.texte).
 * Lecture des bulletins de commande dans une boîte mail, en IMAP (Free : imap.free.fr, port 993, SSL).
 * Ne fait que LIRE : les messages ne sont ni marqués lus (BODY.PEEK), ni déplacés, ni supprimés.
 *
 *   1. connexion SSL, LOGIN adresse complète + mot de passe ;
 *   2. SELECT INBOX (lecture de UIDVALIDITY) ;
 *   3. UID SEARCH des messages des expéditeurs choisis (au premier passage : des JOURS_DEPART derniers jours ;
 *      ensuite : ceux dont l'UID dépasse le dernier vu) ;
 *   4. UID FETCH du message entier, puis extraction des pièces jointes PDF (MIME, base64 / quoted-printable).
 * Deux conditions pour garder un PDF : l'expéditeur est EXACTEMENT l'un des expéditeurs choisis (la recherche du
 * serveur accepte aussi une adresse qui les contient) et le nom du fichier contient « bulletin de commande »
 * ou « contrairement » (NOMS_PDF).
 *
 * Aucune bibliothèque : IMAP et MIME sont écrits ici (réponses simples, littéraux {n}).
 */
final class MailImap {

    static final int JOURS_DEPART = 30;
    /** Le nom de la pièce jointe doit contenir l'un de ces mots (sans majuscules, accents, « _ » ni « - »).
     *  Mêmes valeurs que MAIL_NOMS_PDF (planning_pdf.py). */
    static final String[] NOMS_PDF = {"bulletin de commande", "contrairement"};
    private static final int DELAI_MS = 30000;
    private static final int TAILLE_MAX = 25 * 1024 * 1024;     // message de plus de 25 Mo : ignoré

    /** Pièce jointe PDF trouvée. */
    static final class Piece {
        final long uid;
        final String nom, expediteur, date;
        final byte[] octets;
        /** Pour répondre (confirmation de réception) : adresse de réponse, Message-ID et sujet du mail reçu. */
        String repondreA = "", messageId = "", sujet = "";

        Piece(long uid, String nom, String expediteur, String date, byte[] octets) {
            this.uid = uid;
            this.nom = nom;
            this.expediteur = expediteur;
            this.date = date;
            this.octets = octets;
        }
    }

    /** Résultat d'un relevé. */
    static final class Resultat {
        final List<Piece> pieces = new ArrayList<>();
        long uidValidite, dernierUid;
        int messagesVus;
    }

    private final SocketFactory fabrique;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int numero = 0;

    MailImap() {
        this(SSLSocketFactory.getDefault());
    }

    /** Pour les essais : fabrique de sockets en clair. */
    MailImap(SocketFactory fabrique) {
        this.fabrique = fabrique;
    }

    // ------------------------------------------------------------------ relevé

    /**
     * @param uidValidite / dernierUid : état gardé du dernier relevé (0 = premier relevé)
     * @param expediteurs adresses (ou morceaux d'adresse) des expéditeurs des commandes
     */
    Resultat relever(String serveur, int port, String adresse, String motDePasse, List<String> expediteurs,
                     long uidValidite, long dernierUid) throws IOException {
        if (expediteurs.isEmpty()) throw new IOException("aucun expéditeur indiqué");
        Resultat r = new Resultat();
        connecter(serveur, port);
        try {
            lireLigne();                                                   // * OK accueil
            List<String> rep = commande("LOGIN " + quoter(adresse) + " " + quoter(motDePasse), null);
            if (!ok(rep)) throw new IOException("connexion refusée : adresse ou mot de passe incorrect");
            rep = commande("SELECT INBOX", null);
            if (!ok(rep)) throw new IOException("boîte de réception introuvable");
            long validite = 0;
            for (String l : rep) {
                Matcher m = Pattern.compile("UIDVALIDITY (\\d+)", Pattern.CASE_INSENSITIVE).matcher(l);
                if (m.find()) validite = Long.parseLong(m.group(1));
            }
            boolean neuf = uidValidite == 0 || validite != uidValidite;
            r.uidValidite = validite;
            r.dernierUid = neuf ? 0 : dernierUid;

            StringBuilder critere = new StringBuilder();
            for (int i = 0; i < expediteurs.size() - 1; i++) critere.append("OR ");
            for (String e : expediteurs) critere.append("FROM ").append(quoter(e)).append(' ');
            String recherche = neuf
                    ? "UID SEARCH SINCE " + dateImap(new Date(System.currentTimeMillis() - JOURS_DEPART * 86400000L)) + " " + critere.toString().trim()
                    : "UID SEARCH UID " + (dernierUid + 1) + ":* " + critere.toString().trim();
            rep = commande(recherche, null);
            if (!ok(rep)) throw new IOException("recherche refusée par le serveur");
            List<Long> uids = new ArrayList<>();
            for (String l : rep) {
                if (!l.toUpperCase(Locale.ROOT).startsWith("* SEARCH")) continue;
                for (String x : l.substring(8).trim().split("\\s+")) {
                    if (x.isEmpty()) continue;
                    long u = Long.parseLong(x);
                    if (u > r.dernierUid || neuf) uids.add(u);              // « n:* » renvoie toujours le dernier
                }
            }
            long max = r.dernierUid;
            for (long uid : uids) {
                if (!neuf && uid <= dernierUid) continue;
                byte[][] corps = new byte[1][];
                rep = commande("UID FETCH " + uid + " BODY.PEEK[]", corps);
                max = Math.max(max, uid);
                if (!ok(rep) || corps[0] == null) continue;
                r.messagesVus++;
                Mime.Message msg = Mime.lire(corps[0]);
                if (!expediteurAccepte(msg.de, expediteurs)) continue;
                for (Mime.Partie p : msg.pdf) {
                    if (!nomAccepte(p.nom)) continue;                       // annexe, autre document…
                    Piece piece = new Piece(uid, p.nom.isEmpty() ? "commande-" + uid + ".pdf" : p.nom, msg.de, msg.date, p.octets);
                    piece.repondreA = adresse(msg.repondreA.isEmpty() ? msg.de : msg.repondreA);
                    piece.messageId = msg.messageId;
                    piece.sujet = msg.sujet;
                    r.pieces.add(piece);
                }
            }
            r.dernierUid = max;
            try {
                commande("LOGOUT", null);
            } catch (IOException e) {
                // le serveur ferme parfois avant de répondre
            }
        } finally {
            fermer();
        }
        return r;
    }

    // ------------------------------------------------------------------ accusés de réception des DII envoyées

    /** Message reçu qui cite une DII envoyée, ou venu de son destinataire. */
    static final class Retour {
        final long uid;
        final byte[] brut;

        Retour(long uid, byte[] brut) {
            this.uid = uid;
            this.brut = brut;
        }
    }

    /**
     * Pour chaque Message-ID (clé de « depuis » : date d'envoi en ms) : les messages de la boîte de réception qui le
     * citent (accusés, réponses) ou qui viennent de son destinataire (clé de « de ») depuis l'envoi — avis de
     * l'établissement en pièce jointe. 25 au plus par envoi. Lecture seule (EXAMINE, BODY.PEEK).
     */
    java.util.Map<String, List<Retour>> retours(String serveur, int port, String adresse, String motDePasse,
                                                java.util.Map<String, Long> depuis, java.util.Map<String, String> de) throws IOException {
        java.util.Map<String, List<Retour>> res = new java.util.HashMap<>();
        connecter(serveur, port);
        try {
            lireLigne();
            List<String> rep = commande("LOGIN " + quoter(adresse) + " " + quoter(motDePasse), null);
            if (!ok(rep)) throw new IOException("connexion refusée : adresse ou mot de passe incorrect");
            rep = commande("EXAMINE INBOX", null);
            if (!ok(rep)) throw new IOException("boîte de réception introuvable");
            for (java.util.Map.Entry<String, Long> e : depuis.entrySet()) {
                String cle = e.getKey().replaceAll("[<>\"\\\\]", "");
                String date = dateImap(new Date(e.getValue() - 86400000L));
                java.util.TreeSet<Long> uids = new java.util.TreeSet<>();
                List<String> criteres = new ArrayList<>();
                criteres.add("TEXT " + quoter(cle));
                String dest = de == null ? "" : de.get(e.getKey());
                if (dest != null && !dest.trim().isEmpty()) criteres.add("FROM " + quoter(dest.trim()));
                for (String critere : criteres) {
                    rep = commande("UID SEARCH SINCE " + date + " " + critere, null);
                    if (!ok(rep)) continue;
                    for (String x : rep) {
                        if (!x.toUpperCase(Locale.ROOT).startsWith("* SEARCH")) continue;
                        for (String u : x.substring(8).trim().split("\\s+")) if (!u.isEmpty()) uids.add(Long.parseLong(u));
                    }
                }
                List<Retour> l = new ArrayList<>();
                res.put(e.getKey(), l);
                List<Long> liste = new ArrayList<>(uids);
                if (liste.size() > 25) liste = liste.subList(liste.size() - 25, liste.size());
                for (long uid : liste) {
                    byte[][] corps = new byte[1][];
                    rep = commande("UID FETCH " + uid + " BODY.PEEK[]", corps);
                    if (ok(rep) && corps[0] != null) l.add(new Retour(uid, corps[0]));
                }
            }
            try {
                commande("LOGOUT", null);
            } catch (IOException e) {
                // le serveur ferme parfois avant de répondre
            }
        } finally {
            fermer();
        }
        return res;
    }

    /**
     * Messages des expéditeurs donnés (service DII) reçus depuis « depuis » (ms) et pas encore vus (« deja » : UID ;
     * vidé si UIDVALIDITY a changé, nouvelle valeur dans validite[0]). Lecture seule.
     */
    List<Retour> avis(String serveur, int port, String adresse, String motDePasse, List<String> expediteurs, long depuis,
                      long[] validite, java.util.Set<Long> deja) throws IOException {
        List<Retour> l = new ArrayList<>();
        connecter(serveur, port);
        try {
            lireLigne();
            List<String> rep = commande("LOGIN " + quoter(adresse) + " " + quoter(motDePasse), null);
            if (!ok(rep)) throw new IOException("connexion refusée : adresse ou mot de passe incorrect");
            rep = commande("EXAMINE INBOX", null);
            if (!ok(rep)) throw new IOException("boîte de réception introuvable");
            long v = 0;
            for (String x : rep) {
                Matcher m = Pattern.compile("UIDVALIDITY (\\d+)", Pattern.CASE_INSENSITIVE).matcher(x);
                if (m.find()) v = Long.parseLong(m.group(1));
            }
            if (v != validite[0]) deja.clear();
            validite[0] = v;
            java.util.TreeSet<Long> uids = new java.util.TreeSet<>();
            for (String e : expediteurs) {
                rep = commande("UID SEARCH SINCE " + dateImap(new Date(depuis)) + " FROM " + quoter(e), null);
                if (!ok(rep)) continue;
                for (String x : rep) {
                    if (!x.toUpperCase(Locale.ROOT).startsWith("* SEARCH")) continue;
                    for (String u : x.substring(8).trim().split("\\s+")) if (!u.isEmpty()) uids.add(Long.parseLong(u));
                }
            }
            for (long uid : uids) {
                if (deja.contains(uid)) continue;
                byte[][] corps = new byte[1][];
                rep = commande("UID FETCH " + uid + " BODY.PEEK[]", corps);
                if (ok(rep) && corps[0] != null) l.add(new Retour(uid, corps[0]));
                if (l.size() >= 30) break;                                  // le reste au prochain relevé
            }
            try {
                commande("LOGOUT", null);
            } catch (IOException e) {
                // le serveur ferme parfois avant de répondre
            }
        } finally {
            fermer();
        }
        return l;
    }

    // ------------------------------------------------------------------ « Trouver les expéditeurs »

    /** Expéditeur des bulletins trouvé dans la boîte. */
    static final class Expediteur {
        final String adresse;
        String nom = "", dernier = "";
        int nb;
        long derniereFois;

        Expediteur(String adresse) {
            this.adresse = adresse;
        }
    }

    /** Recherche des 12 derniers mois et des 500 derniers messages (planning_pdf.py : MAIL_JOURS/MAX_RECHERCHE). */
    static final int JOURS_RECHERCHE = 365, MAX_RECHERCHE = 500;

    /**
     * Expéditeurs des messages récents qui portent un bulletin de commande, du plus fréquent au moins fréquent.
     * Lecture seule : seuls la structure (BODYSTRUCTURE) et l'en-tête From/Date sont lus, rien n'est téléchargé.
     */
    List<Expediteur> chercherExpediteurs(String serveur, int port, String adresse, String motDePasse) throws IOException {
        java.util.Map<String, Expediteur> trouves = new java.util.LinkedHashMap<>();
        connecter(serveur, port);
        try {
            lireLigne();
            List<String> rep = commande("LOGIN " + quoter(adresse) + " " + quoter(motDePasse), null);
            if (!ok(rep)) throw new IOException("connexion refusée : adresse ou mot de passe incorrect");
            rep = commande("EXAMINE INBOX", null);                       // lecture seule
            if (!ok(rep)) throw new IOException("boîte de réception introuvable");
            rep = commande("UID SEARCH SINCE " + dateImap(new Date(System.currentTimeMillis() - JOURS_RECHERCHE * 86400000L)), null);
            if (!ok(rep)) throw new IOException("recherche refusée par le serveur");
            List<Long> uids = new ArrayList<>();
            for (String l : rep) {
                if (!l.toUpperCase(Locale.ROOT).startsWith("* SEARCH")) continue;
                for (String x : l.substring(8).trim().split("\\s+")) if (!x.isEmpty()) uids.add(Long.parseLong(x));
            }
            java.util.Collections.sort(uids);
            if (uids.size() > MAX_RECHERCHE) uids = new ArrayList<>(uids.subList(uids.size() - MAX_RECHERCHE, uids.size()));
            List<Long> retenus = new ArrayList<>();
            for (int i = 0; i < uids.size(); i += 100) {
                List<String> lignes = commandeComplete("UID FETCH " + liste(uids.subList(i, Math.min(uids.size(), i + 100)))
                        + " (UID BODYSTRUCTURE)");
                for (String l : lignes) {
                    Matcher m = Pattern.compile("\\bUID (\\d+)").matcher(l);
                    if (l.startsWith("* ") && m.find() && structureCommande(l)) retenus.add(Long.parseLong(m.group(1)));
                }
            }
            for (int i = 0; i < retenus.size(); i += 100) {
                List<String> lignes = commandeComplete("UID FETCH " + liste(retenus.subList(i, Math.min(retenus.size(), i + 100)))
                        + " (UID BODY.PEEK[HEADER.FIELDS (FROM DATE)])");
                for (String l : lignes) {
                    int a = l.indexOf('\u0001'), b = l.indexOf('\u0002');
                    if (!l.startsWith("* ") || a < 0 || b < a) continue;
                    String entetes = l.substring(a + 1, b).replaceAll("\r?\n[ \t]+", " ");
                    Matcher de = Pattern.compile("(?im)^From:\\s*(.*)$").matcher(entetes);
                    if (!de.find()) continue;
                    String brut = Mime.motsEncodes(de.group(1).trim());
                    String adr = adresse(brut);
                    if (!adr.contains("@") || adr.contains(" ")) continue;
                    Expediteur e = trouves.get(adr);
                    if (e == null) trouves.put(adr, e = new Expediteur(adr));
                    e.nb++;
                    int chevron = brut.indexOf('<');
                    if (e.nom.isEmpty() && chevron > 0) e.nom = brut.substring(0, chevron).replace("\"", "").trim();
                    Matcher dt = Pattern.compile("(?im)^Date:\\s*(.*)$").matcher(entetes);
                    if (dt.find()) {
                        long t = dateMessage(dt.group(1).trim());
                        if (t > e.derniereFois) {
                            e.derniereFois = t;
                            e.dernier = new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date(t));
                        }
                    }
                }
            }
            try {
                commande("LOGOUT", null);
            } catch (IOException e) {
                // le serveur ferme parfois avant de répondre
            }
        } finally {
            fermer();
        }
        List<Expediteur> l = new ArrayList<>(trouves.values());
        java.util.Collections.sort(l, (x, y) -> x.nb != y.nb ? y.nb - x.nb : x.adresse.compareTo(y.adresse));
        return l;
    }

    private static String liste(List<Long> uids) {
        StringBuilder b = new StringBuilder();
        for (long u : uids) b.append(b.length() == 0 ? "" : ",").append(u);
        return b.toString();
    }

    static long dateMessage(String d) {
        String[] formats = {"EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z"};
        String propre = d.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
        for (String f : formats) {
            try {
                Date x = new SimpleDateFormat(f, Locale.ENGLISH).parse(propre);
                if (x != null) return x.getTime();
            } catch (java.text.ParseException e) {
                // format suivant
            }
        }
        return 0;
    }

    /** Chaînes d'une structure IMAP (entre guillemets ou littéraux), décodées : =?utf-8?…?= et utf-8''x%20y (RFC 2231). */
    static List<String> chaines(String texte) {
        List<String> l = new ArrayList<>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|\u0001([^\u0002]*)\u0002").matcher(texte);
        while (m.find()) {
            String s = m.group(1) != null ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : m.group(2);
            if (s.contains("=?")) s = Mime.motsEncodes(s);
            Matcher r = Pattern.compile("^([\\w-]+)'[\\w-]*'(.*)$").matcher(s);
            if (r.find()) {
                try {
                    s = java.net.URLDecoder.decode(r.group(2).replace("+", "%2B"), r.group(1));
                } catch (Exception e) {
                    s = r.group(2);
                }
            }
            l.add(s);
        }
        return l;
    }

    /** La structure du message contient un PDF « bulletin de commande » / « contrairement ». */
    static boolean structureCommande(String texte) {
        if (!texte.toLowerCase(Locale.ROOT).contains("pdf")) return false;
        for (String s : chaines(texte)) if (nomAccepte(s)) return true;
        return false;
    }

    /** Comme commande(), mais chaque réponse « * … » est rendue en une seule ligne, littéraux compris
     *  (entre \u0001 et \u0002) : plusieurs messages par FETCH. */
    private List<String> commandeComplete(String cmd) throws IOException {
        String etiquette = "a" + (++numero);
        out.write((etiquette + " " + cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        List<String> lignes = new ArrayList<>();
        StringBuilder courante = new StringBuilder();
        while (true) {
            String l = lireLigne();
            Matcher m = Pattern.compile("\\{(\\d+)\\}$").matcher(l);
            if (m.find()) {
                long n = Long.parseLong(m.group(1));
                if (n > TAILLE_MAX) throw new IOException("réponse trop grosse");
                byte[] b = lireOctets((int) n);
                courante.append(l, 0, m.start()).append('\u0001')
                        .append(new String(b, StandardCharsets.UTF_8).replace('\u0001', ' ').replace('\u0002', ' ')).append('\u0002');
                continue;
            }
            courante.append(l);
            String complete = courante.toString();
            courante.setLength(0);
            lignes.add(complete);
            if (complete.startsWith(etiquette + " ")) return lignes;
        }
    }

    // ------------------------------------------------------------------ conditions

    /** Adresse d'un en-tête From : « Nom <adresse> » → adresse, en minuscules. */
    static String adresse(String de) {
        Matcher m = Pattern.compile("<([^>]+)>").matcher(de);
        String a = m.find() ? m.group(1) : de;
        return a.trim().replace("\"", "").toLowerCase(Locale.ROOT);
    }

    /** L'expéditeur est exactement l'un de ceux choisis. */
    static boolean expediteurAccepte(String de, List<String> expediteurs) {
        String a = adresse(de);
        for (String e : expediteurs) if (a.equals(e.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** Minuscules, sans accents, « _ - . » remplacés par des espaces. */
    static String normaliser(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[_\\-.]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** Le nom du PDF contient « bulletin de commande » ou « contrairement ». */
    static boolean nomAccepte(String nom) {
        String n = normaliser(nom);
        for (String mot : NOMS_PDF) if (n.contains(mot)) return true;
        return false;
    }

    // ------------------------------------------------------------------ IMAP

    private void connecter(String serveur, int port) throws IOException {
        socket = fabrique.createSocket();
        socket.connect(new InetSocketAddress(serveur, port), DELAI_MS);
        socket.setSoTimeout(DELAI_MS);
        if (socket instanceof SSLSocket) ((SSLSocket) socket).startHandshake();
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    private void fermer() {
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            // déjà fermé
        }
    }

    /** Envoie une commande ; renvoie les lignes de réponse (la dernière = statut). Littéral éventuel → corps[0]. */
    private List<String> commande(String cmd, byte[][] corps) throws IOException {
        String etiquette = "a" + (++numero);
        out.write((etiquette + " " + cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        List<String> lignes = new ArrayList<>();
        while (true) {
            String l = lireLigne();
            Matcher m = Pattern.compile("\\{(\\d+)\\}$").matcher(l);
            if (m.find()) {                                                 // littéral {n} : n octets bruts
                long n = Long.parseLong(m.group(1));
                if (n > TAILLE_MAX) throw new IOException("message trop gros");
                byte[] b = lireOctets((int) n);
                if (corps != null && corps[0] == null) corps[0] = b;
                lignes.add(l);
                continue;
            }
            lignes.add(l);
            if (l.startsWith(etiquette + " ")) return lignes;
        }
    }

    private static boolean ok(List<String> rep) {
        String l = rep.get(rep.size() - 1);
        int i = l.indexOf(' ');
        return i > 0 && l.substring(i + 1).toUpperCase(Locale.ROOT).startsWith("OK");
    }

    private String lireLigne() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 1_000_000) throw new IOException("réponse du serveur illisible");
        }
        if (c == -1 && b.size() == 0) throw new IOException("connexion coupée par le serveur");
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] lireOctets(int n) throws IOException {
        byte[] b = new byte[n];
        int lu = 0;
        while (lu < n) {
            int k = in.read(b, lu, n - lu);
            if (k < 0) throw new IOException("connexion coupée pendant le téléchargement");
            lu += k;
        }
        return b;
    }

    static String quoter(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String dateImap(Date d) {
        return new SimpleDateFormat("d-MMM-yyyy", Locale.ENGLISH).format(d);
    }

    // ------------------------------------------------------------------ MIME

    /** Lecture minimale d'un message MIME : expéditeur, date, pièces jointes PDF. */
    static final class Mime {

        static final class Partie {
            final String nom;
            final byte[] octets;

            Partie(String nom, byte[] octets) {
                this.nom = nom;
                this.octets = octets;
            }
        }

        static final class Message {
            String de = "", date = "", repondreA = "", messageId = "", sujet = "";
            final List<Partie> pdf = new ArrayList<>();
            /** Texte des parties text/plain et text/html, décodé. */
            final StringBuilder texte = new StringBuilder();
        }

        static Message lire(byte[] brut) {
            Message m = new Message();
            String texte = new String(brut, StandardCharsets.ISO_8859_1);   // octets conservés tels quels
            partie(texte, m, true);
            return m;
        }

        /** En-têtes (repliés) et corps d'une partie. */
        private static void partie(String texte, Message m, boolean racine) {
            int fin = texte.indexOf("\r\n\r\n");
            int saut = 4;
            if (fin < 0) {
                fin = texte.indexOf("\n\n");
                saut = 2;
            }
            String entetes = fin < 0 ? texte : texte.substring(0, fin);
            String corps = fin < 0 ? "" : texte.substring(fin + saut);
            entetes = entetes.replaceAll("\r?\n[ \t]+", " ");
            String type = "text/plain", disposition = "", encodage = "7bit";
            for (String l : entetes.split("\r?\n")) {
                int i = l.indexOf(':');
                if (i <= 0) continue;
                String cle = l.substring(0, i).trim().toLowerCase(Locale.ROOT), val = l.substring(i + 1).trim();
                switch (cle) {
                    case "content-type": type = val; break;
                    case "content-disposition": disposition = val; break;
                    case "content-transfer-encoding": encodage = val.toLowerCase(Locale.ROOT); break;
                    case "from": if (racine) m.de = motsEncodes(val); break;
                    case "date": if (racine) m.date = val; break;
                    case "reply-to": if (racine) m.repondreA = motsEncodes(val); break;
                    case "message-id": if (racine) m.messageId = val.trim(); break;
                    case "subject": if (racine) m.sujet = motsEncodes(val); break;
                    default: break;
                }
            }
            String typeBas = type.toLowerCase(Locale.ROOT);
            if (typeBas.startsWith("multipart/")) {
                String limite = parametre(type, "boundary");
                if (limite.isEmpty()) return;
                String sep = "--" + limite;
                int pos = corps.indexOf(sep);
                while (pos >= 0) {
                    int debut = corps.indexOf('\n', pos);
                    if (debut < 0) break;
                    if (corps.startsWith(sep + "--", pos)) break;                 // fin du multipart
                    int suivant = corps.indexOf(sep, debut);
                    String morceau = corps.substring(debut + 1, suivant < 0 ? corps.length() : suivant);
                    if (morceau.endsWith("\r\n")) morceau = morceau.substring(0, morceau.length() - 2);
                    else if (morceau.endsWith("\n")) morceau = morceau.substring(0, morceau.length() - 1);
                    partie(morceau, m, false);
                    pos = suivant;
                }
                return;
            }
            if (typeBas.startsWith("message/rfc822")) {                       // commande transférée en pièce jointe
                partie(corps, m, false);
                return;
            }
            String nom = parametre(disposition, "filename");
            if (nom.isEmpty()) nom = parametre(type, "name");
            nom = motsEncodes(nom);
            boolean pdf = typeBas.startsWith("application/pdf")
                    || (nom.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    && (typeBas.startsWith("application/octet-stream") || typeBas.startsWith("application/")));
            if (!pdf) {
                if (typeBas.startsWith("text/") && disposition.toLowerCase(Locale.ROOT).indexOf("attachment") < 0) {
                    byte[] t;
                    if (encodage.contains("base64")) t = base64(corps);
                    else if (encodage.contains("quoted-printable")) t = quotedPrintable(corps);
                    else t = corps.getBytes(StandardCharsets.ISO_8859_1);
                    String cs = parametre(type, "charset");
                    Charset ch;
                    try {
                        ch = cs.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(cs);
                    } catch (Exception e) {
                        ch = StandardCharsets.UTF_8;
                    }
                    String txt = new String(t, ch);
                    if (typeBas.startsWith("text/html")) txt = txt.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ");
                    m.texte.append(txt).append('\n');
                }
                return;
            }
            byte[] octets;
            if (encodage.contains("base64")) octets = base64(corps);
            else if (encodage.contains("quoted-printable")) octets = quotedPrintable(corps);
            else octets = corps.getBytes(StandardCharsets.ISO_8859_1);
            if (octets.length >= 4 && octets[0] == '%' && octets[1] == 'P' && octets[2] == 'D' && octets[3] == 'F') {
                m.pdf.add(new Partie(nettoyerNom(nom), octets));
            }
        }

        /** Paramètre d'un en-tête : name="x", filename=x, ou RFC 2231 filename*=utf-8''x%20y. */
        static String parametre(String entete, String cle) {
            Matcher m = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(cle) + "\\*=([^;]*)").matcher(entete);
            if (m.find()) {
                String v = m.group(1).trim().replace("\"", "");
                int a = v.indexOf("''");
                String jeu = a > 0 ? v.substring(0, a) : "UTF-8";
                if (a >= 0) v = v.substring(a + 2);
                try {
                    return java.net.URLDecoder.decode(v.replace("+", "%2B"), jeu);
                } catch (Exception e) {
                    return v;
                }
            }
            m = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(cle) + "\\s*=\\s*(\"([^\"]*)\"|[^;\\s]*)").matcher(entete);
            if (m.find()) return m.group(2) != null ? m.group(2) : m.group(1);
            return "";
        }

        /** =?utf-8?B?...?= et =?iso-8859-1?Q?...?= */
        static String motsEncodes(String s) {
            Matcher m = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(s);
            StringBuffer b = new StringBuffer();
            while (m.find()) {
                String r;
                try {
                    Charset cs = Charset.forName(m.group(1));
                    byte[] o = m.group(2).equalsIgnoreCase("B") ? base64(m.group(3))
                            : quotedPrintable(m.group(3).replace('_', ' '));
                    r = new String(o, cs);
                } catch (Exception e) {
                    r = m.group(3);
                }
                m.appendReplacement(b, Matcher.quoteReplacement(r));
            }
            m.appendTail(b);
            return b.toString().replaceAll("\\?=\\s+=\\?", "?==?");
        }

        static String nettoyerNom(String nom) {
            String n = nom.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
            return n.length() > 120 ? n.substring(n.length() - 120) : n;
        }

        static byte[] base64(String s) {
            String propre = s.replaceAll("[^A-Za-z0-9+/=]", "");
            return android.util.Base64.decode(propre, android.util.Base64.DEFAULT);
        }

        static byte[] quotedPrintable(String s) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] o = s.getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < o.length; i++) {
                if (o[i] == '=' && i + 2 < o.length) {
                    if (o[i + 1] == '\r' && o[i + 2] == '\n') { i += 2; continue; }
                    if (o[i + 1] == '\n') { i += 1; continue; }
                    try {
                        b.write(Integer.parseInt(new String(o, i + 1, 2, StandardCharsets.ISO_8859_1), 16));
                        i += 2;
                        continue;
                    } catch (NumberFormatException e) {
                        // « = » isolé
                    }
                }
                b.write(o[i]);
            }
            return b.toByteArray();
        }
    }
}
