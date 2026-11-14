// Circuits : une ligne centrale fermée (spline de Catmull-Rom passant par des points de contrôle),
// rééchantillonnée à pas constant. Tout est en mètres, vu de dessus (x, z). Aucun accès au DOM.

export type TrackTheme = "north" | "lake" | "village" | "stars";

export interface TrackDef {
  id: string;
  name: string;
  tagline: string;
  theme: TrackTheme;
  difficulty: 1 | 2 | 3;
  halfWidth: number;
  /** Adhérence latérale (1/s) : plus c'est bas, plus ça glisse. */
  grip: number;
  /** Hors piste : "slow" ralentit, "void" fait tomber (piste suspendue). */
  outside: "slow" | "void";
  points: [number, number][];
  laps: number;
}

export interface Sample { x: number; z: number; tx: number; tz: number; nx: number; nz: number; curv: number; dist: number }

export interface Track {
  def: TrackDef;
  samples: Sample[];
  length: number;
  step: number;
}

export const TRACKS: TrackDef[] = [
  {
    id: "pole", name: "Circuit du Pôle Nord", tagline: "Larges virages dans la neige", theme: "north", difficulty: 1,
    halfWidth: 8, grip: 7.5, outside: "slow", laps: 3,
    points: [[0, 0], [60, -4], [108, 12], [128, 52], [108, 94], [60, 100], [28, 76], [-12, 90], [-56, 98], [-86, 60], [-70, 16]],
  },
  {
    id: "lac", name: "Lac Gelé", tagline: "La glace glisse : anticipe !", theme: "lake", difficulty: 2,
    halfWidth: 9, grip: 2.4, outside: "slow", laps: 3,
    points: [[0, 0], [70, 0], [122, -28], [164, 4], [152, 62], [104, 84], [66, 52], [28, 72], [30, 112], [4, 136], [-42, 128], [-74, 80], [-52, 28]],
  },
  {
    id: "village", name: "Village des Lutins", tagline: "Épingles entre les maisons, de nuit", theme: "village", difficulty: 2,
    halfWidth: 7, grip: 7.5, outside: "slow", laps: 3,
    points: [[0, 0], [52, 0], [92, -18], [100, -62], [64, -84], [36, -52], [2, -66], [-26, -108], [-76, -98], [-94, -58], [-72, -22], [-86, 14], [-46, 40]],
  },
  {
    id: "etoiles", name: "Chemin des Étoiles", tagline: "Piste suspendue : ne tombe pas !", theme: "stars", difficulty: 3,
    halfWidth: 7.5, grip: 6.5, outside: "void", laps: 3,
    points: [[0, 0], [80, 0], [132, 30], [124, 88], [72, 98], [50, 144], [0, 164], [-52, 132], [-40, 82], [-92, 60], [-102, 10], [-50, -14]],
  },
];

function catmull(p0: number, p1: number, p2: number, p3: number, t: number) {
  const t2 = t * t, t3 = t2 * t;
  return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
}

export function buildTrack(def: TrackDef, step = 1.5): Track {
  const P = def.points, n = P.length;
  // 1) courbe dense
  const dense: [number, number][] = [];
  for (let i = 0; i < n; i++) {
    const a = P[(i - 1 + n) % n], b = P[i], c = P[(i + 1) % n], d = P[(i + 2) % n];
    for (let k = 0; k < 40; k++) dense.push([catmull(a[0], b[0], c[0], d[0], k / 40), catmull(a[1], b[1], c[1], d[1], k / 40)]);
  }
  // 2) longueur cumulée
  const cum = [0];
  for (let i = 1; i <= dense.length; i++) {
    const p = dense[i % dense.length], q = dense[i - 1];
    cum.push(cum[i - 1] + Math.hypot(p[0] - q[0], p[1] - q[1]));
  }
  const length = cum[dense.length];
  const count = Math.round(length / step);
  const ds = length / count;
  // 3) échantillons à pas constant
  const pts: [number, number][] = [];
  let j = 0;
  for (let i = 0; i < count; i++) {
    const target = i * ds;
    while (cum[j + 1] < target) j++;
    const t = (target - cum[j]) / (cum[j + 1] - cum[j] || 1);
    const p = dense[j % dense.length], q = dense[(j + 1) % dense.length];
    pts.push([p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t]);
  }
  const samples: Sample[] = pts.map(([x, z], i) => {
    const a = pts[(i - 1 + count) % count], b = pts[(i + 1) % count];
    let tx = b[0] - a[0], tz = b[1] - a[1];
    const l = Math.hypot(tx, tz) || 1;
    tx /= l; tz /= l;
    return { x, z, tx, tz, nx: -tz, nz: tx, curv: 0, dist: i * ds };
  });
  // 4) courbure signée (variation d'angle par mètre), lissée
  const raw = samples.map((s, i) => {
    const a = samples[(i - 2 + count) % count], b = samples[(i + 2) % count];
    let d = Math.atan2(b.tz, b.tx) - Math.atan2(a.tz, a.tx);
    while (d > Math.PI) d -= 2 * Math.PI;
    while (d < -Math.PI) d += 2 * Math.PI;
    void s;
    return d / (4 * ds);
  });
  samples.forEach((s, i) => { s.curv = (raw[(i - 1 + count) % count] + raw[i] * 2 + raw[(i + 1) % count]) / 4; });
  return { def, samples, length, step: ds };
}

/** Indice de l'échantillon le plus proche, cherché autour de `near` (ou partout si absent). */
export function nearestIndex(track: Track, x: number, z: number, near?: number, window = 14): number {
  const S = track.samples, n = S.length;
  let best = 0, bestD = Infinity;
  if (near === undefined) {
    for (let i = 0; i < n; i++) { const d = (S[i].x - x) ** 2 + (S[i].z - z) ** 2; if (d < bestD) { bestD = d; best = i; } }
    return best;
  }
  for (let k = -window; k <= window; k++) {
    const i = ((near + k) % n + n) % n;
    const d = (S[i].x - x) ** 2 + (S[i].z - z) ** 2;
    if (d < bestD) { bestD = d; best = i; }
  }
  return best;
}

/** Écart latéral signé par rapport à la ligne centrale (positif = à gauche du sens de course). */
export function lateral(track: Track, idx: number, x: number, z: number) {
  const s = track.samples[idx];
  return (x - s.x) * s.nx + (z - s.z) * s.nz;
}

/** Distance à la ligne centrale la plus proche, tous tronçons confondus (pour placer le décor). */
export function distanceToTrack(track: Track, x: number, z: number) {
  let best = Infinity;
  const S = track.samples;
  for (let i = 0; i < S.length; i += 2) { const d = (S[i].x - x) ** 2 + (S[i].z - z) ** 2; if (d < best) best = d; }
  return Math.sqrt(best);
}
