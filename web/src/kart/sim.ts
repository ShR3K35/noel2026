// Simulation de course, pure (aucun DOM, aucun three.js) : testée sous Vitest, le rendu ne fait que la lire.
// Pas fixe de 60 Hz. Cap : angle h, avant = (cos h, sin h) dans le plan (x, z) ; braquage > 0 = à droite.

import { lateral, nearestIndex, type Track } from "./tracks";

export const DT = 1 / 60;
export const MAX_SPEED = 30;
const ACCEL = 1.15; // rapprochement exponentiel de la vitesse max (1/s)
const TURN = 1.95; // rad/s à pleine vitesse de braquage
const KART_RADIUS = 1.5;
const WALL_MARGIN = 11; // largeur de bas-côté avant le mur de neige

export type ItemKind = "boost" | "snowball" | "ice";
export type RacerId = "santa" | "grincheux" | "mere" | "renne" | "lutin";

export interface Racer { id: RacerId; name: string; color: string; skill: number }

export const RACERS: Racer[] = [
  { id: "santa", name: "Père Noël", color: "#e5283a", skill: 1 },
  { id: "grincheux", name: "Le Grincheux", color: "#8bd23c", skill: 0.985 },
  { id: "mere", name: "Mère Noël", color: "#ff7fb0", skill: 0.965 },
  { id: "renne", name: "Le Renne", color: "#b9773c", skill: 0.975 },
  { id: "lutin", name: "Le Lutin", color: "#2fbf71", skill: 0.95 },
];

export interface KartInput { steer: number; accel: boolean; brake: boolean; drift: boolean; item: boolean }
export const IDLE: KartInput = { steer: 0, accel: false, brake: false, drift: false, item: false };

export interface Kart {
  racer: Racer;
  isPlayer: boolean;
  x: number; z: number; y: number;
  heading: number;
  speed: number;
  vx: number; vz: number;
  steer: number;
  idx: number;
  lap: number;
  progress: number;
  rank: number;
  finished: boolean;
  finishTime: number;
  item: ItemKind | null;
  roulette: number;
  boostT: number;
  spinT: number;
  drifting: boolean;
  driftDir: number;
  driftT: number;
  offroad: boolean;
  fallT: number;
  falls: number;
  prevItem: boolean;
  ai: { lane: number; laneT: number; itemT: number };
}

export interface ItemBox { x: number; z: number; respawn: number }
export interface Snowball { id: number; x: number; z: number; vx: number; vz: number; owner: Kart; life: number; idx: number }
export interface IcePatch { id: number; x: number; z: number; owner: Kart; life: number; age: number }

export type RaceEventType = "pickup" | "item" | "boost" | "hit" | "lap" | "finish" | "fall" | "bump" | "go" | "beep" | "driftBoost" | "throw";
export interface RaceEvent { type: RaceEventType; kart?: Kart; n?: number }

export class Race {
  karts: Kart[] = [];
  boxes: ItemBox[] = [];
  snowballs: Snowball[] = [];
  ice: IcePatch[] = [];
  events: RaceEvent[] = [];
  /** Temps de course ; négatif pendant le décompte. */
  time = -3.6;
  tick = 0;
  private nextId = 1;
  private seed = 12345;
  private lastBeep = 4;

