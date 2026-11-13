# Contrat d'import de programme : `relance.program`, version 1

Format d'échange d'un programme Relance, pour l'import et l'export dans l'interface coach et pour sa génération par un agent (Claude, ChatGPT…).

| Fichier | Rôle |
|---|---|
| [`program-import-v1.schema.json`](program-import-v1.schema.json) | Schéma JSON (2020-12), strict : tout champ inconnu est refusé. Publié sur https://relance-coach.netlify.app/schemas/program-import-v1.json |
| [`AGENT.md`](AGENT.md) | Instructions à donner à un agent : format, types de séance, règles de coach, vérifications. |
| [`examples/courir-30-min-tapis.json`](examples/courir-30-min-tapis.json) | Exemple complet : programme de 25 semaines transposé d'un document de coach. |

## Principes

- **Un fichier = un programme**, avec une **variante par niveau** (N1 à N4, toutes facultatives) et les types de séance nouveaux qu'il utilise (`sessionTypes`).
- **Mêmes noms que le moteur** : un programme exporté se réimporte à l'identique.
- **Vitesses** : relatives à la vitesse de référence du sportif (conseillé) ou fixes en km/h (`pace: "kmh"`), toujours plafonnées à la vitesse maximale de son tapis.
- **Endurance par palier** : un palier peut fixer sa propre séance d'endurance (`steps[i].easy`), par exemple une marche dont la durée suit un tableau.
- **Durées** : décimales autorisées (1,25 min = 1 min 15 s), affichées avec `{param:min}`.

## À l'import

1. Le JSON est vérifié contre le schéma. En cas d'erreur, rien n'est importé et chaque erreur indique son chemin (`/program/variants/N2/steps/3/sessions/0/params/a : doit être de type number`). Un bouton copie ces erreurs pour les renvoyer à l'agent.
2. **Types de séance** : un type identique à un type existant est réutilisé ; un type qui porte l'id d'un type existant mais diffère est importé sous un nouvel id (`…-import`), et le programme est mis à jour en conséquence.
3. **Programme** : si son id existe déjà, le coach choisit de le remplacer dans le brouillon ou de l'importer en copie.
4. **Contrôles** : les garde-fous de coach (+10 %, un seul curseur, 48 h, séance trop longue…) sont affichés. Seules les erreurs bloquent.
5. Le programme arrive **masqué** dans le brouillon. Le coach le vérifie dans le simulateur, le rend visible, puis publie. Rien n'atteint les téléphones avant.

Limites : 1 Mo, 60 paliers par variante, 20 types de séance nouveaux.

## Évolutions

Une modification incompatible du format passera à `formatVersion: 2`, avec un nouveau schéma. L'interface acceptera alors les deux versions le temps de la transition.
