// Niveaux construits avec un petit constructeur plutôt qu'à la main, case par case.
// Coordonnées en cases : x = colonne, y = ligne depuis le haut (0 à 14).
// Tuiles : '#' sol, '=' bloc de glace, '-' planche (traversable par-dessous), '^' stalagmites,
// 'H' cheminée, '?' cadeau à clochette, '!' cadeau étoile (boules de neige), '+' cadeau chocolat chaud (cœur).

import { ROWS } from "./config";

export type Theme = "village" | "forest" | "city";
export type EnemyKind = "snowman" | "penguin" | "crow";
export type SpawnKind = EnemyKind | "bell" | "checkpoint" | "goal";
export type DecorKind = "pine" | "pineSmall" | "house" | "lamp" | "fence" | "candy" | "sign" | "tree" | "gifts" | "antenna" | "garland" | "snowpile";

export interface Spawn { kind: SpawnKind; x: number; y: number }
export interface Decor { kind: DecorKind; x: number; y: number; flip?: boolean; seed: number }

export interface LevelDef {
  name: string;
  subtitle: string;
  theme: Theme;
  width: number;
  grid: string[];
  spawns: Spawn[];
  decor: Decor[];
  start: { x: number; y: number };
  finale: boolean;
}

class Builder {
  private cells: string[][];
  spawns: Spawn[] = [];
  decor: Decor[] = [];
  start = { x: 2, y: 11 };
  private seed = 1;

  constructor(readonly width: number) {
    this.cells = Array.from({ length: ROWS }, () => Array.from({ length: width }, () => " "));
  }

  set(x: number, y: number, c: string) {
    if (x >= 0 && x < this.width && y >= 0 && y < ROWS) this.cells[y][x] = c;
  }

  /** Sol plein de x0 à x1 inclus, sur h cases de haut. Renvoie la ligne de surface (case du dessus). */
  ground(x0: number, x1: number, h = 3) {
    for (let x = x0; x <= x1; x++) for (let y = ROWS - h; y < ROWS; y++) this.set(x, y, "#");
    return ROWS - h;
  }

  /** Escalier : une colonne par marche, de h0 à h1. */
  stairs(x0: number, h0: number, h1: number) {
    const step = h1 >= h0 ? 1 : -1;
    let x = x0;
    for (let h = h0; h !== h1 + step; h += step) this.ground(x, x, h), x++;
    return x - 1;
  }

  row(x: number, y: number, pattern: string) {
    [...pattern].forEach((c, i) => { if (c !== ".") this.set(x + i, y, c); });
  }

  plank(x: number, y: number, w: number) { this.row(x, y, "-".repeat(w)); }

  spawn(kind: SpawnKind, x: number, y: number) { this.spawns.push({ kind, x, y }); }

  bells(x: number, y: number, n: number, arc = 0) {
    for (let i = 0; i < n; i++) {
      const t = n === 1 ? 0 : i / (n - 1);
      const lift = Math.round(Math.sin(t * Math.PI) * arc);
      this.spawn("bell", x + i, y - lift);
    }
  }

  deco(kind: DecorKind, x: number, y: number, flip = false) {
    this.seed = (this.seed * 9301 + 49297) % 233280;
    this.decor.push({ kind, x, y, flip, seed: this.seed / 233280 });
  }

  build(name: string, subtitle: string, theme: Theme, finale = false): LevelDef {
    return {
      name, subtitle, theme, finale,
      width: this.width,
      grid: this.cells.map((r) => r.join("")),
      spawns: this.spawns,
      decor: this.decor.sort((a, b) => depth(a.kind) - depth(b.kind)),
      start: this.start,
    };
  }
}

function depth(k: DecorKind) {
  return k === "house" ? 0 : k === "tree" || k === "pine" ? 1 : 2;
}