  constructor(readonly track: Track, opts: { playerIsAi?: boolean } = {}) {
    const S = track.samples, n = S.length;
    // grille de départ : le joueur part dernier, comme il se doit
    const order = [1, 2, 3, 4, 0];
    order.forEach((ri, slot) => {
      const back = 6 + slot * 4.2;
      const idx = ((n - Math.round(back / track.step)) % n + n) % n;
      const s = S[idx];
      const side = (slot % 2 ? 1 : -1) * track.def.halfWidth * 0.42;
      const racer = RACERS[ri];
      this.karts.push({
        racer, isPlayer: ri === 0 && !opts.playerIsAi,
        x: s.x + s.nx * side, z: s.z + s.nz * side, y: 0,
        heading: Math.atan2(s.tz, s.tx), speed: 0, vx: 0, vz: 0, steer: 0,
        idx, lap: 0, progress: idx - n, rank: slot + 1, finished: false, finishTime: 0,
        item: null, roulette: 0, boostT: 0, spinT: 0, drifting: false, driftDir: 0, driftT: 0,
        offroad: false, fallT: 0, falls: 0, prevItem: false,
        ai: { lane: side, laneT: 2 + slot, itemT: 1.5 },
      });
    });
    // cadeaux-surprises : quatre rangées réparties sur le tour
    for (const f of [0.14, 0.38, 0.62, 0.86]) {
      const s = S[Math.floor(f * n)];
      for (const o of [-0.6, -0.2, 0.2, 0.6]) this.boxes.push({ x: s.x + s.nx * o * track.def.halfWidth, z: s.z + s.nz * o * track.def.halfWidth, respawn: 0 });
    }
  }

  get player() { return this.karts.find((k) => k.racer.id === "santa")!; }

  private rand() { this.seed = (this.seed * 16807) % 2147483647; return this.seed / 2147483647; }

  step(playerInput: KartInput) {
    this.tick++;
    const before = this.time;
    this.time += DT;
    // décompte : bip à 3, 2, 1 puis départ
    const c = Math.ceil(-this.time);
    if (this.time < 0 && c !== this.lastBeep && c <= 3) { this.lastBeep = c; this.events.push({ type: "beep", n: c }); }
    if (before < 0 && this.time >= 0) this.events.push({ type: "go" });
    const racing = this.time >= 0;

    for (const k of this.karts) {
      const input = !racing ? IDLE : k.isPlayer && !k.finished ? playerInput : this.aiInput(k);
      this.stepKart(k, input);
    }
    this.collide();
    this.stepItems();
    this.rankKarts();
  }

  // ------------------------------------------------------------------ kart

