# Concept — « Relance » : app Android de remise au sport sur tapis de course

## Contexte

Demande : imaginer une app Android de remise au sport avec un seul équipement, un tapis de course. Elle doit s'adapter à **l'emploi du temps** et à **la condition physique de départ**, en raisonnant comme un vrai coach.
Statut : **concept seulement**, pas de code à ce stade.

Le principe de coach qui guide tout : *en reprise, on ne rate pas par manque d'intensité mais par excès d'ambition.* L'app vise la régularité avant la performance, progresse lentement, et recule sans culpabiliser quand la vie s'en mêle.

---

## 1. Bilan de départ (onboarding, ~5 min)

| Étape | Ce que l'app demande | À quoi ça sert |
|---|---|---|
| Santé | Questionnaire type PAR-Q (cœur, douleurs thoraciques, vertiges, articulations, traitement, grossesse) | Un « oui » → message clair « avis médical conseillé avant de commencer » + programme marche douce uniquement |
| Profil | Âge, taille, poids (facultatif), dernière pratique sportive (jamais / > 2 ans / < 6 mois) | Niveau de départ et vitesse de progression |
| Gênes | Genoux, dos, chevilles, essoufflement | Limite la course et l'inclinaison, privilégie la marche en côte |
| Objectif | Choix d'un programme dans le catalogue (§6) : courir 10 min, 30 min, 45 min, 1 h, 5 km, 10 km, améliorer ses performances… | Le programme est ensuite construit sur mesure |
| Tapis | Préréglé Domyos Run100 : 14 km/h max, inclinaison manuelle 0 % ou 3 % | Les séances ne proposent jamais un réglage impossible |
| Capteurs | Appairage ceinture cardio + boîtier ESP32 (facultatifs, voir §9) ; FC de repos mesurée 2 min assis | Zones cardiaques personnelles, suivi automatique |
| Emploi du temps | Jours possibles, durée disponible par jour (15 / 20 / 30 / 45 min), moment préféré | Planification de la semaine |

### Test initial sur le tapis (12 min, séance n°1)
1. 3 min de marche lente d'échauffement.
2. **6 min de marche rapide** : l'utilisateur monte la vitesse jusqu'à l'allure la plus vive qu'il tient *en pouvant encore parler*. Il saisit la vitesse tenue.
3. 3 min de retour au calme, puis il note l'effort ressenti (échelle 1–10).

Avec les capteurs, rien à saisir : l'ESP32 relève la vitesse tenue, la ceinture relève la FC moyenne de l'effort et la **récupération cardiaque à 1 min** (meilleur indicateur simple de forme en reprise).

→ Donne la **vitesse de référence (VR)** et la FC associée. Toutes les séances sont exprimées en pourcentage de la VR, donc personnalisées dès le premier jour. Test refait toutes les 4 semaines : c'est aussi la preuve visible du progrès.

### Quatre niveaux de départ
| Niveau | Profil type | Point de départ |
|---|---|---|
| N1 — Redémarrage | Sédentaire, surpoids important, gêne articulaire, ou alerte santé | Marche uniquement, 15–20 min |
| N2 — Marcheur | VR < 5,5 km/h, pas de sport depuis > 2 ans | Marche active + côtes |
| N3 — Marche/course | VR ≥ 5,5 km/h, effort ressenti ≤ 6, pas de gêne | Alternance marche / trot |
| N4 — Reprise course | Ancien coureur, arrêt < 6 mois | Course continue courte |

---

## 2. Le moteur de coaching

### Types de séances
- **Marche active** — 90–100 % VR, on peut parler. La base.
- **Marche en côte** — 80–90 % VR, inclinaison 3 % (seule position inclinée du Run100). Fait monter le cœur sans impact : l'arme principale pour N1/N2 et les genoux fragiles. L'inclinaison se règle à la main, tapis à l'arrêt : elle est donc **fixe pour toute la séance**, annoncée avant le départ, et la progression se fait par la durée puis la vitesse.
- **Marche/course** — blocs du type 1 min trot / 2 min marche, ratio qui évolue chaque semaine.
- **Endurance continue** — course lente, aisance respiratoire, à plat.
- **Fractionné doux** (N3/N4, après 6 semaines) — 6 × 1 min soutenu / 2 min marche.
- **Récupération** — 15 min de marche lente ; compte comme une séance tenue.

