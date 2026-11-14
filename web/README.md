# Relance · Coach

Interface d'administration des programmes de l'app Relance, et API qui les sert aux téléphones. Hébergée sur Netlify : pages React, fonctions Netlify, stockage Netlify Blobs.

## Ce qu'on y fait

- **Programmes** : créer, dupliquer, masquer, ordonner ; régler l'objectif, les prérequis, à qui le programme est conseillé ; construire la progression palier par palier (niveaux N1 à N4 concernés, séances clés alternées, générateur de paliers) ; régler la semaine type (séance clé, endurance, récupération selon 1 à 4 séances), les semaines allégées et le mot du coach par semaine.
- **Types de séance** : les briques des programmes, bloc par bloc (échauffement, marche, côte, trot, allure soutenue, répétitions), avec des allures relatives à la vitesse de référence du sportif. Aperçu immédiat sur des profils types.
- **Simulateur** : le planning exact que calculera le téléphone pour un profil (niveau, vitesse de référence, jours, temps disponible, pente, vitesse maximale du tapis), semaine par semaine et séance par séance.
- **Allures** : les coefficients qui transforment la vitesse de référence en allures de marche, de côte et de course.
- **Garde-fous** : erreurs qui bloquent la publication (type de séance inconnu, paramètre manquant…) et points d'attention de coach (plus de +10 % de course d'une semaine à l'autre, deux curseurs qui bougent ensemble, séance plus longue que le temps disponible, deux séances de course à moins de 48 h, pas d'échauffement…).
- **Import / export** : un programme s'importe depuis un fichier JSON au format `relance.program` ([contrat](../contract/README.md)) et s'exporte dans ce même format. Le schéma, le guide pour un agent et un exemple sont publiés avec le site (`/schemas/program-import-v1.json`, `/contract/AGENT.md`, `/contract/examples/`).
- **Publication** : le brouillon s'enregistre tout seul ; « Publier » crée une version, que les téléphones récupèrent une fois par jour ou à la demande. Historique et retour à une version précédente.

Le profil du sportif ne quitte jamais son téléphone : le serveur ne stocke que les programmes, et c'est le téléphone qui les adapte.

## Jeu de Noël

Le menu **Jeu 🎅** de l'interface lance « La Grande Tournée du Père Noël », un jeu de plates-formes en 2D sur trois niveaux (village enneigé, forêt boréale sous les aurores, toits de la ville), jouable sur téléphone, tablette et ordinateur. Il s'ouvre en plein écran par-dessus l'interface ; la croix ramène aux programmes.

- **Commandes** : sur écran tactile, un joystick analogique à gauche (il apparaît sous le pouce, n'importe où dans la moitié gauche de l'écran ; pousser un peu fait marcher, à fond fait courir) et les boutons Saut et Lancer (quand on a l'étoile) à droite. Au clavier : flèches ou ZQSD/WASD, Espace pour sauter, X pour lancer, Échap pour la pause. Manette : stick analogique.
- **Règles** : on récolte des clochettes (100 = une vie), on saute sur les bonshommes de neige, pingouins et corbeaux, on frappe les cadeaux par-dessous (clochette, étoile qui donne des boules de neige, chocolat chaud qui rend un cœur). Points de passage en cours de niveau ; le traîneau termine les deux premiers niveaux, la dernière cheminée termine le jeu.
- **Technique** : TypeScript et Canvas 2D sans dépendance (`src/game/`). Graphismes vectoriels et sons synthétisés en direct, aucune image ni fichier audio à charger ; musique : refrain de « Jingle Bells » (domaine public). La simulation (`world.ts`) est séparée du rendu et testée sous Vitest (`world.test.ts` : hauteur de saut, coyote time, tampon de saut, écrasement, trous, accessibilité des niveaux). Progression et réglages (musique, secousses) gardés dans le navigateur.
- Le réglage du saut et la structure suivent les skills de jeu vidéo installés dans `.claude/skills/` ([awesome-gamedev-agent-skills](https://github.com/gamedev-skills/awesome-gamedev-agent-skills), Apache-2.0) : `platformer` (constantes calculées avec `jump_tuner.py`), `game-feel`, `input-systems`, `camera-systems`.

## API

| Route | Accès | Rôle |
|---|---|---|
| `GET /api/catalog` | public | Catalogue publié (ETag, cache 5 min). Lu par l'app. |
| `POST /api/admin/login` | mot de passe | Renvoie un jeton valable 12 h. |
| `GET` / `PUT /api/admin/draft` | jeton | Lire / enregistrer le brouillon. |
| `POST /api/admin/publish` | jeton | Publier le brouillon (refusé s'il contient des erreurs). |
| `GET /api/admin/history`, `GET /api/admin/history/:version` | jeton | Historique des versions. |
| `POST /api/admin/restore` | jeton | Reprendre une version (0 = catalogue initial) dans le brouillon. |

## En ligne

Site : **https://relance-coach.netlify.app** (compte Netlify `shr3k35`). Le mot de passe est la variable `ADMIN_PASSWORD` du site (Netlify → Site configuration → Environment variables), modifiable à tout moment ; changer `SESSION_SECRET` déconnecte toutes les sessions ouvertes.

Redéployer depuis ce dossier, avec la CLI Netlify connectée au compte :

```bash
npm run build
npx netlify deploy --prod --dir=dist --functions=netlify/functions --site relance-coach
```

## Autre option : déploiement automatique depuis GitHub

1. Sur app.netlify.com : **Add new site → Import an existing project → GitHub**, puis le dépôt `noel2026`.
2. Réglages de build :
   - **Branch** : la branche qui contient ce dossier ;
   - **Base directory** : `web` (le fichier `web/netlify.toml` fournit le reste : `npm run build`, dossier `dist`, fonctions dans `netlify/functions`).
3. **Site configuration → Change site name** : `relance-coach`, pour obtenir `https://relance-coach.netlify.app` (l'adresse par défaut de l'app).
4. **Site configuration → Environment variables** :
   - `ADMIN_PASSWORD` : le mot de passe de l'interface ;
   - `SESSION_SECRET` : une longue chaîne aléatoire (sert à signer les connexions).
   Puis **Deploys → Trigger deploy**.
5. Ouvrir le site et se connecter. Tant que rien n'est publié, l'API sert le catalogue initial (`catalog/catalog.json`).

Si le site porte un autre nom, régler l'adresse dans l'app : **Profil → Serveur des programmes** (`https://<nom>.netlify.app/api/catalog`).

## Développer

```bash
npm install
npm run local    # interface + API en mémoire sur http://127.0.0.1:5173 (mot de passe : coach)
npm test         # moteur, garde-fous, API
npm run build
```

Le moteur `src/engine/engine.ts` est un port du moteur de l'app (`app/src/main/java/fr/relance/app/core/Catalog.kt` et `Coach.kt`). `src/engine/fixtures.json`, écrit par `EngineFixturesTest.kt` côté app, garantit que le simulateur calcule exactement comme le téléphone : après une modification du moteur de l'app, relancer ses tests puis `npm test`.
