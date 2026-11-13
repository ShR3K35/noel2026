# Calibration des allures sur tapis — protocole v1

Relance affiche des consignes ; l'utilisateur règle le tapis à la main. L'app ne connaît que la vitesse qu'il **confirme avoir réglée**. Ces tests donnent des **repères de séance associés à une durée observée** : ce n'est ni une VMA, ni une VO2max, ni un seuil physiologique.

Code : `app/src/main/java/fr/relance/app/core/` — `PaceRefs.kt` (références, tapis), `PaceTest.kt` (protocole, constantes), `Acoustic.kt` (analyse de la lecture), `VoiceCommands.kt` (réponses vocales). Tests : `PaceTestTest`, `PaceIntegrationTest`, `AcousticTest`, `PaceUiTest`.

## Statut des choix

| Élément | Statut |
|---|---|
| Test de la parole (passage lu, question de confort) | Fondé sur la littérature (voir sources) comme aide pour trouver une intensité facile. |
| Durées, hausses de 0,3 km/h, seuils d'effort 2/3/4/5, budget de 20 min, règles de décision | **Choix de conception prudents**, non validés scientifiquement. |
| Échelle d'effort 0–10 (0 repos, 3 modéré, 5 difficile, 10 maximal) | Repère simple propre à l'app. |
| Analyse acoustique | **Expérimentale et descriptive.** Seuils techniques ajustables, testés sur voix de synthèse et bruit simulé, pas sur des utilisateurs. |

## Références

`walkEasy` (marche facile : échauffement, récupérations, retour au calme), `walkActive` (marche active), `runEasy` (trot facile). Chacune peut être absente ; une absence ne vaut jamais zéro. Chaque référence garde : vitesse déclarée, pente (0 %), date, protocole et version, provenance (`TEST`, `MANUAL`, `LEGACY`), statut (`PROVISIONAL` ou `CONFIRMED` = confirmée pendant `observedSec` secondes continues), identifiant du test. Le test garde séparément la vitesse proposée, la vitesse déclarée et toutes les observations (`CalibrationRecord`, historique dans `AppState.calibrations`).

Remplacement après un test : une référence confirmée ou manuelle remplace l'ancienne ; une provisoire ne remplace une confirmée que si elle est plus lente (sinon elle reste candidate). L'utilisateur peut aussi garder un résultat sans l'appliquer.

## Classement d'un contrôle (dans cet ordre)

1. Symptôme ou demande d'arrêt → interruption (aucune référence enregistrée).
2. Parole « Non » ou effort ≥ 5 → `tooHard`.
3. Parole « Incertain » ou effort = 4 → `borderline`.
4. Parole « Oui » et effort ≤ 3 et déplacement confortable → `comfortable`.
5. Sinon (manquant, contradictoire, sans réponse après 45 s) → `unknown`, qui n'autorise aucune progression.

## Tests (budget : 1 200 s depuis le premier pas, horloge monotone ; 180 s réservées au retour au calme)

- **Marche par paliers** (nominal 17 min) : 5 min de marche facile (stable sur les 2 dernières minutes, parole confortable, effort ≤ 2 → `walkEasy` provisoire) ; jusqu'à 3 paliers de 3 min, contrôle dans les 30 dernières secondes, +0,3 km/h au plus (arrondi au pas du tapis ; pas > 0,3 → pas de hausse, confirmation proposée) ; arrêt dès `borderline`, `tooHard`, `unknown`, marche difficile, envie de trotter ou limite du tapis. `walkActive` provisoire = palier confortable qui précède le dernier (ou le seul).
- **Confirmation continue** marche ou trot (nominal 18 min) : 5 min d'échauffement, 10 min à une seule vitesse, contrôles aux minutes 3, 6 et 10. Trois `comfortable` et bloc complet → référence **confirmée 10 min**. Une pause, une correction de vitesse ou un contrôle non confortable termine la tentative (candidate inférieure proposée). Une allure très facile suggère un autre test à +0,3 km/h au plus. Pas de plancher ni de plafond universels (seulement les bornes du tapis).
- **Marche-course débutant** (nominal 15 min) : 5 min de marche, 3 × (1 min de trot naturel / 2 min de marche), 3 min de retour au calme. Trois répétitions confortables à la même vitesse, récupérations complètes → `runEasy` **provisoire, observée 1 min** (réglage de blocs courts, pas une capacité à courir longtemps). Réponse donnée après le retour à la marche : acceptée et signalée. Récupération prolongeable si le budget le permet ; une répétition supprimée ne compte pas.