Chaque séance : échauffement 5 min + corps + retour au calme 3–5 min, non négociables.

### Règles de progression (celles d'un coach)
1. **Un seul curseur à la fois** : on augmente soit la durée, soit la vitesse, soit l'inclinaison — jamais deux la même semaine.
2. **+10 % de volume maximum** par semaine.
3. **Cycles de 4 semaines** : 3 semaines progressives + 1 semaine allégée (−30 %) avec re-test.
4. **80 % facile / 20 % soutenu** ; jamais deux séances dures à moins de 48 h.
5. **D'abord la fréquence, puis la durée, enfin l'intensité.**

### Pilotage par la fréquence cardiaque (si ceinture)
- **Zones personnelles** par la réserve cardiaque (Karvonen) : FC max estimée (208 − 0,7 × âge) puis corrigée par les valeurs réellement observées, FC de repos mesurée.
- La séance devient une consigne de zone : « reste en zone 2 ». Si la FC dépasse la zone plus de 30 s, le coach dit « ralentis de 0,5 km/h » ; si elle reste en dessous, « tu peux accélérer ». C'est le cœur qui fixe l'allure, pas un tableau.
- **Blocs de récupération au cardio** en marche/course : on repart quand la FC est redescendue, pas à la seconde près.
- **Indicateurs de progrès** : même vitesse à FC plus basse, récupération à 1 min, dérive cardiaque sur une séance continue.
- **Signaux de fatigue** : FC anormalement haute à allure facile → séance allégée automatiquement.
- **Plafond de sécurité** : alerte sonore au-delà de 90 % de la FC max en reprise.
- Traitement qui modifie le pouls (bêtabloquants…) → zones désactivées, retour au ressenti.

Sans ceinture, tout fonctionne au ressenti (1–10) et au test de la parole.

### Boucle de retour après chaque séance (10 secondes)
Effort ressenti (1–10) · Douleur (aucune / gêne / douleur + zone) · Séance terminée ou écourtée.

| Signal | Réaction de l'app |
|---|---|
| Effort ≤ 4 sur 2 séances de suite | Propose de passer au palier suivant en avance |
| Effort ≥ 8 sur 2 séances de suite | Répète la semaine, −5 % de vitesse |
| Séance écourtée | La prochaine reprend au palier précédent |
| « Gêne » | Séance suivante remplacée par marche à plat, suivi de la zone |
| « Douleur » | 48 h de repos, puis marche seule ; si répété → conseil de consulter |
| Signe d'alerte (douleur thoracique, vertige) | Arrêt immédiat, consigne médicale, programme suspendu |

---

## 3. Adaptation à l'emploi du temps

- **Semaine type** construite à partir des jours cochés : minimum 2 séances, cible 3, maximum 4 en reprise. L'app place les séances dures loin l'une de l'autre et protège au moins 1 jour de repos entre deux courses.
- **Chaque séance existe en 3 formats** (court 15–20 min, normal 30 min, long 45 min) avec le même objectif. Bouton « Je n'ai que 15 min aujourd'hui » → version courte plutôt qu'annulation.
- **Bilan du dimanche** (30 s) : « Ta semaine prochaine est-elle normale ? » → sinon, on recoche les créneaux et la semaine est recalculée.
- **Séance manquée** :
  - 1 séance → décalée au prochain créneau libre ; si impossible, abandonnée sans rattrapage (on ne double jamais).
  - Semaine à moins de 2 séances → la semaine est rejouée.
  - 2 semaines d'arrêt → retour 2 paliers en arrière ; > 4 semaines → mini re-test.
- **Rappels** : notification la veille et 1 h avant, déplaçable en un geste (« plutôt demain »). Lecture facultative de l'agenda du téléphone pour suggérer les créneaux libres.

---

## 4. Écrans principaux