function village(): LevelDef {
  const b = new Builder(178);
  b.start = { x: 3, y: 11 };
  let s = b.ground(0, 26);
  b.deco("house", 1, s); b.deco("lamp", 8, s); b.deco("pine", 12, s); b.deco("sign", 6, s);
  b.row(10, 8, "?");
  b.row(15, 8, "=?=!=");
  b.bells(15, 5, 5, 1);
  b.deco("fence", 20, s); b.deco("pineSmall", 24, s);
  b.spawn("snowman", 22, s - 1);

  s = b.ground(29, 41);
  b.bells(26, 9, 3, 1);
  b.deco("pine", 31, s); b.deco("lamp", 36, s); b.deco("candy", 40, s);
  b.spawn("snowman", 38, s - 1);
  b.stairs(42, 4, 5);
  s = b.ground(44, 52, 5);
  b.deco("pineSmall", 46, s);
  b.row(47, s - 4, "?=?");
  b.spawn("snowman", 50, s - 1);
  b.bells(54, 6, 3, 1);

  s = b.ground(57, 82);
  b.deco("house", 58, s); b.deco("lamp", 66, s, true);
  b.plank(63, 9, 4);
  b.bells(63, 8, 4);
  b.row(70, 8, "?+?");
  b.spawn("snowman", 68, s - 1);
  b.spawn("snowman", 76, s - 1);
  b.deco("fence", 74, s); b.deco("pine", 79, s);
  b.spawn("checkpoint", 82, s - 1);

  s = b.ground(83, 98);
  b.deco("gifts", 85, s); b.deco("pine", 90, s);
  b.spawn("penguin", 94, s - 1);
  b.row(101, 10, "==");
  b.bells(100, 8, 4, 1);
  s = b.ground(105, 122);
  b.row(110, s - 1, "^^");
  b.bells(109, 8, 4, 1);
  b.deco("lamp", 106, s); b.deco("candy", 114, s);
  b.spawn("snowman", 117, s - 1);
  b.row(116, 8, "=!=");

  b.ground(123, 125);
  b.stairs(126, 4, 7);
  b.bells(130, 4, 3, 1);
  b.ground(132, 134, 7);
  b.stairs(135, 6, 4);
  s = b.ground(138, 177);
  b.deco("pineSmall", 140, s); b.deco("house", 143, s);
  b.spawn("snowman", 147, s - 1);
  b.spawn("penguin", 154, s - 1);
  b.row(150, 8, "?=?=?");
  b.bells(150, 5, 5, 1);
  b.deco("fence", 158, s); b.deco("lamp", 161, s);
  b.deco("tree", 172, s);
  b.spawn("goal", 167, s - 1);
  return b.build("Le village enneigé", "Niveau 1", "village");
}

function forest(): LevelDef {
  const b = new Builder(196);
  b.start = { x: 2, y: 11 };
  let s = b.ground(0, 18);
  b.deco("pine", 1, s); b.deco("pineSmall", 6, s); b.deco("pine", 14, s); b.deco("snowpile", 9, s);
  b.row(8, 8, "?!?");
  b.spawn("penguin", 15, s - 1);

  b.plank(20, 10, 3);
  b.bells(20, 9, 3);
  s = b.ground(25, 36, 4);
  b.deco("pine", 27, s); b.deco("lamp", 33, s);
  b.spawn("snowman", 30, s - 1);
  b.spawn("crow", 34, 6);
  b.plank(38, 9, 3);
  b.bells(38, 7, 3, 1);
  s = b.ground(43, 60);
  b.row(46, s - 1, "^");
  b.row(52, s - 1, "^^");
  b.deco("pineSmall", 44, s); b.deco("pine", 49, s); b.deco("pine", 57, s);
  b.plank(48, 8, 6);
  b.bells(48, 7, 6);
  b.spawn("snowman", 56, s - 1);
  b.row(58, 8, "+");

  b.row(63, 10, "===");
  b.spawn("crow", 66, 5);
  s = b.ground(69, 84, 5);
  b.deco("pine", 71, s); b.deco("candy", 80, s);
  b.spawn("penguin", 78, s - 1);
  b.spawn("checkpoint", 83, s - 1);
  b.plank(86, 8, 3);
  b.plank(91, 6, 3);
  b.bells(91, 4, 3, 1);
  b.plank(96, 8, 3);
  s = b.ground(100, 118);
  b.deco("pine", 101, s); b.deco("pineSmall", 107, s); b.deco("lamp", 113, s);
  b.spawn("snowman", 106, s - 1);
  b.spawn("snowman", 110, s - 1);
  b.row(104, 8, "=?=?=");
  b.spawn("crow", 116, 6);

  b.stairs(119, 4, 6);
  s = b.ground(122, 128, 6);
  b.row(125, s - 1, "^");
  b.bells(124, 6, 3, 1);
  b.row(131, 10, "==");
  b.row(135, 8, "==");
  b.row(139, 10, "==");
  b.bells(131, 8, 2); b.bells(135, 6, 2); b.bells(139, 8, 2);
  b.spawn("crow", 137, 4);
  s = b.ground(143, 195);
  b.deco("pine", 144, s); b.deco("pine", 152, s); b.deco("fence", 158, s);
  b.spawn("penguin", 150, s - 1);
  b.spawn("snowman", 162, s - 1);
  b.spawn("penguin", 168, s - 1);
  b.row(155, 8, "?!?");
  b.row(166, s - 1, "^^");
  b.bells(165, 8, 4, 1);
  b.deco("lamp", 174, s); b.deco("tree", 188, s); b.deco("gifts", 178, s);
  b.spawn("goal", 183, s - 1);
  return b.build("La forêt boréale", "Niveau 2", "forest");
}

