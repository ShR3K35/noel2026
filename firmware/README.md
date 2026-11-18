# Programme du boîtier Relance Run100

Programme pour carte ESP32 : il compte les tours de rouleau (capteur à effet Hall sur GPIO 27) et publie vitesse et distance en Bluetooth Low Energy, au standard FTMS (*Treadmill Data*). Si les optocoupleurs de l'option « réglage de la vitesse » sont câblés (GPIO 25, 26, 32), il accepte aussi une consigne de vitesse (FTMS *Control Point*). Le montage est décrit dans [`docs/MONTAGE.md`](../docs/MONTAGE.md).

## Charger le programme

```bash
pip install platformio
cd firmware
pio run -t upload        # compile et envoie sur la carte branchée en USB
pio device monitor       # journal : vitesse, distance, état de la connexion
```

## Tests

```bash
pio test -e native       # logique de mesure et de réglage, sur l'ordinateur
```

## Commandes au clavier

Dans `pio device monitor`, pour vérifier le câblage des touches sans application (tapis démarré sur la console) :

| Commande | Effet |
|---|---|
| `v 6.5` | consigne à 6,5 km/h |
| `+` / `-` | un appui sur + ou − |
| `s` | appui sur stop |
| `x` | abandonner la consigne, sans toucher au tapis |

## Ce que le boîtier publie

| Service | Caractéristique | Contenu |
|---|---|---|
| Fitness Machine `0x1826` | Treadmill Data `0x2ACD` (notification, 1 par seconde) | flags `0x0004`, vitesse en 0,01 km/h, distance totale en m |
| | Fitness Machine Feature `0x2ACC` | distance totale ; consigne de vitesse |
| | Supported Speed Range `0x2AD4` | 1 à 14 km/h, pas de 0,1 |
| | Fitness Machine Control Point `0x2AD9` (écriture, indication) | Request Control, Reset, Set Target Speed, Start (refusé si la bande est arrêtée), Stop/Pause |
| | Fitness Machine Status `0x2ADA` (notification) | consigne changée, arrêt, remise à zéro |
| Relance `6f1e0001-…` | Distance par tour `6f1e0002-…` (lecture, écriture) | uint32 en micromètres, gardé en mémoire flash |

- Le nom annoncé est « Relance Run100 ».
- La distance par tour vaut par défaut π × 50 mm ; l'app la corrige à l'étalonnage. Les valeurs hors 60–380 mm sont refusées.
- Une application doit d'abord demander la main (*Request Control*) ; une seule l'a à la fois. Si elle se déconnecte, le boîtier arrête d'appuyer et le tapis garde sa vitesse.
- *Start* ne démarre jamais le tapis : il réussit si la bande tourne déjà, échoue sinon. Une consigne est refusée bande arrêtée (*Operation Failed*).
- Sans optocoupleurs câblés, les commandes sont acceptées mais sans effet sur le tapis.

## Réglages dans `include/speed.h`

- `DEBOUNCE_MS` : impulsions plus rapprochées ignorées (rebonds), 5 ms.
- `WINDOW_MS` : fenêtre de calcul de la vitesse, 2 s.
- `STOP_MS` : délai sans impulsion avant d'annoncer 0 km/h, 2 s.

## Réglages dans `include/control.h`

- `PRESS_MS` / `RELEASE_MS` : durée d'un appui et pause entre deux appuis, 150 et 200 ms. À allonger si la console rate des appuis.
- `MIN_TARGET_KMH` / `MAX_TARGET_KMH` : consignes acceptées, 1 à 14 km/h. Baisser la limite haute pour brider le tapis.
- `TOLERANCE_KMH`, `MAX_CORRECTIONS` : écart accepté après réglage (0,15 km/h) et nombre de séries de correction (2).
- `OVERRIDE_KMH` / `OVERRIDE_MS` : écart qui signale un changement à la console, et sa durée (0,6 km/h pendant 3 s) ; le boîtier lâche alors la main.
- `OVERSPEED_KMH` / `OVERSPEED_MS` : au-delà de 15 km/h mesurés pendant 2 s, appui sur stop.
