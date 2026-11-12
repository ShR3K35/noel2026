# Programme du boîtier Relance Run100

Programme pour carte ESP32 : il compte les tours de rouleau (capteur à effet Hall sur GPIO 27) et publie vitesse et distance en Bluetooth Low Energy, au standard FTMS (*Treadmill Data*). Le montage est décrit dans [`docs/MONTAGE.md`](../docs/MONTAGE.md).

## Charger le programme

```bash
pip install platformio
cd firmware
pio run -t upload        # compile et envoie sur la carte branchée en USB
pio device monitor       # journal : vitesse, distance, état de la connexion
```

## Tests

```bash
pio test -e native       # logique de mesure, sur l'ordinateur
```

## Ce que le boîtier publie

| Service | Caractéristique | Contenu |
|---|---|---|
| Fitness Machine `0x1826` | Treadmill Data `0x2ACD` (notification, 1 par seconde) | flags `0x0004`, vitesse en 0,01 km/h, distance totale en m |
| | Fitness Machine Feature `0x2ACC` | distance totale |
| | Supported Speed Range `0x2AD4` | 0,8 à 14 km/h |
| Relance `6f1e0001-…` | Distance par tour `6f1e0002-…` (lecture, écriture) | uint32 en micromètres, gardé en mémoire flash |

- Le nom annoncé est « Relance Run100 ».
- La distance par tour vaut par défaut π × 50 mm ; l'app la corrige à l'étalonnage. Les valeurs hors 60–380 mm sont refusées.
- Aucune commande vers le tapis n'est implémentée (pas de *Control Point*) : le boîtier lit seulement.

## Réglages dans `include/speed.h`

- `DEBOUNCE_MS` : impulsions plus rapprochées ignorées (rebonds), 5 ms.
- `WINDOW_MS` : fenêtre de calcul de la vitesse, 2 s.
- `STOP_MS` : délai sans impulsion avant d'annoncer 0 km/h, 2 s.