1. **Aujourd'hui** — la séance du jour en une phrase (« 30 min · marche/course · facile »), bouton Démarrer, boutons « version courte » et « reporter ».
2. **Séance guidée** — très gros chiffres lisibles depuis le tapis : *vitesse cible* à côté de la *vitesse réelle* (ESP32), *FC et zone* en couleur (ceinture), inclinaison, compte à rebours du bloc, bloc suivant. Le bloc ne démarre que lorsque la vitesse cible est atteinte. Un **coureur animé** illustre l'effort (voir ci-dessous). Annonce vocale + vibration à chaque changement, écran toujours allumé, bouton « trop dur » (baisse la suite de la séance de 5–10 %).
3. **Bilan de séance** — les 3 questions de ressenti + un mot du coach.
4. **Prochaines séances** — les 2 semaines à venir en détail (date, type, durée, contenu bloc par bloc), séances déplaçables par glisser-déposer ; au-delà, la trame du programme semaine par semaine jusqu'à l'objectif, marquée « prévisionnel » car elle s'ajuste aux retours de séance.
5. **Historique** — toutes les séances passées et leurs statistiques (§7).
6. **Progrès** — évolution de la VR, minutes de course continue, régularité (semaines tenues, pas de « série » qui se casse au premier raté).
7. **Programmes** — programme en cours, catalogue, programmes effectués (§6).
8. **Profil** — disponibilités, tapis, capteurs, comptes connectés (Strava…), gênes déclarées.

### Le coureur animé

Pendant la séance, un personnage court à l'écran. Sa cadence suit en continu la **vitesse du tapis** s'il est connecté, sinon la **vitesse demandée** par le bloc en cours ; en pause, il s'arrête et souffle. Son attitude rend visible le « test de la parole » :

| État | Mouvement | Attitude |
|---|---|---|
| Arrêt | Debout, respiration lente | — |
| Marche | Pas de marche, bras souples | Il chante (notes de musique) ou discute (bulle) |
| Trot | Foulée de course, léger rebond | Il parle (bulle) |
| Course rapide | Foulée ample, buste penché | Il transpire (gouttes), plus de bulle |

Les seuils entre états sont calés sur les allures de l'utilisateur : un débutant transpire déjà à une vitesse où un coureur confirmé parle encore. L'animation se coupe dans le profil et respecte le réglage système de réduction des animations.

Ton du coach : direct, bienveillant, jamais culpabilisant. Il explique toujours *pourquoi* (« semaine allégée : c'est là que ton corps assimile »).

---

## 5. Exemple : N2, programme « Courir 30 min », 3 × 30 min/semaine

| Sem. | Séance A | Séance B | Séance C |
|---|---|---|---|
| 1 | Test initial + marche | Marche active 20 min | Côte 3 % · 15 min |
| 2 | Marche active 25 min | Côte 3 % · 20 min | Marche active 25 min |
| 3 | 6 × (1 min trot / 3 min marche) | Côte 3 % · 25 min | 6 × (1 / 3) |
| 4 *(allégée)* | 5 × (1 / 3) | Marche récup | Re-test |
| 5 | 6 × (2 / 2) | Côte 3 % · 25 min, +0,3 km/h | 6 × (2 / 2) |
| 6 | 5 × (3 / 2) | Côte 3 % · 30 min | 5 × (3 / 2) |
| 7 | 4 × (5 / 2) | Marche active | 3 × (7 / 2) |
| 8 *(allégée)* | 2 × (10 / 2) | Marche récup | Re-test |
| 9–12 | 15 → 20 → 25 → **30 min continues** | Côte ou récup | Course continue |

Les durées et vitesses réelles sont recalculées selon la VR et les retours de séance.

---

## 6. Programmes

### Catalogue
| Programme | Pour qui (prérequis) | Durée indicative | Validation |
|---|---|---|---|
| Courir 10 min | Débutant complet, N1/N2 | 4–6 sem. | 10 min de course continue |
| Courir 30 min | Tient 10 min, ou N2/N3 | 8–12 sem. | 30 min continues |
| Courir 45 min | Tient 30 min | 6–8 sem. | 45 min continues |
| Courir 1 h | Tient 45 min | 8 sem. | 60 min continues |
| Courir 5 km | Tient 10 min | 8–10 sem. | 5 km sans marcher |
| Courir 10 km | Tient 5 km ou 45 min | 10–12 sem. | 10 km sans marcher |
| Améliorer mes performances | Tient 30 min | 8 sem., renouvelable | Chrono sur 5 km (ou 10 km) amélioré vs test de départ |
| Santé & forme / Perte de poids | Tous, y compris N1 marche seule | 8 sem., renouvelable | Régularité tenue, minutes actives par semaine |

« Améliorer mes performances » introduit le travail d'allure : seuil, fractionné long, sortie longue. Limite matérielle à connaître : le Run100 plafonne à 14 km/h (4'17''/km), les fractions rapides sont bornées à cette vitesse.

