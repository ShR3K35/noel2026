// Simulation pure (aucun accès au DOM) : on la teste sous Vitest et le rendu ne fait que la lire.
// Pas fixe de 60 Hz, fenêtres de grâce comptées en ticks (skill `platformer`, Pattern 1).

import {
  AIR_ACCEL, APEX_HANG, APEX_THRESHOLD, BUFFER_TICKS, CORNER_CORRECTION, COYOTE_TICKS, DT, FALL_GRAVITY,
  GROUND_ACCEL, GROUND_DECEL, HURT_INVULN_TICKS, JUMP_CUT, JUMP_VELOCITY, MAX_FALL, MAX_HEARTS, PLAYER_H,
  PLAYER_W, RISE_GRAVITY, ROWS, RUN_SPEED, STOMP_BOUNCE, STOMP_BOUNCE_HELD, TILE,
} from "./config";
import type { EnemyKind, LevelDef } from "./levels";

export interface Input { left: boolean; right: boolean; jump: boolean; action: boolean }
export const NO_INPUT: Input = { left: false, right: false, jump: false, action: false };

export type EventType =
  | "jump" | "land" | "bell" | "stomp" | "bump" | "spawnItem" | "power" | "heart" | "hurt" | "die"
  | "throw" | "hit" | "checkpoint" | "clear" | "step";
export interface GameEvent { type: EventType; x: number; y: number; big?: boolean }

export type ItemKind = "bell" | "star" | "cocoa" | "popbell";
export interface Box { x: number; y: number; w: number; h: number; vx: number; vy: number; onGround: boolean }
export interface Enemy extends Box { kind: EnemyKind; id: number; dir: number; alive: boolean; deadT: number; active: boolean; t: number; baseY: number; squashed: boolean }
export interface Item extends Box { kind: ItemKind; id: number; alive: boolean; t: number; emerge: number }
export interface Snowball extends Box { id: number; alive: boolean; t: number; bounces: number }

export interface Player extends Box {
  facing: number;
  hearts: number;
  invuln: number;
  power: boolean;
  lastGround: number;
  lastPress: number;
  jumping: boolean;
  landSquash: number;
  runPhase: number;
  throwCooldown: number;
  throwAnim: number;
  dead: boolean;
}

const NEVER = -1e9;
const SOLID = new Set(["#", "=", "?", "!", "+", "u", "H"]);
const GIFTS = new Set(["?", "!", "+"]);

export class World {
  readonly grid: string[][];
  readonly width: number;
  readonly widthPx: number;
  player: Player;
  enemies: Enemy[] = [];
  items: Item[] = [];
  snowballs: Snowball[] = [];
  checkpoints: { x: number; y: number; lit: boolean }[] = [];
  goal = { x: 0, y: 0 };
  tick = 0;
  bells = 0;
  events: GameEvent[] = [];
  status: "play" | "dead" | "clear" = "play";
  statusT = 0;
  /** Cases de cadeau frappées : clé "x,y" → tick du coup (pour l'animation de rebond). */
  bumps = new Map<string, number>();
  private prev: Input = NO_INPUT;
  private nextId = 1;

  constructor(readonly level: LevelDef, respawnAt?: { x: number; y: number }, carried?: { power: boolean }) {
    this.grid = level.grid.map((r) => [...r]);
    this.width = level.width;
    this.widthPx = level.width * TILE;
    const start = respawnAt ?? level.start;
    this.player = {
      x: start.x * TILE + (TILE - PLAYER_W) / 2, y: (start.y + 1) * TILE - PLAYER_H,
      w: PLAYER_W, h: PLAYER_H, vx: 0, vy: 0, onGround: false, facing: 1,
      hearts: MAX_HEARTS, invuln: 0, power: carried?.power ?? false,
      lastGround: NEVER, lastPress: NEVER, jumping: false, landSquash: 0, runPhase: 0,
      throwCooldown: 0, throwAnim: 0, dead: false,
    };
    for (const s of level.spawns) {
      const cx = s.x * TILE + TILE / 2;
      const bottom = (s.y + 1) * TILE;
      if (s.kind === "bell") this.items.push(this.makeItem("bell", cx - 16, s.y * TILE + 8, 32, 32));
      else if (s.kind === "checkpoint") this.checkpoints.push({ x: cx, y: bottom, lit: !!respawnAt && respawnAt.x === s.x });
      else if (s.kind === "goal") this.goal = { x: cx, y: bottom };
      else {
        const size = s.kind === "snowman" ? { w: 38, h: 58 } : s.kind === "penguin" ? { w: 34, h: 40 } : { w: 40, h: 30 };
        this.enemies.push({
          kind: s.kind, id: this.nextId++, x: cx - size.w / 2, y: bottom - size.h, w: size.w, h: size.h,
          vx: 0, vy: 0, onGround: false, dir: -1, alive: true, deadT: 0, active: false, t: 0,
          baseY: bottom - size.h, squashed: false,
        });
      }
    }
  }

