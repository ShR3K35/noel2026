import { describe, expect, it } from "vitest";
import { BUFFER_TICKS, COYOTE_TICKS, ROWS, TILE } from "./config";
import { LEVELS, type LevelDef } from "./levels";
import { deadzone } from "./input";
import { NO_INPUT, World, type Input } from "./world";

/** Terrain plat de 40 cases, sol sur 3 cases, avec un rebord à droite (trou à partir de la colonne 20). */
function flat(extra: (rows: string[][]) => void = () => {}): LevelDef {
  const rows = Array.from({ length: ROWS }, () => Array.from({ length: 40 }, () => " "));
  for (let x = 0; x < 20; x++) for (let y = 12; y < ROWS; y++) rows[y][x] = "#";
  extra(rows);
  return { name: "test", subtitle: "", theme: "village", width: 40, grid: rows.map((r) => r.join("")), spawns: [{ kind: "goal", x: 39, y: 11 }], decor: [], start: { x: 2, y: 11 }, finale: false };
}

const hold = (over: Partial<Input>): Input => ({ ...NO_INPUT, ...over });

function settle(w: World) {
  for (let i = 0; i < 30; i++) w.step(NO_INPUT);
  expect(w.player.onGround).toBe(true);
}

function jumpApex(w: World, ticksHeld: number) {
  const ground = w.player.y;
  let top = ground;
  for (let i = 0; i < 80; i++) {
    w.step(i < ticksHeld ? hold({ jump: true }) : NO_INPUT);
    top = Math.min(top, w.player.y);
  }
  return (ground - top) / TILE;
}

describe("saut (skill platformer)", () => {
  it("atteint la hauteur réglée en tenant le bouton", () => {
    const w = new World(flat());
    settle(w);
    const h = jumpApex(w, 80);
    // 4,2 cases visées, la demi-gravité au sommet ajoute un peu de hauteur
    expect(h).toBeGreaterThan(4.1);
    expect(h).toBeLessThan(5.2);
  });

  it("un appui bref donne un petit saut", () => {
    const w = new World(flat());
    settle(w);
    const tap = jumpApex(w, 1);
    expect(tap).toBeGreaterThan(0.7);
    expect(tap).toBeLessThan(2);
  });

  it("appui un tick avant l'atterrissage : le saut part quand même (tampon)", () => {
    const w = new World(flat());
    settle(w);
    for (let i = 0; i < 4; i++) w.step(hold({ jump: true }));
    let pressedAt = -1;
    for (let i = 0; i < 120 && pressedAt < 0; i++) {
      // on relâche pendant la chute, puis on réappuie juste avant de toucher le sol
      const p = w.player;
      const nearGround = p.vy > 0 && 12 * TILE - (p.y + p.h) < p.vy / 60 * 1.5;
      if (nearGround) { pressedAt = w.tick; w.step(hold({ jump: true })); } else w.step(NO_INPUT);
    }
    expect(pressedAt).toBeGreaterThan(0);
    let jumped = false;
    for (let i = 0; i < BUFFER_TICKS + 2; i++) { w.step(hold({ jump: true })); if (w.player.vy < -500) jumped = true; }
    expect(jumped).toBe(true);
  });

  it(`coyote : saut accepté ${COYOTE_TICKS} ticks après le bord, refusé après`, () => {
    for (const late of [COYOTE_TICKS - 1, COYOTE_TICKS + 2]) {
      const w = new World(flat());
      w.player.x = 18 * TILE;
      settle(w);
      let leftAt = -1;
      for (let i = 0; i < 200 && leftAt < 0; i++) { w.step(hold({ right: true })); if (!w.player.onGround) leftAt = w.tick; }
      for (let i = 1; i < late; i++) w.step(hold({ right: true }));
      w.step(hold({ right: true, jump: true }));
      const jumped = w.player.vy < -500;
      expect(jumped).toBe(late <= COYOTE_TICKS);
    }
  });

  it("pas de double saut dans la fenêtre coyote", () => {
    const w = new World(flat());
    settle(w);
    w.step(hold({ jump: true }));
    w.step(NO_INPUT);
    const vy = w.player.vy;
    w.step(hold({ jump: true }));
    expect(w.player.vy).toBeGreaterThan(vy - 1); // pas relancé
  });

  it("frapper un cadeau par-dessous donne une clochette et vide le bloc", () => {
    const w = new World(flat((r) => { r[8][2] = "?"; }));
    settle(w);
    for (let i = 0; i < 40; i++) w.step(hold({ jump: true }));
    expect(w.grid[8][2]).toBe("u");
    expect(w.bells).toBe(1);
  });

  it("sauter sur un ennemi l'écrase et fait rebondir", () => {
    const lvl = flat();
    lvl.spawns.push({ kind: "snowman", x: 8, y: 11 });
    const w = new World(lvl);
    settle(w);
    const e = w.enemies[0];
    w.player.x = e.x;
    w.player.y = e.y - w.player.h - 40;
    w.player.vy = 300;
    for (let i = 0; i < 6; i++) w.step(NO_INPUT);
    expect(e.alive).toBe(false);
    expect(w.player.vy).toBeLessThan(0);
    expect(w.player.hearts).toBe(3);
  });

  it("tomber dans un trou fait perdre la vie", () => {
    const w = new World(flat());
    w.player.x = 19 * TILE;
    for (let i = 0; i < 200; i++) w.step(hold({ right: true }));
    expect(w.status).toBe("dead");
  });
});

