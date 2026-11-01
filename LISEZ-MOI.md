# Résa SAD : appli Android pour le Service à la Demande TAC

L'appli remplit et envoie le formulaire
https://ardenne-metropole-tad.nuamouv.com/#/demande-sad
et transmet exactement la même demande que le site officiel.

En plus du site :
- **ton identité est gardée** sur le téléphone (nom, prénom, téléphone, email, personnes en plus) ;
- **trajets favoris** : enregistre un trajet (ligne, arrêts, horaires, retour), puis une touche suffit pour tout remplir ;
- **un écran de vérification** s'affiche avant chaque envoi.

> Rappel du site TAC : une réservation faite par internet doit être **confirmée par l'équipe TAC** pour être réalisée.

---

## Obtenir le fichier .apk (gratuit, environ 10 minutes la première fois)

GitHub compile l'appli à ta place.

1. Crée un compte gratuit sur https://github.com si tu n'en as pas.
2. Clique sur **+** en haut à droite, puis **New repository**. Donne-lui le nom `resa-sad`, choisis **Private**, puis clique sur **Create repository**.
3. Clique sur le lien **uploading an existing file**. Décompresse `ResaSAD.zip` sur ton ordinateur, puis glisse **tout le contenu** du dossier `tac-sad` dans la page (les dossiers `app`, les fichiers `build.gradle`, etc.). Clique sur **Commit changes**.
4. Le dossier caché `.github` n'est souvent pas envoyé par le glisser-déposer. Pour l'ajouter, clique sur **Add file**, puis **Create new file**. Comme nom, tape exactement `.github/workflows/build.yml`. Ouvre le fichier `build.yml` du zip avec le Bloc-notes, copie tout son contenu, colle-le dans GitHub, puis clique sur **Commit changes**.
5. Va dans l'onglet **Actions**. Une compilation « Construire l'APK » démarre et prend environ 3 à 5 minutes. Attends qu'elle affiche une coche verte ✅.
6. Clique sur cette compilation. En bas, dans **Artifacts**, télécharge **ResaSAD-apk** : tu obtiens un zip qui contient `ResaSAD.apk`.

## Installer sur le téléphone Android

1. Envoie `ResaSAD.apk` sur le téléphone (par email, Google Drive, câble USB…).
2. Ouvre le fichier sur le téléphone. Android demande d'**autoriser l'installation d'applis de cette source** : accepte pour cette fois.
3. Si Play Protect affiche un avertissement (appli inconnue), touche **Plus de détails**, puis **Installer quand même**. C'est normal pour une appli qui ne vient pas du Play Store.
4. L'icône **Résa SAD** (un bus blanc sur fond vert) apparaît.

## Utilisation

1. Remplis ton identité une seule fois : elle reste enregistrée.
2. Choisis la date, la ligne, l'arrêt et l'horaire de départ, puis l'arrêt d'arrivée (l'horaire d'arrivée se remplit tout seul). Coche « Réserver le retour » si besoin.
3. Touche **★ Enregistrer ce trajet** pour le garder en favori.
4. Coche le règlement, puis touche **Vérifier et envoyer**. Vérifie le récapitulatif et touche **Envoyer**.

## Si le site TAC change

L'appli utilise les mêmes adresses internes que le site. Si TAC modifie son site, l'appli peut cesser de fonctionner : il faudra alors la mettre à jour.