  private makeItem(kind: ItemKind, x: number, y: number, w: number, h: number): Item {
    return { kind, id: this.nextId++, x, y, w, h, vx: 0, vy: 0, onGround: false, alive: true, t: 0, emerge: 0 };
  }

  tile(cx: number, cy: number): string {
    if (cx < 0 || cx >= this.width) return "#";
    if (cy < 0) return " ";
    if (cy >= ROWS) return " ";
    return this.grid[cy][cx];
  }

  isSolid(cx: number, cy: number) {
    if (cy < 0) return cx < 0 || cx >= this.width; // murs invisibles aux bords, jusqu'en haut
    return SOLID.has(this.tile(cx, cy));
  }

  private emit(type: EventType, x: number, y: number, big = false) {
    this.events.push({ type, x, y, big });
  }

  step(input: Input) {
    this.tick++;
    this.statusT++;
    if (this.status !== "play") {
      this.prev = input;
      if (this.status === "dead") {
        // petit saut de « chute » puis sortie par le bas
        const p = this.player;
        if (this.statusT > 30) { p.vy = Math.min(p.vy + FALL_GRAVITY * 0.6 * DT, MAX_FALL); p.y += p.vy * DT; }
      }
      this.updateItems();
      return;
    }
    this.updatePlayer(input);
    this.updateEnemies();
    this.updateItems();
    this.updateSnowballs();
    this.prev = input;
  }

  // ---------------------------------------------------------------- joueur

