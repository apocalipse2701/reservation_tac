# Journal des versions

Numérotation `MAJEURE.MINEURE.CORRECTIF` :
- **MAJEURE** : changement important (réglages à refaire, fonctionnement différent) ;
- **MINEURE** : nouvelle fonction ;
- **CORRECTIF** : correction de bug, petit ajustement.

Le numéro de version est dans `version.txt`. Chaque version publiée a sa propre page « Release » sur GitHub (`v2.0.0`…) ;
la page « Version … (dernière) » sert aux mises à jour automatiques de l'appli.

Pour publier une nouvelle version : augmenter `version.txt`, ajouter sa section ci-dessous (même numéro), puis envoyer.

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