  private stepKart(k: Kart, input: KartInput) {
    const t = this.track, def = t.def;
    if (k.fallT > 0) {
      // chute dans le vide puis remise en piste
      k.fallT -= DT;
      k.y -= 14 * DT;
      if (k.fallT <= 0) {
        const s = t.samples[k.idx];
        k.x = s.x; k.z = s.z; k.y = 0; k.heading = Math.atan2(s.tz, s.tx);
        k.speed = 0; k.vx = k.vz = 0; k.spinT = 0; k.boostT = 0; k.drifting = false;
      }
      return;
    }
    k.boostT = Math.max(0, k.boostT - DT);
    const spinning = k.spinT > 0;
    if (spinning) { k.spinT -= DT; input = IDLE; }

    // objet : roulette puis utilisation à l'appui
    if (k.roulette > 0) {
      k.roulette -= DT;
      if (k.roulette <= 0) {
        const r = this.rand();
        // le premier reçoit surtout des pièges, les derniers surtout des turbos
        const boostP = k.rank === 1 ? 0.2 : k.rank >= 4 ? 0.55 : 0.4;
        k.item = r < boostP ? "boost" : r < boostP + (1 - boostP) * 0.55 ? "snowball" : "ice";
        this.events.push({ type: "item", kart: k });
      }
    }
    if (input.item && !k.prevItem && k.item && !spinning) this.useItem(k);
    k.prevItem = input.item;

    // dérapage : virage plus serré, mini-turbo au relâchement s'il a duré
    const steerIn = Math.max(-1, Math.min(1, input.steer));
    k.steer += (steerIn - k.steer) * Math.min(1, DT * 12);
    if (input.drift && !k.drifting && Math.abs(steerIn) > 0.3 && k.speed > 13 && !k.offroad) { k.drifting = true; k.driftDir = Math.sign(steerIn); k.driftT = 0; }
    if (k.drifting && (!input.drift || k.speed < 9 || k.offroad)) {
      if (k.driftT > 0.9 && input.accel !== false && !k.offroad) { k.boostT = Math.max(k.boostT, k.driftT > 1.9 ? 1.2 : 0.7); this.events.push({ type: "driftBoost", kart: k }); }
      k.drifting = false;
    }
    if (k.drifting) k.driftT += DT;

    // vitesse
    let max = MAX_SPEED * k.racer.skill * this.rubber(k);
    if (k.boostT > 0) max *= 1.38;
    if (k.offroad && k.boostT <= 0) max *= 0.5;
    if (input.accel) k.speed += Math.max(0, max - k.speed) * ACCEL * DT + (k.speed < max ? 3.5 * DT : 0);
    else k.speed -= Math.min(Math.abs(k.speed), 9 * DT) * Math.sign(k.speed);
    if (input.brake) k.speed = Math.max(-9, k.speed - 34 * DT);
    if (k.speed > max) k.speed -= (k.speed - max) * (k.offroad ? 3.2 : 1.6) * DT;
    if (k.boostT > 0 && k.speed < max) k.speed += (max - k.speed) * 4 * DT;
    if (spinning) k.speed *= 1 - 2.2 * DT;

    // direction
    const speedF = Math.min(1, Math.abs(k.speed) / 9) * (k.speed < 0 ? -1 : 1);
    const turn = k.drifting ? k.driftDir * 0.62 + k.steer * 0.72 : k.steer;
    k.heading += turn * TURN * speedF * DT * (k.speed > 24 ? 0.92 : 1);

    // adhérence : la vitesse réelle rejoint le cap plus ou moins vite (glace, dérapage)
    const grip = (k.drifting ? Math.min(def.grip, 3.4) : def.grip) * (spinning ? 0.4 : 1);
    const tvx = Math.cos(k.heading) * k.speed, tvz = Math.sin(k.heading) * k.speed;
    const g = Math.min(1, grip * DT);
    k.vx += (tvx - k.vx) * g; k.vz += (tvz - k.vz) * g;
    k.x += k.vx * DT; k.z += k.vz * DT;

    // position sur le circuit
    const n = t.samples.length;
    const prev = k.idx;
    k.idx = nearestIndex(t, k.x, k.z, k.idx);
    let d = k.idx - prev;
    if (d > n / 2) d -= n; else if (d < -n / 2) d += n;
    if (prev + d >= n) { k.lap++; this.onLap(k); } else if (prev + d < 0) k.lap--;
    k.progress = (k.lap - 1) * n + k.idx;
    const off = lateral(t, k.idx, k.x, k.z);
    const a = Math.abs(off);
    k.offroad = a > def.halfWidth + 0.6;
    if (def.outside === "void") {
      if (a > def.halfWidth + 1.4) { k.fallT = 1.25; k.falls++; this.events.push({ type: "fall", kart: k }); }
    } else if (a > def.halfWidth + WALL_MARGIN) {
      const s = t.samples[k.idx];
      const lim = (def.halfWidth + WALL_MARGIN) * Math.sign(off);
      k.x += s.nx * (lim - off); k.z += s.nz * (lim - off);
      k.speed *= 0.86;
      const vn = k.vx * s.nx + k.vz * s.nz;
      k.vx -= s.nx * vn * 1.3; k.vz -= s.nz * vn * 1.3;
    }
  }

  /** Élastique léger : les concurrents loin devant lèvent un peu le pied, ceux loin derrière poussent. */
  private rubber(k: Kart) {
    if (k.isPlayer || k.racer.id === "santa") return 1;
    const gap = (k.progress - this.player.progress) * this.track.step;
    return gap > 70 ? 0.93 : gap > 30 ? 0.965 : gap < -70 ? 1.045 : gap < -30 ? 1.02 : 1;
  }

  private onLap(k: Kart) {
    if (k.finished) return;
    if (k.lap > this.track.def.laps) {
      k.finished = true;
      k.finishTime = this.time;
      this.events.push({ type: "finish", kart: k });
    } else if (k.lap > 1) this.events.push({ type: "lap", kart: k, n: k.lap });
  }