  private updatePlayer(input: Input) {
    const p = this.player;
    const pressed = input.jump && !this.prev.jump;
    const released = !input.jump && this.prev.jump;
    const dir = (input.right ? 1 : 0) - (input.left ? 1 : 0);

    // course
    const target = dir * RUN_SPEED;
    const accel = p.onGround ? (dir !== 0 ? GROUND_ACCEL : GROUND_DECEL) : AIR_ACCEL;
    if (p.vx < target) p.vx = Math.min(target, p.vx + accel * DT);
    else if (p.vx > target) p.vx = Math.max(target, p.vx - accel * DT);
    if (dir !== 0) p.facing = dir;

    // saut : tampon + coyote, consommés ensemble
    if (p.onGround && p.vy >= 0) p.lastGround = this.tick;
    if (pressed) p.lastPress = this.tick;
    if (this.tick - p.lastPress <= BUFFER_TICKS && this.tick - p.lastGround <= COYOTE_TICKS) {
      p.vy = JUMP_VELOCITY;
      p.lastPress = p.lastGround = NEVER;
      p.jumping = true;
      p.onGround = false;
      if (!input.jump) p.vy *= JUMP_CUT;
      this.emit("jump", p.x + p.w / 2, p.y + p.h);
    }
    if (released && p.vy < 0 && p.jumping) { p.vy *= JUMP_CUT; p.jumping = false; }

    // gravité, demi-pas (apex exact, cf. jump_tuner --integrator half)
    let g = p.vy > 0 ? FALL_GRAVITY : RISE_GRAVITY;
    if (input.jump && Math.abs(p.vy) < APEX_THRESHOLD) g *= APEX_HANG;
    const vy0 = p.vy;
    p.vy = Math.min(p.vy + g * DT, MAX_FALL);
    const dy = ((vy0 + p.vy) / 2) * DT;

    const wasGround = p.onGround;
    const fallSpeed = vy0;
    this.moveX(p, p.vx * DT);
    const hit = this.moveY(p, dy, true);
    if (hit === "ceiling") p.jumping = false;
    if (p.onGround) p.jumping = false;
    if (p.onGround && !wasGround) {
      p.landSquash = Math.min(1, fallSpeed / 900);
      this.emit("land", p.x + p.w / 2, p.y + p.h, fallSpeed > 900);
    }
    p.landSquash = Math.max(0, p.landSquash - DT * 5);
    if (p.onGround && Math.abs(p.vx) > 40) {
      const before = p.runPhase;
      p.runPhase += (Math.abs(p.vx) / RUN_SPEED) * DT * 11;
      if (Math.floor(before / Math.PI) !== Math.floor(p.runPhase / Math.PI)) this.emit("step", p.x + p.w / 2, p.y + p.h);
    } else if (p.onGround) p.runPhase = 0;

    // boules de neige
    p.throwCooldown = Math.max(0, p.throwCooldown - 1);
    p.throwAnim = Math.max(0, p.throwAnim - 1);
    if (p.power && input.action && !this.prev.action && p.throwCooldown === 0 && this.snowballs.length < 3) {
      this.snowballs.push({
        id: this.nextId++, x: p.x + p.w / 2 + p.facing * 14 - 9, y: p.y + 18, w: 18, h: 18,
        vx: p.facing * 560 + p.vx * 0.3, vy: -260, onGround: false, alive: true, t: 0, bounces: 0,
      });
      p.throwCooldown = 14;
      p.throwAnim = 12;
      this.emit("throw", p.x + p.w / 2, p.y + 20);
    }

    p.invuln = Math.max(0, p.invuln - 1);

    // dangers : stalagmites
    const x0 = Math.floor((p.x + 4) / TILE), x1 = Math.floor((p.x + p.w - 5) / TILE);
    const yBottom = Math.floor((p.y + p.h - 2) / TILE);
    for (let cx = x0; cx <= x1; cx++) {
      if (this.tile(cx, yBottom) === "^" && p.y + p.h > yBottom * TILE + TILE * 0.45) this.hurt(p.vx >= 0 ? -1 : 1);
    }

    // chute dans un trou
    if (p.y > ROWS * TILE + 40) { p.hearts = 0; this.die(); return; }

    // points de passage et arrivée
    const cx = p.x + p.w / 2;
    for (const c of this.checkpoints) {
      if (!c.lit && Math.abs(cx - c.x) < 30 && p.y + p.h > c.y - 120) {
        c.lit = true;
        this.emit("checkpoint", c.x, c.y - 70);
      }
    }
    if (cx >= this.goal.x - 10 && p.y + p.h > this.goal.y - 260) {
      this.status = "clear";
      this.statusT = 0;
      p.vx = 0;
      this.emit("clear", this.goal.x, this.goal.y - 60);
    }
  }

  hurt(knockDir: number) {
    const p = this.player;
    if (p.invuln > 0 || this.status !== "play") return;
    p.hearts--;
    if (p.hearts <= 0) { this.die(); return; }
    p.invuln = HURT_INVULN_TICKS;
    p.vy = -520;
    p.vx = knockDir * 260;
    p.onGround = false;
    this.emit("hurt", p.x + p.w / 2, p.y + p.h / 2, true);
  }

  private die() {
    const p = this.player;
    if (p.dead) return;
    p.dead = true;
    p.hearts = 0;
    p.vy = -700;
    p.vx = 0;
    this.status = "dead";
    this.statusT = 0;
    this.emit("die", p.x + p.w / 2, p.y + p.h / 2, true);
  }

  /** Point de passage le plus récent allumé, en cases. */
  respawnPoint(): { x: number; y: number } | undefined {
    const lit = this.checkpoints.filter((c) => c.lit);
    if (!lit.length) return undefined;
    const c = lit[lit.length - 1];
    return { x: Math.floor(c.x / TILE), y: Math.floor(c.y / TILE) - 1 };
  }

  // ---------------------------------------------------------------- collisions

  private moveX(b: Box, dx: number): boolean {
    if (dx === 0) return false;
    b.x += dx;
    const y0 = Math.floor(b.y / TILE), y1 = Math.floor((b.y + b.h - 0.01) / TILE);
    if (dx > 0) {
      const cx = Math.floor((b.x + b.w - 0.01) / TILE);
      for (let cy = y0; cy <= y1; cy++) if (this.isSolid(cx, cy)) { b.x = cx * TILE - b.w; b.vx = 0; return true; }
    } else {
      const cx = Math.floor(b.x / TILE);
      for (let cy = y0; cy <= y1; cy++) if (this.isSolid(cx, cy)) { b.x = (cx + 1) * TILE; b.vx = 0; return true; }
    }
    return false;
  }