### Un programme sur mesure, pas un tableau figé
À partir du **même objectif**, deux utilisateurs n'ont pas le même programme. Le générateur combine :
- le **niveau** issu du test (point de départ dans la progression) ;
- les **disponibilités** (2, 3 ou 4 séances, durées par jour) → nombre de semaines et répartition ;
- l'**âge, les gênes et le poids** → progression plus lente, davantage de marche en côte, moins d'impact ;
- les **retours de séance** → le planning se recalcule en cours de route (§2, §3).

**Objectif trop ambitieux pour le niveau** : l'app ne refuse pas, elle allonge. Un sédentaire qui choisit « Courir 10 km » obtient un programme en étapes (10 min → 30 min → 5 km → 10 km) avec la durée réelle annoncée avant de s'engager (« environ 6 mois à 3 séances/semaine ») et une date d'arrivée estimée, mise à jour au fil des semaines.

### Un seul programme à la fois
- Un programme **actif** au maximum. En choisir un autre demande de terminer ou d'arrêter le programme en cours, qui est archivé comme « interrompu » avec son avancement.
- **Pause** possible (vacances, maladie) : le programme se fige et reprend avec la règle de retour après arrêt (§3).
- **Fin de programme** : séance de validation, bilan (avant/après), puis proposition du programme suivant logique (30 min → 45 min ou 5 km) ou d'un programme d'entretien. Jamais de vide après une réussite.

### Programmes effectués
Liste chronologique. Pour chacun : objectif, dates de début et de fin, statut (**réussi / terminé sans validation / interrompu**), séances réalisées sur prévues, VR et FC de repos avant/après, résultat de la séance de validation. Un appui ouvre le détail et la liste des séances du programme.

---

## 7. Historique des séances et statistiques

### Liste
Séances triées par date, filtrables par programme, type de séance et période. Chaque ligne : date, type, durée, distance, ressenti. Les séances hors programme (marche libre) y figurent aussi.

### Fiche d'une séance
| Donnée | Source |
|---|---|
| Durée totale et temps actif, distance | ESP32, sinon calcul à partir des consignes |
| Vitesse moyenne / max, allure moyenne (min/km) | ESP32 |
| Inclinaison, dénivelé équivalent | ESP32 (ou saisie) |
| FC moyenne / max, temps passé par zone, récupération à 1 min | Ceinture |
| Calories estimées | FC + profil si ceinture, sinon vitesse + poids ; toujours affichées comme estimation |
| Réalisé vs prévu, bloc par bloc | Consignes du coach comparées aux mesures |
| Ressenti, douleur, note libre, mot du coach | Bilan de séance |

Graphiques vitesse et FC dans le temps, avec les blocs de la séance en fond. Une donnée non mesurée est marquée « estimée » ou absente — jamais inventée.

### Statistiques cumulées
- Totaux par semaine, mois, programme et depuis le début : séances, temps, distance, calories.
- Tendances : VR, FC de repos, FC à allure donnée, récupération cardiaque.
- Records personnels : plus longue course continue, plus longue distance, meilleur 1 km / 5 km / 10 km.
- Assiduité : séances tenues sur prévues.

---

## 8. Export et synchronisation

### Export d'une séance (manuel)
Depuis la fiche de séance, bouton « Exporter / Partager » :
- **FIT** (format par défaut) — standard Garmin, accepté presque partout ; gère proprement une activité en intérieur sans GPS : sport course à pied, sous-type tapis, vitesse, distance, FC, inclinaison, tours = blocs de la séance.
- **TCX** — alternative XML, également acceptée par Strava et la plupart des plateformes.
- **CSV** — pour un tableur.

