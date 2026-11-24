# Journal des versions

Numérotation `MAJEURE.MINEURE.CORRECTIF` :
- **MAJEURE** : changement important (réglages à refaire, fonctionnement différent) ;
- **MINEURE** : nouvelle fonction ;
- **CORRECTIF** : correction de bug, petit ajustement.

Le numéro de version est dans `version.txt`. Chaque version publiée a sa propre page « Release » sur GitHub (`v2.0.0`…) ;
la page « Version … (dernière) » sert aux mises à jour automatiques de l'appli.

Pour publier une nouvelle version : augmenter `version.txt`, ajouter sa section ci-dessous (même numéro), puis envoyer.

## 2.7.1 — 24/11/2026
- Accueil : les tuiles Cantine et Bus affichent leur icône en grand, en fond.
- Le nom de la ligne s'écrit « Ligne 14 » au lieu de « LIGNE14 » (tuile Bus, résumé, en-tête, listes). La tuile Bus affiche seulement la ligne.

## 2.7.0 — 24/11/2026
- Covoiturage : nouveau bouton « 🏫 Pas d'école » sur chaque jour (grève, journée pédagogique, sortie…). Le jour s'affiche alors « 🏫 Pas d'école : personne à aller chercher », sans covoiturage, et tous les téléphones sont prévenus. « Il y a école finalement » annule.
- Accueil, ligne Retour : affiche la raison quand personne n'a à aller chercher les enfants (🏫 Pas d'école, 🚌 Bus…), au lieu de « — ».

## 2.6.7 — 24/11/2026
- Bus › ⭐ Mes trajets favoris :
  - quand il n'y a aucun favori, l'appli explique que le trajet habituel (Réglages) remplit déjà la ligne et les arrêts, et qu'un favori retient en plus l'horaire ;
  - dès qu'un trajet complet est rempli et n'est pas encore en favori, un encadré propose « ★ L'enregistrer en favori », juste sous la liste ;
  - le favori qui correspond au trajet rempli est mis en évidence.
- « Forcer un jour » est replié par défaut pour alléger le haut de la page.

## 2.6.6 — 23/11/2026
- Accueil : mêmes couleurs translucides que dans les réglages. Cantine en bleu, Bus en vert et Covoiturage en rose, sur les lignes du résumé du jour comme sur les grandes tuiles.

## 2.6.5 — 23/11/2026
- Réglages : chaque catégorie a sa couleur, en translucide (lisible en thème clair comme en Ardoise) : Bus vert, Portail cantine bleu, Planning orange, Covoiturage rose, Widget turquoise, Couleurs violet, Application gris.

## 2.6.4 — 23/11/2026
- Réglages › Bus : un seul bloc bien visible (bandeau bleu, cadre), avec ses trois parties numérotées à l'intérieur : 1 · Enfant et trajet, 2 · Mon identité, 3 · Réponses de la compagnie.

## 2.6.3 — 23/11/2026
- Réglages réorganisés :
  - une seule partie « 🚌 Bus » regroupe l'enfant et le trajet habituel, mon identité pour les demandes, et les réponses de la compagnie (mail) ;
  - l'adresse du portail famille est maintenant dans « 🍽️ Portail cantine » ;
  - les prénoms des enfants du covoiturage sont dans « 🚗 Covoiturage ».

## 2.6.2 — 23/11/2026
- Réglages › Trajet de bus habituel : la ligne et les arrêts enregistrés sont de nouveau affichés dans les listes quand on revient dans les réglages (avant, elles revenaient à « Choisir la ligne… »).
- Bus › « ★ Enregistrer ce trajet » : le nom du favori se saisit dans une fenêtre aux couleurs du thème, et un message confirme l'enregistrement.