  private moveY(b: Box, dy: number, isPlayer = false): "floor" | "ceiling" | null {
    const prevBottom = b.y + b.h;
    b.y += dy;
    b.onGround = false;
    const x0 = Math.floor(b.x / TILE), x1 = Math.floor((b.x + b.w - 0.01) / TILE);
    if (dy > 0) {
      const cy = Math.floor((b.y + b.h - 0.01) / TILE);
      for (let cx = x0; cx <= x1; cx++) {
        const t = this.tile(cx, cy);
        const oneWay = t === "-" && prevBottom <= cy * TILE + 0.5;
        if (SOLID.has(t) || oneWay) {
          b.y = cy * TILE - b.h;
          b.vy = 0;
          b.onGround = true;
          return "floor";
        }
      }
    } else if (dy < 0) {
      const cy = Math.floor(b.y / TILE);
      const solids: number[] = [];
      for (let cx = x0; cx <= x1; cx++) if (this.isSolid(cx, cy)) solids.push(cx);
      if (solids.length) {
        if (isPlayer) {
          // correction de coin : on glisse si on n'accroche le plafond que de quelques pixels
          const leftOverlap = (solids[solids.length - 1] + 1) * TILE - b.x;
          const rightOverlap = b.x + b.w - solids[0] * TILE;
          if (solids.every((c) => c === x0) && leftOverlap <= CORNER_CORRECTION && !this.isSolid(x0 + 1, cy)) {
            b.x += leftOverlap; return null;
          }
          if (solids.every((c) => c === x1) && rightOverlap <= CORNER_CORRECTION && !this.isSolid(x1 - 1, cy)) {
            b.x -= rightOverlap; return null;
          }
          const center = b.x + b.w / 2;
          const best = solids.reduce((a, c) => (Math.abs((c + 0.5) * TILE - center) < Math.abs((a + 0.5) * TILE - center) ? c : a));
          this.bumpBlock(best, cy);
        }
        b.y = (cy + 1) * TILE;
        b.vy = 0;
        return "ceiling";
      }
    }
    return null;
  }

  private bumpBlock(cx: number, cy: number) {
    const t = this.tile(cx, cy);
    const x = cx * TILE + TILE / 2, y = cy * TILE;
    this.bumps.set(`${cx},${cy}`, this.tick);
    this.emit("bump", x, y);
    // un ennemi posé sur le bloc est éjecté
    for (const e of this.enemies) {
      if (e.alive && Math.abs(e.y + e.h - y) < 4 && e.x + e.w > cx * TILE && e.x < (cx + 1) * TILE) this.killEnemy(e, false);
    }
    if (!GIFTS.has(t)) return;
    this.grid[cy][cx] = "u";
    if (t === "?") {
      const it = this.makeItem("popbell", x - 16, y - 40, 32, 32);
      it.vy = -620;
      this.items.push(it);
      this.collectBell(x, y - 30);
    } else {
      const it = this.makeItem(t === "!" ? "star" : "cocoa", x - 18, y, 36, 36);
      it.emerge = 36;
      it.vx = 0;
      this.items.push(it);
      this.emit("spawnItem", x, y);
    }
  }

  private collectBell(x: number, y: number) {
    this.bells++;
    this.emit("bell", x, y);
  }

  // ---------------------------------------------------------------- ennemis