describe("niveaux", () => {
  for (const lvl of LEVELS) {
    it(`${lvl.name} : départ sur le sol, arrivée atteignable, sauts à portée`, () => {
      const grid = lvl.grid;
      expect(grid).toHaveLength(ROWS);
      grid.forEach((r) => expect(r).toHaveLength(lvl.width));
      expect(grid[lvl.start.y + 1][lvl.start.x]).toBe("#");
      expect(lvl.spawns.filter((s) => s.kind === "goal")).toHaveLength(1);

      // profil du sol (plus haute case solide de chaque colonne, plates-formes comprises)
      // sol et cheminées d'abord ; une plate-forme ne compte que si elle franchit un trou
      const surface = (x: number) => {
        for (let y = 0; y < ROWS; y++) if ("#H".includes(grid[y][x])) return y;
        for (let y = 0; y < ROWS; y++) if ("=-".includes(grid[y][x])) return y;
        return ROWS;
      };
      let lastX = 0, lastY = surface(0);
      for (let x = 1; x < lvl.width; x++) {
        const y = surface(x);
        if (y === ROWS) continue;
        const gap = x - lastX - 1;
        expect(gap, `trou trop large avant la colonne ${x}`).toBeLessThanOrEqual(4);
        expect(lastY - y, `marche trop haute en colonne ${x}`).toBeLessThanOrEqual(3);
        lastX = x; lastY = y;
      }
      // chaque ennemi au sol se tient au-dessus de quelque chose
      for (const s of lvl.spawns.filter((s) => s.kind === "snowman" || s.kind === "penguin")) {
        expect(" ^".includes(grid[s.y][s.x]), `${s.kind} en ${s.x},${s.y} dans un mur`).toBe(true);
        expect(surface(s.x)).toBeLessThan(ROWS);
      }
    });
  }
});

describe("joystick analogique", () => {
  it("pousser à moitié fait marcher, à fond fait courir", () => {
    const speed = (axis: number) => {
      const w = new World(flat());
      w.player.x = 10 * TILE;
      settle(w);
      for (let i = 0; i < 30; i++) w.step(hold({ axis }));
      return w.player.vx;
    };
    const half = speed(0.4), full = speed(1);
    expect(full).toBeCloseTo(7 * TILE, 0);
    expect(half).toBeGreaterThan(0.4 * full);
    expect(half).toBeLessThan(0.8 * full);
    expect(speed(-1)).toBeCloseTo(-7 * TILE, 0);
  });

  it("zone morte : un pouce qui tremble ne fait pas bouger", () => {
    expect(deadzone(0.1)).toBe(0);
    expect(deadzone(-0.15)).toBe(0);
    expect(deadzone(1)).toBe(1);
    expect(deadzone(-1.4)).toBe(-1);
    expect(deadzone(0.6)).toBeGreaterThan(0.4);
  });
});
