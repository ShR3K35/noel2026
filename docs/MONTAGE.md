# Monter le boîtier sur un Domyos Run100

Le Run100 classique n'est pas connecté. Ce boîtier compte les tours d'un rouleau et envoie la vitesse et la distance au téléphone en Bluetooth, au standard FTMS. Dans sa version de base, il **lit seulement** : il ne se branche pas sur l'électronique du tapis et ne le commande jamais.

En option (§ 7), trois optocoupleurs soudés sur les touches +, − et stop de la console lui permettent aussi de **régler la vitesse**. Même alors, il ne démarre jamais le tapis, et la console et la clé de sécurité restent prioritaires.

> Un Run100**E** (version connectée) émet déjà ces données : pas de boîtier à fabriquer.

## 1. Liste d'achat

Prix en ordre de grandeur, environ 20 € au total.

| Élément | Détail | Prix |
|---|---|---|
| Carte ESP32 DevKit | modèle « ESP32-WROOM-32 DevKit V1 », 30 ou 38 broches | ~8 € |
| Module capteur à effet Hall | KY-003 (puce A3144), avec sa LED témoin | ~2 € |
| Aimant néodyme | rond, 6 × 3 mm ; en prendre quelques-uns | ~1 € |
| Câble | 3 fils souples, environ 1,5 m (câble de capteur ou fils Dupont femelle) | ~3 € |
| Fixation | colle époxy bi-composant, serre-câbles, adhésif double face épais | ~5 € |
| Boîtier | petite boîte plastique pour l'ESP32 | ~3 € |
| Alimentation | chargeur USB 5 V de téléphone et câble micro-USB ou USB-C selon la carte | déjà chez toi |

## 2. Câblage

```
Module KY-003              Carte ESP32
  -  (GND)       ────────  GND
  broche du milieu ──────  3V3
  S  (signal)    ────────  GPIO 27
```