Le GPX n'est pas proposé : sans trace GPS il perd la distance sur la plupart des plateformes. Export groupé de tout l'historique (archive ZIP) pour sauvegarde ou changement de téléphone.

### Strava — envoi automatique
- Connexion du compte une fois (OAuth, autorisation d'écriture d'activités), puis envoi automatique du fichier FIT à la fin de chaque séance ; file d'attente et nouvel essai s'il n'y a pas de réseau. Réglage : automatique / demander à chaque fois / désactivé.
- Activité créée comme course en intérieur, titre = nom de la séance, description = programme et semaine.
- **Contrainte technique** : l'API Strava exige une clé d'application. Pour un usage personnel sans serveur, vous créez votre propre « application API » sur votre compte Strava (gratuit, limitée à un seul sportif tant que Strava ne l'a pas validée — suffisant ici) et saisissez la clé dans l'app. Une diffusion publique demanderait un petit serveur relais et la validation de Strava.

### Decathlon Coach — à confirmer
Il n'existe, à notre connaissance, **aucune API publique documentée** permettant à une app tierce d'envoyer une séance vers Decathlon Coach. Le « Hub » Decathlon récupère les activités de Garmin, Polar, Coros et Fitbit, mais pas celles de Strava (le lien Strava ne fonctionne que de Decathlon vers Strava). Stratégie retenue :
1. **Dès la première version** : export FIT + partage Android, pour import manuel dans Decathlon Coach (fonction d'import à vérifier dans l'app sur votre téléphone).
2. **Piste automatique à instruire** : demande d'accès partenaire à l'API Decathlon ; écriture de la séance dans Health Connect, si Decathlon Coach sait le lire (non confirmé).
3. Si aucune piste n'aboutit, l'envoi automatique vers Decathlon Coach ne sera pas possible et l'app le dira clairement plutôt que d'afficher une option qui ne marche pas.

Anti-doublon : si le lien Decathlon → Strava est actif dans votre compte Decathlon, une séance envoyée aux deux pourrait apparaître deux fois sur Strava ; l'app le signale à la connexion.

**Health Connect** (Android) : écriture de chaque séance, pour la retrouver dans Google Fit, Samsung Health, etc.

---

## 9. Capteurs : ceinture cardio et boîtier ESP32

### Ceinture cardiaque thoracique
Toute ceinture Bluetooth Low Energy standard (profil *Heart Rate*, 0x180D : Polar H9/H10, Garmin HRM-Dual, Decathlon, Coospo…). Appairage une fois, reconnexion automatique au début de séance. Les ceintures ANT+ seules ne sont pas compatibles.

### Boîtier ESP32 sur le Domyos Run100
Le Run100 de base (14 km/h, inclinaison manuelle 0/3 %) n'est pas connecté ; le boîtier le rend « lisible ».

| Mesure | Capteur | Montage |
|---|---|---|
| Vitesse | Capteur à effet Hall + petit aimant collé sur le rouleau avant (ou capteur infrarouge + repère sur la bande) | Impulsions comptées par l'ESP32 → vitesse et distance |
| Inclinaison | Accéléromètre (MPU6050 ou équivalent) fixé sur le châssis du plateau | Angle mesuré → 0 % ou 3 % détecté tout seul |

- **Lecture seule, non invasif** : aucun branchement sur l'électronique ni le secteur du tapis, alimentation USB séparée. Le boîtier ne *commande* jamais le tapis — la clé de sécurité et la console restent les seuls maîtres. Monté sur l'embout du rouleau arrière, il ne demande pas d'ouvrir le capot ; si le capot moteur doit être ouvert, vérifier d'abord les conditions de garantie. Guide : [MONTAGE.md](MONTAGE.md).
- **Protocole standard FTMS** (Fitness Machine Service, *Treadmill Data*) : l'app parle un standard, pas un format maison. Bénéfices : le boîtier marche aussi avec Zwift/Kinomap, et l'app marchera plus tard avec n'importe quel tapis connecté (dont le Run100E).
- **Étalonnage guidé dans l'app** : 3 paliers (4, 8, 12 km/h) comparés à l'affichage de la console, puis mise à zéro de l'inclinaison tapis à plat.
- Base existante à adapter plutôt que repartir de zéro : projet open source `lefty01/ESP32_TTGO_FTMS` (vitesse + inclinaison → FTMS).
- **À vérifier sur votre tapis avant de figer le montage** : accès au rouleau avant sous le capot moteur, et présence éventuelle d'un capteur de vitesse d'origine. Si c'est en réalité un Run100**E**, il émet déjà en FTMS et l'ESP32 est inutile.

### Ce que les capteurs changent pour le coaching
- Plus aucune saisie : vitesse, distance, inclinaison et FC sont enregistrées en continu.
- Le coach vérifie que la consigne est réellement suivie et corrige en direct.
- Les adaptations reposent sur des mesures (FC, vitesse réelle) croisées avec le ressenti, plus fiables que le ressenti seul.
- **Mode dégradé** : capteur absent ou déconnecté en séance → l'app continue en mode manuel, sans interrompre.

---

## 10. Architecture envisagée (pour la phase de développement)

- **Kotlin + Jetpack Compose**, 100 % hors ligne, sans compte, données sur le téléphone (Room).
- **Couche Bluetooth LE** : deux clients standards (Heart Rate, FTMS) derrière une interface commune, simulables pour les tests.
- **Firmware ESP32** (Arduino/PlatformIO, C++) dans un dossier `firmware/` du même dépôt.
- **Modèle de données** : Profil · Programme (un seul actif, les autres archivés) · Séance planifiée · Séance réalisée (résumé + échantillons vitesse/FC/inclinaison à 1 Hz) · Tours.
- **Export** : encodeurs FIT / TCX / CSV en Kotlin pur, testés par relecture des fichiers produits ; envoi Strava en tâche de fond (WorkManager) avec file d'attente.
- **Moteur de coaching en Kotlin pur**, séparé de l'interface : bilan → niveau, générateur de programme et de semaine, règles d'adaptation. Entièrement couvert par tests unitaires (c'est le cœur du produit).
- Notifications et rappels via WorkManager ; synthèse vocale Android pour le guidage.
- Compilation de l'APK par GitHub Actions (pas de SDK Android dans cet environnement).

### Feuille de route
- **MVP** : bilan, test initial, programmes « Courir 10 min » et « Courir 30 min », un seul programme actif, séance guidée, retour de séance, prochaines séances, rappels, historique avec fiche de séance, export FIT/TCX manuel, **ceinture cardio**.
- **V1** : **boîtier ESP32** (firmware + étalonnage), reste du catalogue (45 min, 1 h, 5 km, 10 km, performances), programmes effectués, statistiques cumulées et records, **envoi automatique Strava**, Health Connect.
- **V2** : Decathlon Coach automatique (si une voie est confirmée), lecture de l'agenda, renforcement au poids du corps en complément.

---

## 11. Suite

1. Retours sur ce concept (programmes, statistiques, ton du coach, priorités du MVP).
2. Vérifications : modèle exact du tapis (Run100 ou Run100E), accès au rouleau avant, présence d'un import de fichier dans Decathlon Coach.
3. Développement du MVP, en commençant par le moteur de coaching et ses tests.

**Limite à garder en tête** : l'app donne des conseils d'entraînement, pas un avis médical — d'où le questionnaire santé et les consignes d'arrêt.

## Sources

- Caractéristiques du Run100 : [Decathlon support](https://support.decathlon.co.uk/run100), [fiche Run100](https://decathlon.ch/en/p/tapis-de-course-14km-h-et-compact-run100/_/R-p-346612)
- Run100E et FTMS : [Decathlon/domyos-developers](https://www.github.com/Decathlon/domyos-developers)
- Boîtier ESP32 → FTMS : [lefty01/ESP32_TTGO_FTMS](https://www.github.com/lefty01/ESP32_TTGO_FTMS), [Hackaday — Add Bluetooth to Treadmill](https://hackaday.io/project/175237-add-bluetooth-to-treadmill)
- Strava : [envoi d'activités](https://developers.strava.com/docs/uploads/), [limites de l'API](https://developers.strava.com/docs/rate-limits/)
- Decathlon : [FAQ du Hub](https://hub.decathlon.com/faq), [FitnessSyncer — Decathlon Coach](https://www.fitnesssyncer.com/support/decathlon-coach)
