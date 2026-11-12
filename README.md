# Relance

App Android de remise au sport sur tapis de course : un coach qui s'adapte à ton emploi du temps et à ta condition physique de départ.

## Installer l'app

**[Télécharger l'APK (relance.apk)](apk/relance.apk?raw=true)** — version 0.3.0, Android 8 ou plus récent.

1. Ouvre ce lien depuis le téléphone, ou copie le fichier `apk/relance.apk` dessus.
2. Ouvre le fichier et accepte l'installation depuis cette source (Android le demande pour toute app hors Play Store).
3. Lance « Relance » et fais le bilan de départ.

Les données restent sur le téléphone : pas de compte, pas de serveur.

| Aujourd'hui | Séance guidée | Fiche de séance | Planning |
|---|---|---|---|
| ![Aujourd'hui](docs/captures/aujourdhui.png) | ![Séance guidée](docs/captures/seance.png) | ![Fiche de séance](docs/captures/fiche-seance.png) | ![Planning](docs/captures/planning.png) |

Le coureur animé, à l'arrêt, en marche, au trot et en course rapide :

![Coureur animé](docs/captures/coureur.png)

## Ce que fait cette version

- **Bilan de départ** : questions de santé, profil, zones sensibles, test de marche, jours et durée disponibles.
- **Programmes sur mesure** : courir 10 min, 30 min, 45 min, 1 h, 5 km, 10 km, améliorer ses performances, santé et forme. Un seul programme à la fois, avec pause, arrêt et liste des programmes effectués.
- **Planning** : prochaines séances datées selon tes jours, une semaine allégée sur quatre avec test de forme. Une séance manquée glisse, elle n'est jamais doublée.
- **Séance guidée** : compte à rebours par bloc, vitesse à régler, annonces vocales, version courte « je n'ai que 15 min », bouton « trop dur ».
- **Coureur animé** : il suit la vitesse du tapis (ou la consigne), chante en marchant, parle en trottinant, transpire quand ça va vite.
- **Adaptation** : l'effort ressenti, une douleur ou une séance écourtée modifient la suite du programme ; une coupure de deux semaines fait reculer de deux paliers.
- **Historique** : statistiques par séance (durée, distance, allure, cardio et zones, calories estimées), courbes, cumuls et records.
- **Export** : chaque séance en TCX (accepté par Strava et Garmin Connect) ou en CSV.
- **Capteurs Bluetooth** : ceinture cardio standard, et tapis ou boîtier ESP32 parlant FTMS, en lecture seule.
- **Fréquence cardiaque de repos** : bouton « Mesurer avec ma ceinture » dans le profil. Assis, 2 minutes d'écoute, puis la valeur est enregistrée et les zones cardiaques recalculées.
- **Étalonnage du boîtier** : trois paliers de marche, bande à vide, pour caler la vitesse mesurée sur celle de la console.

## Boîtier pour Domyos Run100

Le Run100 classique n'est pas connecté : un boîtier ESP32 (environ 20 €) compte les tours d'un rouleau grâce à un aimant et un capteur à effet Hall, et envoie vitesse et distance à l'app.

- [Guide de montage](docs/MONTAGE.md) : liste d'achat, câblage, pose, mise en service.
- [Programme du boîtier](firmware/README.md) : à charger une fois dans l'ESP32 avec PlatformIO.

## Ce qui n'y est pas encore

- Envoi automatique vers Strava (l'export TCX s'importe à la main sur strava.com) et vers Decathlon Coach.
- Export au format FIT.
- Rappels par notification.
- Mesure de l'inclinaison par le boîtier (l'app utilise l'inclinaison prévue par la séance).

## État des vérifications

L'app a été construite et testée sans téléphone ni émulateur :

- le moteur de coaching, l'export, l'étalonnage et le décodage Bluetooth sont couverts par 26 tests unitaires ;
- le programme du boîtier compile pour ESP32 et sa logique de mesure passe 7 tests ; une trame qu'il produit est décodée par l'app dans les tests ;
- des tests parcourent toute l'interface (bilan de départ, séance, bilan, fiche, arrêt de programme, étalonnage, mesure de la FC de repos) et produisent les captures ci-dessus.

**Non vérifié sur un vrai appareil** : le boîtier monté sur un vrai tapis, la connexion Bluetooth à une ceinture ou au boîtier, les annonces vocales, l'enregistrement du fichier d'export, et le comportement quand l'app passe en arrière-plan pendant une séance (garde l'écran de séance ouvert : il reste allumé).

## Construire l'APK

Il faut le SDK Android (plateforme 35) et un JDK 17 ou plus récent.

```bash
./gradlew :app:testDebugUnitTest   # tests, captures dans app/build/shots
./gradlew :app:assembleRelease     # APK dans app/build/outputs/apk/release
```

L'APK est signé avec une clé de développement versionnée (`app/relance-dev.keystore`), pour que chaque version s'installe par-dessus la précédente. Elle n'a rien de secret : à remplacer par une clé privée avant toute diffusion publique.

## Documentation

- [Concept détaillé](docs/CONCEPT.md) : logique de coach, programmes, capteurs, feuille de route.

Relance donne des conseils d'entraînement, pas un avis médical.