- Le module KY-003 porte sa propre résistance de tirage vers son alimentation : alimenté en 5 V, sa sortie S monterait à 5 V, au-delà de ce que supporte une entrée de l'ESP32 (3,3 V). On l'alimente donc en **3,3 V**.
- **Vérifie** en approchant l'aimant : la LED du module doit s'allumer.
- **Si elle ne s'allume pas** : la puce A3144 est prévue pour 4,5 V au minimum et certaines ne réagissent pas en 3,3 V. Passe alors la broche du milieu sur **VIN (5 V)** et ajoute une **résistance de 15 kΩ entre S et GND**. Mesure S au multimètre sans aimant : environ 3 V, jamais plus de 3,3 V.
- Rien d'autre n'est relié au tapis (sauf l'option du § 7).

## 3. Charger le programme dans l'ESP32

Une seule fois, depuis un ordinateur, avec [PlatformIO](https://platformio.org/install/cli) :

```bash
cd firmware
pio run -t upload       # carte branchée en USB
pio device monitor      # affiche la vitesse mesurée chaque seconde
```

Le détail est dans [`firmware/README.md`](../firmware/README.md).

## 4. Pose sur le tapis

1. **Débranche la prise** et retire la clé de sécurité.
2. **Choisis le rouleau.** Commence par le **rouleau arrière** : son embout est accessible sans ouvrir le capot moteur, loin du 230 V et du moteur, dont le champ magnétique peut gêner le capteur. Le rouleau avant ne sert qu'en dernier recours ; dans ce cas, vérifie d'abord ce que dit la garantie avant d'ouvrir le capot.
3. **Colle l'aimant** à l'époxy sur la face ou le bord de l'embout du rouleau qui tourne, pas sur la pièce fixe. Laisse sécher selon la notice de la colle.
4. **Fixe le capteur** sur le châssis, face au passage de l'aimant, à **2 à 5 mm**. La face imprimée du composant A3144 doit regarder l'aimant. Il ne doit jamais toucher le rouleau.
5. **Teste à la main** : branche la carte en USB et fais tourner le rouleau lentement. La LED du module KY-003 doit s'allumer à chaque passage de l'aimant, et la LED bleue de l'ESP32 change d'état à chaque tour. Si rien ne s'allume, retourne l'aimant (l'A3144 ne réagit qu'à un pôle) ou rapproche le capteur.
6. **Range le câble** le long du cadre avec des serre-câbles, à l'écart de la bande, des parties mobiles et du mécanisme de pliage.
7. **Place l'ESP32** dans son boîtier, à l'extérieur du tapis, près d'une prise.
8. Remets la clé, rebranche, et fais un essai à 3 km/h en restant sur les repose-pieds.

## 5. Mise en service dans l'app Relance

1. Profil → Capteurs → **Chercher mes capteurs** : « Relance Run100 » apparaît. Touche-le pour l'associer.
2. Dans la même page, **Étalonner le tapis** :
   - reste debout sur les repose-pieds, la bande tourne à vide ;
   - règle la console sur 3 km/h, attends que la mesure se stabilise, valide ;
   - recommence à 4,5 puis 6 km/h ;
   - enregistre : le réglage est gardé dans le boîtier, même débranché.
3. Pendant les séances, la vitesse du tapis s'affiche à côté de la consigne et le coureur animé la suit.

Si tu changes l'aimant de place ou de rouleau, refais l'étalonnage.

## 6. À savoir

- **Inclinaison** : elle n'est pas mesurée. L'app utilise l'inclinaison prévue par la séance (0 ou 3 % sur le Run100).
- **Autres applications** : le boîtier parle le standard FTMS ; Zwift ou Kinomap devraient le voir comme un tapis connecté (non testé).
- **Ne jamais** toucher à la carte de contrôle ni au moteur du tapis, ni alimenter l'ESP32 depuis le tapis. L'option du § 7 ne touche qu'aux touches de la console, en basse tension, à travers des optocoupleurs.
- **Ce qui n'a pas été vérifié** : le montage sur un vrai Run100 (accès à l'embout du rouleau, place pour le capteur), la détection de l'aimant et la connexion Bluetooth avec le téléphone. Le programme est compilé et sa logique de calcul est testée, mais il n'a pas tourné sur une vraie carte.

## 7. Option : régler la vitesse depuis le boîtier

L'ESP32 « appuie » sur les touches de la console à travers des optocoupleurs : il n'y a aucun lien électrique entre la carte et le tapis. Les touches d'origine continuent de fonctionner.

### Ce qu'il faut en plus

| Élément | Détail | Prix |
|---|---|---|
| Modules optocoupleur PC817, 2 voies | type « 817 Module 2 Channel Isolation » (Hailege ou équivalent) ; 2 modules pour 3 voies | ~6 € les 2 |
| Fil fin | 4 à 6 fils souples d'environ 30 cm vers la console, fer à souder | — |

### Le clavier du Run100

Les touches sont une membrane plate (sans course, mais à pression : un crayon suffit), reliée à la carte de la console par une nappe sur le connecteur **KEY**. C'est une matrice : chaque touche relie une colonne K1…K6 à une ligne S1…S4. Mesuré sur un Run100 (programme `V18_20200728_run100`) :

- **colonnes K** à 5 V au repos (entrées tirées vers le haut), **lignes S** scrutées (environ 1,5 V en moyenne au multimètre) ;
- **+ = K2–S1**, **− = K1–S1** ;
- stop : paire à repérer (voir ci-dessous).

Les pastilles dorées juste au-dessus du connecteur KEY, marquées K6…K1 et S1…S4, servent de points de soudure. L'emplacement vide **H_SPD** (K2, S5, K1), prévu pour des touches de vitesse sur les poignées, ne fonctionne pas : la ligne S5 est scrutée mais le programme de la console ignore ces touches. H_INC sert à une inclinaison motorisée que ce modèle n'a pas.

### Câblage

Pour chaque voie, l'entrée (IN, G) est côté ESP32, la sortie côté console. Le courant va de la colonne K vers la ligne S : la sortie OUT va sur la pastille K, le G de sortie sur la pastille S.

| Touche | Voie | IN | G (entrée) | Sortie OUT | G (sortie) |
|---|---|---|---|---|---|
| + | module 1, voie 1 | VIN (5 V) | GPIO 25 | pastille K2 | pastille S1 |
| − | module 1, voie 2 | VIN (5 V) | GPIO 26 | pastille K1 | pastille S1 |
| stop (facultatif) | module 2, voie 1 | VIN (5 V) | GPIO 32 | pastille K de la paire stop | pastille S de la paire stop |

- Les deux G de sortie de + et − arrivent sur la même pastille S1.
- Sans la voie stop, tout fonctionne : seul l'appui sur stop en cas de survitesse est sans effet, et la clé de sécurité reste l'arrêt prioritaire.

- « Sortie OUT » : la borne de sortie qui n'est ni V/VCC ni G (son nom varie selon les modules). La borne V/VCC de sortie reste vide. La quatrième voie reste libre : **start n'est volontairement pas câblé**, le boîtier ne peut pas démarrer le tapis.
- Pourquoi IN au 5 V et G sur le GPIO : ces modules demandent au moins 3,6 V en entrée, une sortie de l'ESP32 n'en donne que 3,3. L'ESP32 met donc la borne G à la masse pour « appuyer » (GPIO à l'état bas) ; à l'état haut, il reste trop peu de tension pour allumer l'optocoupleur.