  private updateEnemies() {
    const p = this.player;
    for (const e of this.enemies) {
      e.t++;
      if (!e.alive) {
        e.deadT++;
        if (!e.squashed) { e.vy += FALL_GRAVITY * 0.5 * DT; e.y += e.vy * DT; e.x += e.vx * DT; }
        continue;
      }
      if (!e.active) {
        if (Math.abs(e.x - p.x) < 1050) { e.active = true; e.dir = e.x > p.x ? -1 : 1; }
        else continue;
      }
      if (e.kind === "crow") {
        e.x += e.dir * 95 * DT;
        e.y = e.baseY + Math.sin(e.t * 0.05) * 46;
        if (e.t % 240 === 0) e.dir *= -1;
      } else {
        const speed = e.kind === "snowman" ? 62 : 150;
        e.vx = e.dir * speed;
        e.vy = Math.min(e.vy + FALL_GRAVITY * DT, MAX_FALL);
        if (this.moveX(e, e.vx * DT)) e.dir *= -1;
        this.moveY(e, e.vy * DT);
        // les bonshommes de neige restent sur leur corniche
        if (e.kind === "snowman" && e.onGround) {
          const ahead = Math.floor((e.dir > 0 ? e.x + e.w + 2 : e.x - 2) / TILE);
          const below = Math.floor((e.y + e.h + 4) / TILE);
          if (!this.isSolid(ahead, below) && this.tile(ahead, below) !== "-") e.dir *= -1;
        }
        if (e.y > ROWS * TILE + 100) { e.alive = false; e.deadT = 999; }
      }
      if (this.status !== "play" || p.dead) continue;
      if (overlap(p, e, 3)) {
        const prevBottom = p.y + p.h - (p.vy * DT);
        if (p.vy > 0 && prevBottom <= e.y + 14) {
          this.killEnemy(e, true);
          p.vy = this.prev.jump ? STOMP_BOUNCE_HELD : STOMP_BOUNCE;
          p.jumping = this.prev.jump;
          p.y = e.y - p.h;
        } else {
          this.hurt(p.x + p.w / 2 < e.x + e.w / 2 ? -1 : 1);
        }
      }
    }
  }

  private killEnemy(e: Enemy, stomped: boolean) {
    e.alive = false;
    e.deadT = 0;
    e.squashed = stomped && e.kind !== "crow";
    if (!e.squashed) { e.vy = -420; e.vx = (e.dir || 1) * 60; }
    this.emit(stomped ? "stomp" : "hit", e.x + e.w / 2, e.y + e.h / 2, true);
  }

  // ---------------------------------------------------------------- objets

  private updateItems() {
    const p = this.player;
    for (const it of this.items) {
      if (!it.alive) continue;
      it.t++;
      if (it.kind === "popbell") {
        it.vy += 2200 * DT; it.y += it.vy * DT;
        if (it.t > 26) it.alive = false;
        continue;
      }
      if (it.kind === "bell") {
        if (this.status === "play" && overlap(p, it, -2)) { it.alive = false; this.collectBell(it.x + it.w / 2, it.y + it.h / 2); }
        continue;
      }
      // étoile et chocolat : sortent du cadeau puis glissent
      if (it.emerge > 0) {
        it.y -= 1; it.emerge--;
        if (it.emerge === 0) it.vx = 120;
      } else {
        it.vy = Math.min(it.vy + FALL_GRAVITY * 0.7 * DT, MAX_FALL);
        if (this.moveX(it, it.vx * DT)) it.vx = -it.vx || -120;
        if (it.vx === 0) it.vx = 120;
        this.moveY(it, it.vy * DT);
        if (it.kind === "star" && it.onGround) it.vy = -560;
        if (it.y > ROWS * TILE + 100) it.alive = false;
      }
      if (this.status === "play" && overlap(p, it, 0)) {
        it.alive = false;
        if (it.kind === "star") { p.power = true; this.emit("power", it.x + it.w / 2, it.y); }
        else { p.hearts = Math.min(MAX_HEARTS, p.hearts + 1); this.emit("heart", it.x + it.w / 2, it.y); }
      }
    }
    if (this.tick % 120 === 0) this.items = this.items.filter((i) => i.alive);
  }

  private updateSnowballs() {
    for (const s of this.snowballs) {
      s.t++;
      s.vy = Math.min(s.vy + 2000 * DT, 700);
      if (this.moveX(s, s.vx * DT)) { s.alive = false; this.emit("hit", s.x, s.y + 9); continue; }
      this.moveY(s, s.vy * DT);
      if (s.onGround) { s.vy = -380; if (++s.bounces > 3) s.alive = false; }
      if (s.t > 150 || s.y > ROWS * TILE) s.alive = false;
      for (const e of this.enemies) {
        if (e.alive && e.active && overlap(s, e, 0)) { this.killEnemy(e, false); s.alive = false; break; }
      }
    }
    this.snowballs = this.snowballs.filter((s) => s.alive);
  }
}

function overlap(a: { x: number; y: number; w: number; h: number }, b: { x: number; y: number; w: number; h: number }, inset: number) {
  return a.x + inset < b.x + b.w - inset && a.x + a.w - inset > b.x + inset && a.y + inset < b.y + b.h - inset && a.y + a.h - inset > b.y + inset;
}
