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
| Objectif | Santé & forme · Perte de poids · Courir 30 min sans s'arrêter · Courir 5 km | Choix de la filière de programme |
| Tapis | Préréglé Domyos Run100 : 14 km/h max, inclinaison manuelle 0 % ou 3 % | Les séances ne proposent jamais un réglage impossible |
| Capteurs | Appairage ceinture cardio + boîtier ESP32 (facultatifs, voir §6) ; FC de repos mesurée 2 min assis | Zones cardiaques personnelles, suivi automatique |
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
2. **Séance guidée** — très gros chiffres lisibles depuis le tapis : *vitesse cible* à côté de la *vitesse réelle* (ESP32), *FC et zone* en couleur (ceinture), inclinaison, compte à rebours du bloc, bloc suivant. Le bloc ne démarre que lorsque la vitesse cible est atteinte. Annonce vocale + vibration à chaque changement, écran toujours allumé, bouton « trop dur » (baisse la suite de la séance de 5–10 %).
3. **Bilan de séance** — les 3 questions de ressenti + un mot du coach.
4. **Ma semaine** — calendrier, séances déplaçables par glisser-déposer.
5. **Progrès** — évolution de la VR, minutes de course continue, régularité (semaines tenues, pas de « série » qui se casse au premier raté).
6. **Profil** — objectif, disponibilités, tapis, gênes déclarées.

Ton du coach : direct, bienveillant, jamais culpabilisant. Il explique toujours *pourquoi* (« semaine allégée : c'est là que ton corps assimile »).

---

## 5. Exemple : N2, objectif « courir 30 min », 3 × 30 min/semaine

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

## 6. Capteurs : ceinture cardio et boîtier ESP32

### Ceinture cardiaque thoracique
Toute ceinture Bluetooth Low Energy standard (profil *Heart Rate*, 0x180D : Polar H9/H10, Garmin HRM-Dual, Decathlon, Coospo…). Appairage une fois, reconnexion automatique au début de séance. Les ceintures ANT+ seules ne sont pas compatibles.

### Boîtier ESP32 sur le Domyos Run100
Le Run100 de base (14 km/h, inclinaison manuelle 0/3 %) n'est pas connecté ; le boîtier le rend « lisible ».

| Mesure | Capteur | Montage |
|---|---|---|
| Vitesse | Capteur à effet Hall + petit aimant collé sur le rouleau avant (ou capteur infrarouge + repère sur la bande) | Impulsions comptées par l'ESP32 → vitesse et distance |
| Inclinaison | Accéléromètre (MPU6050 ou équivalent) fixé sur le châssis du plateau | Angle mesuré → 0 % ou 3 % détecté tout seul |

- **Lecture seule, non invasif** : aucun branchement sur l'électronique ni le secteur du tapis, alimentation USB séparée. Le boîtier ne *commande* jamais le tapis — la clé de sécurité et la console restent les seuls maîtres. Pas de perte de garantie.
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

## 7. Architecture envisagée (pour la phase de développement)

- **Kotlin + Jetpack Compose**, 100 % hors ligne, sans compte, données sur le téléphone (Room).
- **Couche Bluetooth LE** : deux clients standards (Heart Rate, FTMS) derrière une interface commune, simulables pour les tests.
- **Firmware ESP32** (Arduino/PlatformIO, C++) dans un dossier `firmware/` du même dépôt.
- **Moteur de coaching en Kotlin pur**, séparé de l'interface : bilan → niveau, générateur de semaine, règles d'adaptation. Entièrement couvert par tests unitaires (c'est le cœur du produit).
- Notifications et rappels via WorkManager ; synthèse vocale Android pour le guidage.
- Compilation de l'APK par GitHub Actions (pas de SDK Android dans cet environnement).

### Feuille de route
- **MVP** : bilan, test initial, programmes N1–N3, séance guidée, retour de séance, planification hebdo, rappels, **ceinture cardio** (standard, sans matériel à fabriquer).
- **V1** : **boîtier ESP32** (firmware + étalonnage), écran Progrès avec courbes FC/vitesse, re-tests, lecture de l'agenda, filières perte de poids et 5 km.
- **V2** : export Health Connect / Strava, renforcement au poids du corps en complément.

---

## 8. Suite

1. Retours sur ce concept (niveaux, objectifs, ton du coach, priorités du MVP).
2. Vérification du tapis : modèle exact (Run100 ou Run100E), accès au rouleau avant.
3. Développement du MVP, en commençant par le moteur de coaching et ses tests.

**Limite à garder en tête** : l'app donne des conseils d'entraînement, pas un avis médical — d'où le questionnaire santé et les consignes d'arrêt.

## Sources

- Caractéristiques du Run100 : [Decathlon support](https://support.decathlon.co.uk/run100), [fiche Run100](https://decathlon.ch/en/p/tapis-de-course-14km-h-et-compact-run100/_/R-p-346612)
- Run100E et FTMS : [Decathlon/domyos-developers](https://www.github.com/Decathlon/domyos-developers)
- Boîtier ESP32 → FTMS : [lefty01/ESP32_TTGO_FTMS](https://www.github.com/lefty01/ESP32_TTGO_FTMS), [Hackaday — Add Bluetooth to Treadmill](https://hackaday.io/project/175237-add-bluetooth-to-treadmill)