### Avant de souder

1. **Retire les cavaliers jaunes** des deux modules. Selon les versions, ils relient la masse d'entrée à celle de sortie ou ajoutent une résistance de tirage en sortie : dans les deux cas, ils sont à enlever.
2. **Vérifie que les G d'entrée sont séparés** : au multimètre en mode continuité, entre le G de la voie 1 et celui de la voie 2 d'un même module. S'ils sont reliés, ce câblage ne convient pas (un appui déclencherait les deux voies) : il faut alors un transistor NPN par voie.
3. **Repère la paire stop** (si tu la câbles) : tapis débranché, nappe sortie du connecteur KEY. Ses contacts sont dans l'ordre K6, K5, K4, K3, K2, K1, S1, S2, S3, S4. Multimètre en continuité ou en résistance, maintiens STOP enfoncé et cherche la paire K–S qui conduit.
4. **Vérifie chaque paire avant de souder** : nappe rebranchée, bande à 1 km/h, relie franchement les deux pastilles pendant une demi-seconde avec un fil : la vitesse doit changer.
5. **Soude** les fils sur les pastilles au-dessus du connecteur KEY, sans toucher à la nappe. Fais sortir les fils par un passage existant, à l'écart de la bande.
6. **Si la console ne réagit pas aux appuis de l'ESP32**, inverse les deux fils de sortie de la voie (OUT et G). Si ça ne suffit pas, remplace le PC817 par un relais statique PhotoMOS (AQY212, TLP222A), qui conduit dans les deux sens.

### Essai sans application

Carte en USB, `pio device monitor` ouvert, tapis démarré à 3 km/h sur la console (en restant sur les repose-pieds) :

- `+` puis `-` : la console doit monter puis redescendre de 0,1 km/h ;
- `v 4` : le boîtier enchaîne les appuis, attend que la bande se stabilise, corrige si besoin et affiche « Consigne atteinte » ;
- `s` : le tapis s'arrête.

### Dans l'app

Pour vérifier les optocoupleurs : Profil → Capteurs → **Étalonner le tapis**, carte « Tester les optocoupleurs ». Chaque appui sur − ou + appuie une fois sur la touche de la console (0,1 km/h) ; la vitesse mesurée doit suivre en quelques secondes. Le boîtier n'appuie jamais sur start.

Profil → Capteurs → **Régler la vitesse pendant les séances : Oui** (le choix apparaît quand le boîtier est connecté). Pendant une séance guidée, la vitesse de chaque bloc est alors envoyée au boîtier. Démarre toujours le tapis sur la console : la vitesse se règle dès que la bande tourne. Les tests d'allure, où tu cherches toi-même ta vitesse, restent à régler à la main.

### Ce que fait le boîtier, et ce qu'il refuse

- Il **ne démarre jamais** le tapis : une consigne est refusée tant que la bande est arrêtée.
- Il part de la vitesse mesurée, appuie (un appui toutes les 0,35 s), attend la stabilisation, puis corrige au plus deux fois. La précision dépend donc de l'étalonnage du § 5.
- Si quelqu'un change la vitesse sur la console, il **lâche la main** au bout de 3 s et n'appuie plus.
- Si la bande s'arrête (stop, clé retirée), il abandonne la consigne.
- Si l'application se déconnecte, il arrête d'appuyer : le tapis garde sa vitesse.
- Si la vitesse mesurée dépasse 15 km/h pendant 2 s, il appuie sur **stop**.
- La clé de sécurité n'est jamais touchée et reste le moyen d'arrêt prioritaire.

**Vérifié sur un Run100** : les paires + (K2–S1) et − (K1–S1), en reliant les pastilles à la main. **Non vérifié** : les appuis par optocoupleur et leur durée de 150 ms, la touche stop, et la commande depuis une application. La logique de réglage est testée sur PC avec un tapis simulé.
