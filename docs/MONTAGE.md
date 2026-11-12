# Monter le boîtier sur un Domyos Run100

Le Run100 classique n'est pas connecté. Ce boîtier compte les tours d'un rouleau et envoie la vitesse et la distance au téléphone en Bluetooth, au standard FTMS. Il **lit seulement** : il ne se branche pas sur l'électronique du tapis et ne le commande jamais. La console et la clé de sécurité restent seules maîtres.

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
Module KY-003            Carte ESP32
  +  (VCC)  ───────────  VIN (5 V)
  -  (GND)  ───────────  GND
  S  (signal) ─────────  GPIO 27
```

- Le capteur A3144 a besoin de 5 V : il est alimenté par la broche VIN, qui reçoit le 5 V de l'USB.
- Sa sortie ne fait que tirer le signal vers la masse : la carte la ramène en 3,3 V elle-même. Pas de résistance à ajouter.
- Rien d'autre n'est relié au tapis.

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
- **Ne jamais** toucher à la carte de contrôle ni au moteur du tapis, ni alimenter l'ESP32 depuis le tapis.
- **Ce qui n'a pas été vérifié** : le montage sur un vrai Run100 (accès à l'embout du rouleau, place pour le capteur), la détection de l'aimant et la connexion Bluetooth avec le téléphone. Le programme est compilé et sa logique de calcul est testée, mais il n'a pas tourné sur une vraie carte.