  private useItem(k: Kart) {
    const item = k.item;
    k.item = null;
    const fx = Math.cos(k.heading), fz = Math.sin(k.heading);
    if (item === "boost") { k.boostT = 1.7; this.events.push({ type: "boost", kart: k }); }
    else if (item === "snowball") {
      this.snowballs.push({ id: this.nextId++, x: k.x + fx * 2.4, z: k.z + fz * 2.4, vx: fx * 58, vz: fz * 58, owner: k, life: 3, idx: k.idx });
      this.events.push({ type: "throw", kart: k });
    } else if (item === "ice") {
      this.ice.push({ id: this.nextId++, x: k.x - fx * 3, z: k.z - fz * 3, owner: k, life: 25, age: 0 });
      this.events.push({ type: "throw", kart: k });
    }
  }

  private spinOut(k: Kart) {
    if (k.spinT > 0 || k.fallT > 0 || k.finished) return;
    k.spinT = 1.25;
    k.boostT = 0;
    k.drifting = false;
    this.events.push({ type: "hit", kart: k });
  }

  // ------------------------------------------------------------------ IA

  private aiInput(k: Kart): KartInput {
    const t = this.track, S = t.samples, n = S.length, def = t.def;
    const ai = k.ai;
    ai.laneT -= DT;
    if (ai.laneT <= 0) { ai.laneT = 2 + this.rand() * 3; ai.lane = (this.rand() * 2 - 1) * def.halfWidth * 0.5; }
    // éviter le kart juste devant
    for (const o of this.karts) {
      if (o === k) continue;
      const dx = o.x - k.x, dz = o.z - k.z;
      const ahead = dx * Math.cos(k.heading) + dz * Math.sin(k.heading);
      if (ahead > 0 && ahead < 9 && Math.abs(lateral(t, o.idx, o.x, o.z) - ai.lane) < 2.2) { ai.lane += ai.lane > 0 ? -2.5 : 2.5; ai.laneT = 1.5; }
    }
    ai.lane = Math.max(-def.halfWidth * 0.6, Math.min(def.halfWidth * 0.6, ai.lane));

    // point visé un peu plus loin, coupé vers l'intérieur des virages
    const look = Math.round((7 + Math.abs(k.speed) * 0.42) / t.step);
    const target = S[(k.idx + look) % n];
    const cut = Math.max(-1, Math.min(1, -target.curv * 26)) * def.halfWidth * 0.45;
    const lane = Math.max(-def.halfWidth * 0.7, Math.min(def.halfWidth * 0.7, ai.lane * 0.6 + cut));
    const tx = target.x + target.nx * lane, tz = target.z + target.nz * lane;
    let diff = Math.atan2(tz - k.z, tx - k.x) - k.heading;
    while (diff > Math.PI) diff -= 2 * Math.PI;
    while (diff < -Math.PI) diff += 2 * Math.PI;

    // vitesse cible selon la courbure à venir (plus prudente sur la glace et au bord du vide)
    let curv = 0;
    const span = Math.round((10 + Math.abs(k.speed) * 0.9) / t.step);
    for (let i = 0; i < span; i += 2) curv = Math.max(curv, Math.abs(S[(k.idx + i) % n].curv));
    const lat = def.grip < 4 ? 26 : def.outside === "void" ? 40 : 52;
    const want = curv > 0.002 ? Math.sqrt(lat / curv) : 99;

    // objets
    let useItem = false;
    ai.itemT -= DT;
    if (k.item && ai.itemT <= 0) {
      if (k.item === "boost") useItem = curv < 0.02 && !k.offroad;
      else if (k.item === "snowball") {
        for (const o of this.karts) {
          if (o === k) continue;
          const dx = o.x - k.x, dz = o.z - k.z;
          const ahead = dx * Math.cos(k.heading) + dz * Math.sin(k.heading);
          const side = Math.abs(-dx * Math.sin(k.heading) + dz * Math.cos(k.heading));
          if (ahead > 4 && ahead < 45 && side < 2.5) useItem = true;
        }
      } else useItem = this.rand() < 0.02;
      if (useItem) ai.itemT = 2 + this.rand() * 3;
    }
    if (!k.item) ai.itemT = Math.max(ai.itemT, 1.2);

    return {
      steer: Math.max(-1, Math.min(1, diff * 2.6)),
      accel: k.speed < want,
      brake: k.speed > want + 5,
      drift: false,
      item: useItem,
    };
  }