function city(): LevelDef {
  const b = new Builder(200);
  b.start = { x: 2, y: 10 };
  let s = b.ground(0, 14, 4);
  b.deco("antenna", 3, s); b.deco("garland", 8, s);
  b.set(11, s - 1, "H"); b.set(11, s - 2, "H");
  b.row(6, s - 4, "?+?");
  b.spawn("snowman", 9, s - 1);

  s = b.ground(17, 28, 5);
  b.deco("snowpile", 19, s);
  b.set(23, s - 1, "H");
  b.spawn("penguin", 26, s - 1);
  b.bells(15, 7, 2, 0);
  s = b.ground(31, 40, 6);
  b.deco("antenna", 33, s); b.deco("garland", 36, s);
  b.spawn("crow", 37, 4);
  b.bells(30, 6, 2);
  b.plank(43, 9, 3);
  s = b.ground(48, 62, 4);
  b.set(52, s - 1, "H"); b.set(52, s - 2, "H");
  b.set(58, s - 1, "H");
  b.row(54, s - 4, "?!?");
  b.spawn("snowman", 55, s - 1);
  b.spawn("snowman", 61, s - 1);
  b.bells(44, 7, 3, 1);

  s = b.ground(65, 70, 6);
  b.deco("antenna", 67, s);
  s = b.ground(73, 88, 5);
  b.deco("garland", 76, s);
  b.row(79, s - 1, "^^");
  b.bells(78, s - 4, 4, 1);
  b.spawn("penguin", 85, s - 1);
  b.spawn("checkpoint", 87, s - 1);

  b.row(91, 9, "==");
  b.row(95, 7, "==");
  b.spawn("crow", 96, 3);
  b.row(99, 9, "==");
  b.bells(91, 7, 2); b.bells(95, 5, 2); b.bells(99, 7, 2);
  s = b.ground(103, 118, 4);
  b.set(107, s - 1, "H"); b.set(107, s - 2, "H"); b.set(107, s - 3, "H");
  b.row(110, s - 4, "=?=+=");
  b.spawn("snowman", 113, s - 1);
  b.spawn("penguin", 116, s - 1);
  b.deco("antenna", 104, s);

  b.stairs(119, 5, 7);
  s = b.ground(122, 128, 7);
  b.spawn("crow", 126, 3);
  b.plank(131, 9, 3);
  b.bells(131, 8, 3);
  s = b.ground(136, 150, 4);
  b.set(140, s - 1, "H"); b.set(140, s - 2, "H");
  b.row(143, s - 1, "^");
  b.row(146, s - 4, "?!?");
  b.spawn("snowman", 148, s - 1);
  b.spawn("crow", 149, 5);
  s = b.ground(153, 162, 5);
  b.deco("garland", 155, s);
  b.spawn("penguin", 160, s - 1);
  b.bells(151, 7, 2);
  s = b.ground(165, 199, 4);
  b.deco("snowpile", 167, s); b.deco("antenna", 171, s);
  b.spawn("snowman", 172, s - 1);
  b.spawn("snowman", 176, s - 1);
  b.bells(173, 7, 5, 1);
  b.deco("garland", 182, s);
  b.spawn("goal", 189, s - 1);
  return b.build("Les toits de la ville", "Niveau 3 · Dernière cheminée", "city", true);
}

export const LEVELS: LevelDef[] = [village(), forest(), city()];