Chaque palier commence après confirmation de la vitesse réglée (jamais au-dessus de la vitesse proposée). Avant chaque palier, l'app vérifie qu'il tient avec le retour au calme ; sinon, retour au calme et résultat incomplet. Une pause (bouton, arrière-plan, appel, relance de l'app) rompt la continuité ; la reprise demande toujours une confirmation, et les 20 minutes continuent de s'écouler.

## Utilisation dans les séances

Échauffement, récupération et retour au calme → `walkEasy` ; marche → `walkActive` ; trot → `runEasy`. Sans référence : **repli explicite** sur l'ancien calcul depuis la vitesse de référence (`vr`) et les réglages du catalogue, affiché comme tel. La course n'est jamais déduite de la marche. Côte, allure soutenue et rapide gardent le mécanisme du catalogue (côte depuis `vr`, soutenue et rapide ×1,12 et ×1,25 du trot). Les vitesses fixes (`pace: "kmh"`) ne changent pas. Toute vitesse est arrondie au pas du tapis et bornée par ses vitesses minimale et maximale, avec explication du conflit. Objectif de distance : durée = 60 × km / vitesse retenue, recalculée à chaque séance (y compris pour un programme démarré avant cette version). Chaque séance faite garde les références utilisées (`CompletedSession.basis`). Le contrat d'import n'a pas changé : les références vivent dans le profil.

## Voix et analyse acoustique (sur l'appareil, gratuit)

- **Reconnaissance**, deux moteurs, tous deux sur l'appareil :
  - **Android sur l'appareil** en priorité (Android 12 et plus, par exemple un Pixel 8a en français) : `SpeechRecognizer.createOnDeviceSpeechRecognizer`, langue `fr-FR`, mots attendus favorisés (`EXTRA_BIASING_STRINGS`). Si le français hors ligne n'est pas installé, l'app propose de l'installer par Android. Il écoute une réponse à la fois, pendant les questions ; le micro de l'app ne tourne alors que pendant les lectures, pour que les deux ne se disputent jamais le micro. Les commandes « pause », « ralentir », « arrêter » sont reconnues pendant les questions ; hors questions, les boutons.
  - **Vosk** en secours (téléphone sans reconnaissance sur l'appareil, français indisponible, ou au choix) : Vosk Android 0.3.75 (Apache 2.0) et `vosk-model-small-fr-0.22` (41 Mo, Apache 2.0, téléchargé une fois, taille vérifiée), en mode grammaire limitée ; il écoute aussi les commandes en continu.
  - Chaque réponse est interprétée selon la question en cours ; parmi les hypothèses d'Android, la première qui a un sens pour la question est retenue. Une réponse ambiguë déclenche une seule relance ciblée, puis les boutons. Le micro est en sourdine pendant que l'app parle, et 400 ms après.
- **Référence personnelle** avant le mouvement : 3 s de silence et deux lectures du passage (v1). À refaire si le micro, sa position ou le texte changent.
- **Indicateurs** : durée de lecture, segments de parole, nombre et durée des pauses internes (≥ 250 ms), fraction de parole, niveau de bruit, rapport parole/bruit, saturation. Détection d'activité vocale par énergie après un filtre passe-haut à 150 Hz, avec un seuil adaptatif (+9 dB pour démarrer, +5 dB pour finir) au-dessus du bruit local (15e centile). **Un silence n'est pas une mesure de respiration.**
- **Écarts notables** (heuristiques ajustables, `AcousticConfig`) : durée +15 %, +2 pauses, +1 s de pauses, fraction de parole −8 points. Ils déclenchent seulement une question de confort supplémentaire ; ils n'autorisent jamais une hausse et ne valident jamais un palier. Les pourcentages décrivent un changement mesuré, pas une probabilité.
- **Capture inutilisable** : rapport parole/bruit < 15 dB, saturation > 0,5 %, lecture < 3 s, coupée ou absente. Sensibilité au bruit testée : un bruit de tapis simulé à 17 dB ne crée pas d'écart ; à 10 dB, des pauses fictives apparaissent, d'où le seuil de 15 dB.
- **Aucun classificateur physiologique ni score de confiance** n'est livré : pas de modèle entraîné et validé pour des francophones sur tapis. AktivTalk (20 participants, sur vélo) ne fournit pas de garantie transposable.
- **Données** : l'audio reste en mémoire le temps de l'analyse, puis il est effacé ; seuls les résultats structurés sont gardés. La conservation pour diagnostic est un choix explicite, révocable (Mes allures → Voix), qui efface les fichiers quand on la désactive.
- **Sans micro** (refusé, modèle absent, erreur, bruit) : tout le parcours se fait aux boutons. Le micro n'est pas un arrêt d'urgence.

## Vérifications faites et restant à faire

- Faites : reconnaissance d'Android simulée par Robolectric (langue, hors ligne, mots favorisés, chiffres et ponctuation, erreur de langue) ; tests unitaires et Robolectric (règles, scénarios, limite de 20 min sur 400 parcours aléatoires, interruptions, migration, distances) ; reconnaissance Vosk **hors réseau** sur PC (`tools/vosk-check`, même modèle et même grammaire) : 9 réponses de synthèse reconnues sur 9, aussi avec bruit de tapis simulé ; l'écho de la question lue par l'app est reconnu comme « oui incertain non », ce qui justifie la sourdine.
- À faire sur téléphone : la reconnaissance d'Android réelle sur le Pixel 8a, capture micro réelle avec le tapis en marche, voix humaines, micro Bluetooth, appels entrants, téléchargement du modèle sur réseau mobile, charge processeur de Vosk pendant 20 minutes.
- Non faite : toute validation scientifique du protocole ou de l'analyse acoustique.

## Sources

- Persinger R. et al., *Consistency of the talk test for exercise prescription*, Med Sci Sports Exerc, 2004 — https://pubmed.ncbi.nlm.nih.gov/15354048/
- Jeanes E.M. et al., *Translation of exercise testing to exercise prescription using the talk test*, J Strength Cond Res, 2011 — https://pubmed.ncbi.nlm.nih.gov/21311346/
- *AktivTalk: Digitizing the Talk Test for Voice-Based Exercise Intensity Self-Assessment and Exploring Automated Classification from Speech* (20 participants, vélo) — https://arxiv.org/html/2604.20302v1
- Android `SpeechRecognizer` (reconnaissance sur l'appareil à partir d'Android 12, selon les langues installées) — https://developer.android.com/reference/android/speech/SpeechRecognizer
- Vosk Android et modèles — https://alphacephei.com/vosk/android, https://alphacephei.com/vosk/models