  // ------------------------------------------------------------------ collisions et objets

  private collide() {
    const K = this.karts;
    for (let i = 0; i < K.length; i++) for (let j = i + 1; j < K.length; j++) {
      const a = K[i], b = K[j];
      if (a.fallT > 0 || b.fallT > 0) continue;
      const dx = b.x - a.x, dz = b.z - a.z;
      const d = Math.hypot(dx, dz);
      if (d >= KART_RADIUS * 2 || d < 1e-4) continue;
      const nx = dx / d, nz = dz / d, push = (KART_RADIUS * 2 - d) / 2;
      a.x -= nx * push; a.z -= nz * push; b.x += nx * push; b.z += nz * push;
      const rel = (b.vx - a.vx) * nx + (b.vz - a.vz) * nz;
      if (rel < 0) {
        a.vx += nx * rel * 0.6; a.vz += nz * rel * 0.6; b.vx -= nx * rel * 0.6; b.vz -= nz * rel * 0.6;
        if (rel < -3 && (a.isPlayer || b.isPlayer)) this.events.push({ type: "bump", kart: a.isPlayer ? a : b });
      }
    }
  }

  private stepItems() {
    const t = this.track, def = t.def;
    for (const b of this.boxes) {
      if (b.respawn > 0) { b.respawn -= DT; continue; }
      for (const k of this.karts) {
        if (k.fallT > 0 || (k.x - b.x) ** 2 + (k.z - b.z) ** 2 > 2.4 ** 2) continue;
        b.respawn = 4;
        if (!k.item && k.roulette <= 0) { k.roulette = 1; this.events.push({ type: "pickup", kart: k }); }
        break;
      }
    }
    for (const s of this.snowballs) {
      s.life -= DT;
      s.x += s.vx * DT; s.z += s.vz * DT;
      s.idx = nearestIndex(t, s.x, s.z, s.idx, 40);
      if (Math.abs(lateral(t, s.idx, s.x, s.z)) > def.halfWidth + (def.outside === "void" ? 2 : WALL_MARGIN)) s.life = 0;
      for (const k of this.karts) {
        if (k === s.owner || k.fallT > 0) continue;
        if ((k.x - s.x) ** 2 + (k.z - s.z) ** 2 < 2.1 ** 2) { this.spinOut(k); s.life = 0; break; }
      }
    }
    this.snowballs = this.snowballs.filter((s) => s.life > 0);
    for (const p of this.ice) {
      p.life -= DT; p.age += DT;
      for (const k of this.karts) {
        if ((k === p.owner && p.age < 1.2) || k.fallT > 0 || k.spinT > 0) continue;
        if ((k.x - p.x) ** 2 + (k.z - p.z) ** 2 < 2 ** 2) { this.spinOut(k); p.life = 0; break; }
      }
    }
    this.ice = this.ice.filter((p) => p.life > 0);
  }

  private rankKarts() {
    const sorted = [...this.karts].sort((a, b) => {
      if (a.finished && b.finished) return a.finishTime - b.finishTime;
      if (a.finished !== b.finished) return a.finished ? -1 : 1;
      return b.progress - a.progress;
    });
    sorted.forEach((k, i) => (k.rank = i + 1));
  }

  /** Classement courant, du premier au dernier. */
  standings() { return [...this.karts].sort((a, b) => a.rank - b.rank); }
}