## 2.6.1 — 23/11/2026
- Cantine : correction de l'erreur « Impossible de joindre le portail » alors qu'internet fonctionne. Un texte du portail contenant certains caractères (par exemple une apostrophe écrite « \' ») bloquait la lecture de toute la page. Chaque valeur est maintenant lue séparément.
- Si la page du portail ne peut pas être lue, le message le dit clairement, avec le détail, au lieu de parler de connexion internet.

## 2.6.0 — 09/11/2026
- Covoiturage : nouvel encadré « 👥 Personnes du covoiturage ». On peut y ajouter quelqu'un qui n'a pas l'appli (grand-parent, voisin…), avec un nom, des initiales et une photo facultative, puis le modifier ou le retirer.
- Sur chaque jour, le bouton « 👤 Quelqu'un d'autre » (ou « Autre ») permet de choisir qui récupère les enfants : une personne de la liste, ou une nouvelle personne à ajouter tout de suite.
- Tous les téléphones sont prévenus (« Mamie récupère … (indiqué par Papa) »). Les initiales de ces personnes apparaissent aussi sur le widget.

## 2.5.3 — 04/11/2026
- Messages de l'appli : petite fenêtre aux couleurs du thème, avec une icône (✓ réussi, ⚠️ problème), au lieu de « La page file:// indique ».
- « Enregistré sur le téléphone. » après l'enregistrement des réglages.
- Questions (OK / Annuler) et saisies : fenêtres sans le titre « La page file:// indique ».

## 2.5.2 — 04/11/2026
- Sous-pages des réglages (covoiturage, lien avec le planning, affichage d'un code) : bouton « ‹ Retour » en haut, qui reste visible quand on fait défiler.
- Personnaliser le widget et Connexion au portail famille : bouton « ‹ Retour » en haut.
- La touche retour du téléphone ferme d'abord la sous-page ouverte, au lieu de quitter les réglages.

## 2.5.1 — 04/11/2026
- Réglages : grand bouton « ‹ Retour à l'accueil » en bas de la page.
- Le bouton « ‹ » en haut ramène toujours à l'accueil, même quand l'appli a été ouverte directement sur une page.

## 2.5.0 — 04/11/2026
- Covoiturage : nouvel encadré « 🎒 Heures de sortie (emploi du temps) ». On y saisit l'heure de fin des cours de chaque jour, en semaine A et en semaine B. L'heure de sortie de chaque jour est alors calculée automatiquement, et partagée avec tout le covoiturage.
- Vacances scolaires (zone B, 2026-2027) et jours fériés : affichés « 🏖️ … : pas d'école », sans covoiturage. Ces jours sont aussi ignorés par le widget et par l'indisponibilité automatique. On peut quand même ajouter un de ces jours en touchant son heure.
- Une heure mise à la main sur un jour précis reste prioritaire.

## 2.4.0 — 04/11/2026
- Accueil : la ligne Bus affiche les 3 prochains jours de classe, chacun avec son statut (✅ pris en compte, ⏳ en attente, ❌ refusé ou pas de demande) et l'horaire.

## 2.3.1 — 04/11/2026
- Accueil : le mercredi, la ligne Cantine affiche « Pas de cantine Mercredi » au lieu de « Pas encore vu ».

## 2.3.0 — 04/11/2026
- Covoiturage : quand le bus de Léa est validé (✅) pour un jour, ce jour passe en « 🚌 Léa prend le bus : personne à aller chercher » et on ne peut plus le prendre en covoiturage. C'est partagé avec tous les téléphones du covoiturage (ils sont prévenus).
- Si le bus est ensuite refusé ou retiré, le jour redevient à prendre (« il faut aller chercher… »).
- La synchronisation se fait à l'ouverture de l'appli, à l'ouverture du covoiturage et dès qu'une réponse de bus est indiquée à la main.

## 2.2.0 — 04/11/2026
- Bus : nouvel encadré « ⏳ Suivi des réservations », toujours visible.
- « Forcer un jour » : sur n'importe quelle date, marquer le bus comme ✅ pris en compte, ❌ refusé, ou 🗑️ le retirer (horaire facultatif). Utile pour une réservation faite par téléphone ou sur le site, ou pour corriger un jour.
- Une demande en attente pour ce jour-là est réglée en même temps ; l'accueil et le widget sont mis à jour.

## 2.1.0 — 04/11/2026

**Bus**
- Carte « Demandes en attente » en haut de la partie Bus : chaque demande sans réponse peut être marquée
  « ✅ Prise en compte » ou « ❌ Refusée » à la main, si le mail de la compagnie n'est pas arrivé ou pas reconnu.
  L'accueil et le widget sont mis à jour, et l'appli n'attend plus de mail pour cette demande.

## 2.0.0 — 04/11/2026

Première version numérotée : elle regroupe tout le travail fait jusqu'ici.

**Cantine**
- Calendrier du mois : toucher un jour pour réserver ou annuler, puis « Valider ».
- Connexion automatique au portail famille (identifiants chiffrés sur le téléphone).
- Suivi des demandes en attente : acceptée ✅ / refusée ❌, avec notification et mise à jour du widget.
- Conseils d'après le planning de travail (version « avec planning »), services à ignorer.

**Bus**
- Demande de transport simplifiée, trajet habituel choisi dans les réglages.
- Réponses de la compagnie lues dans la boîte mail : « prise en compte » ou « refusée ».

**Covoiturage**
- Fichier partagé chiffré sur un serveur WebDAV (Synology…), notifications des changements.
- Indisponibilité déduite du planning, photo de profil de chacun, initiales dans le widget.

**Appli**
- Accueil « Aujourd'hui », 5 thèmes de couleurs, widget réglable, mises à jour automatiques.
- Aucune donnée personnelle dans le code : prénoms, trajet et identifiants restent sur le téléphone.
