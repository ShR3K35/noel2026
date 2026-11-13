// Tout le dessin du jeu, en vectoriel : dégradés, ombres douces et reflets, sans aucune image à charger.
// Les fonctions dessinent en unités du monde (1 case = 48) ; l'appelant a déjà appliqué l'échelle.

import { ROWS, TILE } from "./config";
import type { Decor, Theme } from "./levels";
import type { Enemy, Item, Player, Snowball } from "./world";

type Ctx = CanvasRenderingContext2D;

export interface Palette {
  sky: string[];
  soilTop: string; soilBottom: string; soilSpeck: string; edge: string;
  snow: string; snowShade: string;
  glow: string;
  fog: string;
  far: string; farSnow: string; mid: string; near: string;
}

export const PALETTES: Record<Theme, Palette> = {
  village: {
    sky: ["#16234f", "#3c3b88", "#9a5f9c", "#f0a07a", "#ffd39a"],
    soilTop: "#6a4160", soilBottom: "#2c1a33", soilSpeck: "#82557a", edge: "#22122a",
    snow: "#f7fbff", snowShade: "#b8c9f0", glow: "#ffcf7a", fog: "rgba(255,190,170,0.22)",
    far: "#7a74b8", farSnow: "#e8e4ff", mid: "#3d3f7d", near: "#272a5c",
  },
  forest: {
    sky: ["#030817", "#071a38", "#0d3352", "#145063", "#1d6a6e"],
    soilTop: "#34465e", soilBottom: "#121a28", soilSpeck: "#4a5f7c", edge: "#0b111c",
    snow: "#eef6ff", snowShade: "#8fb2df", glow: "#9ff5d2", fog: "rgba(120,220,210,0.13)",
    far: "#1d4560", farSnow: "#bfe6ff", mid: "#0f2c40", near: "#0a1e2d",
  },
  city: {
    sky: ["#060619", "#14123d", "#2a2466", "#4a3a88", "#7a5a9e"],
    soilTop: "#7c3a4a", soilBottom: "#3a1a26", soilSpeck: "#94505f", edge: "#25101a",
    snow: "#f4f6ff", snowShade: "#aab6ea", glow: "#ffd27a", fog: "rgba(170,140,230,0.16)",
    far: "#3a3478", farSnow: "#d8d4ff", mid: "#221c50", near: "#16123a",
  },
};

// ------------------------------------------------------------------ utilitaires

export function hash(a: number, b = 0) {
  let h = (a * 374761393 + b * 668265263) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

export function rng(seed: number) {
  let s = Math.floor(seed * 2147483646) + 1;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

function ellipse(ctx: Ctx, x: number, y: number, rx: number, ry: number, rot = 0) {
  ctx.beginPath();
  ctx.ellipse(x, y, Math.max(0.1, rx), Math.max(0.1, ry), rot, 0, Math.PI * 2);
}

function rr(ctx: Ctx, x: number, y: number, w: number, h: number, r: number | number[]) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}

function lin(ctx: Ctx, x0: number, y0: number, x1: number, y1: number, stops: [number, string][]) {
  const g = ctx.createLinearGradient(x0, y0, x1, y1);
  for (const [o, c] of stops) g.addColorStop(o, c);
  return g;
}

function rad(ctx: Ctx, x: number, y: number, r0: number, r1: number, stops: [number, string][]) {
  const g = ctx.createRadialGradient(x, y, r0, x, y, r1);
  for (const [o, c] of stops) g.addColorStop(o, c);
  return g;
}

export function glow(ctx: Ctx, x: number, y: number, r: number, color: string, alpha = 1) {
  ctx.save();
  ctx.globalCompositeOperation = "lighter";
  ctx.globalAlpha = alpha;
  ctx.fillStyle = rad(ctx, x, y, 0, r, [[0, color], [0.35, color.replace(/[\d.]+\)$/, "0.35)")], [1, "rgba(0,0,0,0)"]]);
  ctx.fillRect(x - r, y - r, r * 2, r * 2);
  ctx.restore();
}

function star5(ctx: Ctx, x: number, y: number, r: number, inner = 0.45, rot = -Math.PI / 2) {
  ctx.beginPath();
  for (let i = 0; i < 10; i++) {
    const a = rot + (i * Math.PI) / 5;
    const rr2 = i % 2 ? r * inner : r;
    ctx.lineTo(x + Math.cos(a) * rr2, y + Math.sin(a) * rr2);
  }
  ctx.closePath();
}

// ------------------------------------------------------------------ Père Noël

export interface SantaPose { pom: number; blink: boolean }

export function drawSanta(ctx: Ctx, p: Player, t: number, pose: SantaPose) {
  const airborne = !p.onGround;
  const speed = Math.min(1, Math.abs(p.vx) / 336);
  const phase = p.runPhase;
  const swing = airborne ? 0 : Math.sin(phase) * speed;
  const bob = airborne ? 0 : Math.abs(Math.sin(phase)) * 3 * speed;
  const breathe = Math.sin(t * 3) * 0.8 * (1 - speed);

  let sx = 1, sy = 1;
  if (p.landSquash > 0) { sx = 1 + p.landSquash * 0.22; sy = 1 - p.landSquash * 0.18; }
  else if (airborne) { const s = Math.min(1, Math.abs(p.vy) / 1000) * 0.1; sx = 1 - s * 0.6; sy = 1 + s; }

  ctx.save();
  ctx.translate(p.x + p.w / 2, p.y + p.h + 2);
  // ombre portée
  if (p.onGround) {
    ctx.fillStyle = "rgba(20,25,60,0.28)";
    ellipse(ctx, 0, -1, 26 * sx, 5); ctx.fill();
  }
  ctx.scale(p.facing * sx, sy);
  ctx.translate(0, -bob);
  if (p.invuln > 0 && Math.floor(p.invuln / 4) % 2 === 0) ctx.globalAlpha = 0.35;

  const red = lin(ctx, -20, -60, 20, 0, [[0, "#ff4a4a"], [0.55, "#d61f2c"], [1, "#8e0f1d"]]);
  const fur = lin(ctx, 0, -10, 0, 10, [[0, "#ffffff"], [1, "#d7e2f7"]]);

  // hotte : sac de jouets sur le dos
  ctx.save();
  ctx.rotate(-0.12 + swing * 0.05);
  ctx.fillStyle = lin(ctx, -40, -60, -8, -20, [[0, "#c79a5b"], [1, "#7a5228"]]);
  ellipse(ctx, -22, -42, 16, 19, -0.3); ctx.fill();
  ctx.fillStyle = "#6b4520";
  ellipse(ctx, -14, -62, 7, 4, -0.5); ctx.fill();
  // jouets qui dépassent
  ctx.fillStyle = "#2fbf71"; rr(ctx, -30, -66, 10, 10, 2); ctx.fill();
  ctx.fillStyle = "#ffd34d"; rr(ctx, -21, -70, 8, 10, 2); ctx.fill();
  ctx.fillStyle = "#e53950"; ctx.fillRect(-27, -66, 3, 10);
  ctx.restore();

  // jambes
  const leg = (front: boolean) => {
    const s = front ? swing : -swing;
    ctx.save();
    ctx.translate(front ? 5 : -5, -20);
    ctx.rotate(airborne ? (front ? -0.6 : 0.35) : s * 0.75);
    ctx.fillStyle = front ? red : "#a3141f";
    rr(ctx, -6, 0, 12, 14, 5); ctx.fill();
    ctx.fillStyle = front ? "#1b1b26" : "#101018";
    rr(ctx, -7, 11, 17, 10, 5); ctx.fill();
    ctx.fillStyle = "rgba(255,255,255,0.25)";
    rr(ctx, -4, 12, 8, 3, 2); ctx.fill();
    ctx.restore();
  };
  leg(false);

  // bras arrière
  const arm = (front: boolean) => {
    const throwing = p.throwAnim > 0 && front;
    const a = throwing ? -2.2 + (12 - p.throwAnim) * 0.25 : airborne ? (front ? -2.4 : -2.0) : (front ? -swing : swing) * 0.9;
    ctx.save();
    ctx.translate(front ? 4 : -2, -44);
    ctx.rotate(a);
    ctx.fillStyle = front ? red : "#a3141f";
    rr(ctx, -5, 0, 10, 19, 5); ctx.fill();
    ctx.fillStyle = "#f3f6ff"; rr(ctx, -6, 14, 12, 5, 2.5); ctx.fill();
    ctx.fillStyle = front ? "#2b2f3d" : "#1d2029";
    ellipse(ctx, 0, 22, 5.5, 5.5); ctx.fill();
    ctx.restore();
  };
  arm(false);

  // corps
  ctx.fillStyle = red;
  ellipse(ctx, 0, -35 + breathe * 0.3, 19, 21); ctx.fill();
  // reflet
  ctx.fillStyle = "rgba(255,255,255,0.18)";
  ellipse(ctx, 6, -44, 7, 9, 0.4); ctx.fill();
  // bande de fourrure et ceinture
  ctx.fillStyle = fur;
  rr(ctx, -19, -19, 38, 7, 3.5); ctx.fill();
  ctx.fillRect(4, -54, 6, 36);
  ctx.fillStyle = "#16161f";
  ctx.fillRect(-19, -31, 38, 7);
  ctx.strokeStyle = "#ffcf3a"; ctx.lineWidth = 2.2;
  ctx.strokeRect(4, -32, 10, 9);
  ctx.fillStyle = "rgba(255,255,255,0.12)"; ctx.fillRect(-19, -31, 38, 2);

  leg(true);

  // tête
  ctx.save();
  ctx.translate(2, -58 + breathe * 0.4);
  ctx.fillStyle = lin(ctx, -10, -10, 10, 10, [[0, "#ffe2cc"], [1, "#f2b896"]]);
  ellipse(ctx, 0, 0, 12, 12); ctx.fill();
  // barbe
  ctx.fillStyle = lin(ctx, 0, 0, 0, 24, [[0, "#ffffff"], [1, "#cfdcf5"]]);
  ctx.beginPath();
  ctx.moveTo(-12, 0);
  ctx.quadraticCurveTo(-15, 14, -4, 23);
  ctx.quadraticCurveTo(4, 27, 10, 20);
  ctx.quadraticCurveTo(17, 12, 13, 0);
  ctx.quadraticCurveTo(0, 8, -12, 0);
  ctx.fill();
  ellipse(ctx, -9, 6, 6, 7); ctx.fill();
  // joue, nez, moustache
  ctx.fillStyle = "rgba(255,110,120,0.55)";
  ellipse(ctx, 7, 1, 4, 3); ctx.fill();
  ctx.fillStyle = "#ff8f8a";
  ellipse(ctx, 11, 0, 4, 3.6); ctx.fill();
  ctx.fillStyle = "rgba(255,255,255,0.7)"; ellipse(ctx, 12, -1, 1.3, 1); ctx.fill();
  ctx.fillStyle = "#ffffff";
  ellipse(ctx, 6, 6, 7, 3.5, -0.2); ctx.fill();
  ellipse(ctx, 13, 6, 5, 3, 0.3); ctx.fill();
  // œil
  if (pose.blink) {
    ctx.strokeStyle = "#2a1a1a"; ctx.lineWidth = 1.6;
    ctx.beginPath(); ctx.moveTo(3, -4); ctx.lineTo(8, -4); ctx.stroke();
  } else {
    ctx.fillStyle = "#1e1420"; ellipse(ctx, 6, -4, 2.3, 3); ctx.fill();
    ctx.fillStyle = "#fff"; ellipse(ctx, 6.8, -5.2, 0.9, 0.9); ctx.fill();
  }
  ctx.fillStyle = "#ffffff"; rr(ctx, 2, -10, 8, 3, 1.5); ctx.fill();
  // bonnet
  ctx.save();
  ctx.fillStyle = red;
  ctx.beginPath();
  ctx.moveTo(-13, -5);
  ctx.quadraticCurveTo(-8, -26, 6, -24);
  const tipX = -16 - Math.sin(pose.pom) * 10, tipY = -22 + Math.cos(pose.pom) * 8;
  ctx.quadraticCurveTo(-4, -22, tipX, tipY);
  ctx.quadraticCurveTo(-4, -16, 13, -7);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = fur;
  rr(ctx, -15, -11, 30, 8, 4); ctx.fill();
  ctx.fillStyle = "#ffffff";
  ellipse(ctx, tipX, tipY, 5, 5); ctx.fill();
  ctx.restore();
  ctx.restore();

  arm(true);
  ctx.restore();
}

// ------------------------------------------------------------------ ennemis

export function drawEnemy(ctx: Ctx, e: Enemy, t: number) {
  if (e.kind === "snowman") drawSnowman(ctx, e, t);
  else if (e.kind === "penguin") drawPenguin(ctx, e, t);
  else drawCrow(ctx, e, t);
}

function drawSnowman(ctx: Ctx, e: Enemy, t: number) {
  ctx.save();
  ctx.translate(e.x + e.w / 2, e.y + e.h);
  if (!e.alive && e.squashed) {
    const k = Math.min(1, e.deadT / 10);
    ctx.globalAlpha = Math.max(0, 1 - (e.deadT - 30) / 25);
    ctx.fillStyle = lin(ctx, 0, -14, 0, 0, [[0, "#ffffff"], [1, "#b9c9ef"]]);
    ellipse(ctx, 0, -6, 26 + k * 6, 9 - k * 2); ctx.fill();
    ctx.fillStyle = "#1a1a24"; rr(ctx, -9, -24 + k * 6, 18, 12, 2); ctx.fill();
    ctx.fillStyle = "#ff8a2a"; ctx.beginPath(); ctx.moveTo(8, -8); ctx.lineTo(24, -6); ctx.lineTo(8, -4); ctx.fill();
    ctx.restore();
    return;
  }
  if (!e.alive) ctx.rotate(e.deadT * 0.15);
  ctx.scale(e.dir < 0 ? -1 : 1, 1);
  const wob = Math.sin(e.t * 0.18) * 0.06;
  ctx.fillStyle = "rgba(20,25,60,0.25)";
  ellipse(ctx, 0, 0, 22, 4); ctx.fill();
  ctx.rotate(wob);
  const snow = (y: number, r: number) => {
    ctx.fillStyle = rad(ctx, -r * 0.35, y - r * 0.4, r * 0.1, r * 1.2, [[0, "#ffffff"], [0.6, "#e6eeff"], [1, "#a9bce8"]]);
    ellipse(ctx, 0, y, r, r * 0.95); ctx.fill();
  };
  snow(-15, 18);
  // bras en bois
  ctx.strokeStyle = "#5a3a22"; ctx.lineWidth = 2.5; ctx.lineCap = "round";
  const arm = Math.sin(e.t * 0.18) * 0.3;
  ctx.beginPath(); ctx.moveTo(10, -36); ctx.lineTo(24, -46 + arm * 10); ctx.lineTo(28, -50 + arm * 10); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(-10, -36); ctx.lineTo(-24, -44 - arm * 10); ctx.stroke();
  snow(-38, 13);
  // boutons de charbon
  ctx.fillStyle = "#22222c";
  ellipse(ctx, 3, -40, 2, 2); ctx.fill(); ellipse(ctx, 3, -33, 2, 2); ctx.fill();
  ellipse(ctx, 2, -17, 2.4, 2.4); ctx.fill();
  // écharpe
  ctx.fillStyle = "#2f9e63";
  rr(ctx, -12, -50, 24, 6, 3); ctx.fill();
  ctx.save(); ctx.translate(-8, -46); ctx.rotate(0.3 + Math.sin(e.t * 0.25) * 0.25);
  rr(ctx, -4, 0, 7, 16, 3); ctx.fill();
  ctx.fillStyle = "#e5394a"; ctx.fillRect(-4, 5, 7, 3); ctx.fillRect(-4, 11, 7, 2);
  ctx.restore();
  ctx.fillStyle = "#e5394a"; ctx.fillRect(-4, -50, 3, 6); ctx.fillRect(4, -50, 3, 6);
  // tête
  ctx.fillStyle = rad(ctx, -4, -62, 1, 14, [[0, "#ffffff"], [1, "#b7c7ee"]]);
  ellipse(ctx, 0, -58, 11, 11); ctx.fill();
  // yeux fâchés
  ctx.fillStyle = "#1a1a24";
  ellipse(ctx, 2, -60, 1.9, 2.3); ctx.fill(); ellipse(ctx, 8, -60, 1.9, 2.3); ctx.fill();
  ctx.strokeStyle = "#1a1a24"; ctx.lineWidth = 1.8;
  ctx.beginPath(); ctx.moveTo(-1, -66); ctx.lineTo(4, -63); ctx.moveTo(11, -66); ctx.lineTo(6, -63); ctx.stroke();
  // carotte
  ctx.fillStyle = lin(ctx, 6, -57, 22, -57, [[0, "#ff9a3c"], [1, "#e0611a"]]);
  ctx.beginPath(); ctx.moveTo(6, -59); ctx.lineTo(22, -56); ctx.lineTo(6, -54); ctx.fill();
  // haut-de-forme
  ctx.fillStyle = "#1c1c26";
  rr(ctx, -12, -69, 24, 4, 2); ctx.fill();
  rr(ctx, -8, -84, 16, 16, 2); ctx.fill();
  ctx.fillStyle = "#c2263a"; ctx.fillRect(-8, -73, 16, 3);
  ctx.fillStyle = "rgba(255,255,255,0.15)"; ctx.fillRect(-6, -83, 3, 12);
  ctx.restore();
}

function drawPenguin(ctx: Ctx, e: Enemy, t: number) {
  void t;
  ctx.save();
  ctx.translate(e.x + e.w / 2, e.y + e.h);
  if (!e.alive && e.squashed) {
    ctx.globalAlpha = Math.max(0, 1 - (e.deadT - 30) / 25);
    ctx.scale(1.4, 0.35);
  } else if (!e.alive) ctx.rotate(e.deadT * 0.2);
  ctx.scale(e.dir < 0 ? -1 : 1, 1);
  const waddle = Math.sin(e.t * 0.45) * 0.12;
  ctx.fillStyle = "rgba(20,25,60,0.25)";
  ellipse(ctx, 0, 0, 18, 4); ctx.fill();
  // pieds
  ctx.fillStyle = "#ff9b2f";
  ellipse(ctx, -6 + Math.sin(e.t * 0.45) * 3, -2, 7, 3); ctx.fill();
  ellipse(ctx, 7 - Math.sin(e.t * 0.45) * 3, -2, 7, 3); ctx.fill();
  ctx.rotate(waddle);
  ctx.fillStyle = lin(ctx, -14, -40, 14, 0, [[0, "#3a4a7a"], [1, "#141a33"]]);
  ellipse(ctx, 0, -19, 15, 19); ctx.fill();
  ctx.fillStyle = lin(ctx, 0, -34, 0, 0, [[0, "#ffffff"], [1, "#d5def5"]]);
  ellipse(ctx, 4, -16, 9, 14); ctx.fill();
  // aileron
  ctx.fillStyle = "#1e2748";
  ellipse(ctx, -10, -18, 4, 10, 0.4 + Math.sin(e.t * 0.45) * 0.3); ctx.fill();
  // yeux + bec
  ctx.fillStyle = "#fff"; ellipse(ctx, 6, -30, 3.4, 3.8); ctx.fill();
  ctx.fillStyle = "#111"; ellipse(ctx, 7, -30, 1.8, 2.2); ctx.fill();
  ctx.fillStyle = "#ff9b2f";
  ctx.beginPath(); ctx.moveTo(10, -27); ctx.lineTo(19, -24); ctx.lineTo(10, -22); ctx.fill();
  // bonnet rayé
  ctx.fillStyle = "#e53950";
  ctx.beginPath(); ctx.moveTo(-12, -33); ctx.quadraticCurveTo(0, -48, 12, -33); ctx.fill();
  ctx.fillStyle = "#ffffff"; rr(ctx, -13, -35, 26, 5, 2.5); ctx.fill();
  ellipse(ctx, -2, -45, 4, 4); ctx.fill();
  ctx.restore();
}

function drawCrow(ctx: Ctx, e: Enemy, t: number) {
  void t;
  ctx.save();
  ctx.translate(e.x + e.w / 2, e.y + e.h / 2);
  if (!e.alive) ctx.rotate(e.deadT * 0.2);
  ctx.scale(e.dir < 0 ? -1 : 1, 1);
  const flap = Math.sin(e.t * 0.35);
  ctx.fillStyle = lin(ctx, 0, -10, 0, 12, [[0, "#3b3557"], [1, "#16131f"]]);
  ellipse(ctx, 0, 2, 16, 11); ctx.fill();
  // ailes
  ctx.fillStyle = "#26213a";
  ctx.beginPath(); ctx.moveTo(-6, 0); ctx.quadraticCurveTo(-4, -22 * flap, -22, -20 * flap - 4); ctx.quadraticCurveTo(-14, 2, -6, 6); ctx.fill();
  ctx.beginPath(); ctx.moveTo(-14, 4); ctx.lineTo(-26, 0); ctx.lineTo(-24, 8); ctx.fill();
  // tête, écharpe, bec, œil
  ctx.fillStyle = "#2a2540"; ellipse(ctx, 13, -5, 9, 8); ctx.fill();
  ctx.fillStyle = "#3fb6e8"; rr(ctx, 4, 1, 12, 4, 2); ctx.fill();
  ctx.fillStyle = "#ffb43a";
  ctx.beginPath(); ctx.moveTo(20, -7); ctx.lineTo(30, -3); ctx.lineTo(20, -1); ctx.fill();
  ctx.fillStyle = "#ffe36a"; ellipse(ctx, 15, -7, 2.6, 2.6); ctx.fill();
  ctx.fillStyle = "#111"; ellipse(ctx, 15.6, -7, 1.2, 1.6); ctx.fill();
  ctx.strokeStyle = "#111"; ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.moveTo(11, -11); ctx.lineTo(18, -9); ctx.stroke();
  ctx.restore();
}

// ------------------------------------------------------------------ objets

export function drawBell(ctx: Ctx, x: number, y: number, t: number, scale = 1) {
  ctx.save();
  ctx.translate(x, y);
  ctx.scale(scale, scale);
  ctx.rotate(Math.sin(t * 4) * 0.18);
  ctx.fillStyle = lin(ctx, -12, -12, 12, 12, [[0, "#fff3a8"], [0.45, "#ffc62b"], [1, "#c27a0e"]]);
  ctx.beginPath();
  ctx.moveTo(0, -13);
  ctx.bezierCurveTo(9, -13, 10, -4, 10, 2);
  ctx.quadraticCurveTo(11, 8, 15, 10);
  ctx.lineTo(-15, 10);
  ctx.quadraticCurveTo(-11, 8, -10, 2);
  ctx.bezierCurveTo(-10, -4, -9, -13, 0, -13);
  ctx.fill();
  ctx.fillStyle = "#b86e0a"; ellipse(ctx, 0, 12, 4, 3.5); ctx.fill();
  ctx.fillStyle = "#e53950";
  ctx.beginPath(); ctx.moveTo(0, -12); ctx.lineTo(-9, -19); ctx.lineTo(-7, -11); ctx.fill();
  ctx.beginPath(); ctx.moveTo(0, -12); ctx.lineTo(9, -19); ctx.lineTo(7, -11); ctx.fill();
  ctx.fillStyle = "rgba(255,255,255,0.75)"; ellipse(ctx, -4, -4, 2.4, 5, 0.3); ctx.fill();
  ctx.restore();
}

export function drawItem(ctx: Ctx, it: Item, t: number) {
  const cx = it.x + it.w / 2, cy = it.y + it.h / 2;
  if (it.kind === "bell" || it.kind === "popbell") {
    const bob = it.kind === "bell" ? Math.sin(t * 3 + it.id) * 3 : 0;
    glow(ctx, cx, cy + bob, 30, "rgba(255,210,90,0.5)", 0.8);
    drawBell(ctx, cx, cy + bob, t + it.id * 0.3);
    return;
  }
  if (it.kind === "star") {
    glow(ctx, cx, cy, 46, "rgba(255,240,140,0.7)");
    ctx.save();
    ctx.translate(cx, cy);
    ctx.rotate(Math.sin(t * 5) * 0.2);
    ctx.fillStyle = lin(ctx, -16, -16, 16, 16, [[0, "#fff8c4"], [0.5, "#ffd23a"], [1, "#f08a12"]]);
    star5(ctx, 0, 0, 19); ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,0.8)"; ctx.lineWidth = 1.5; ctx.stroke();
    ctx.fillStyle = "#3a2410"; ellipse(ctx, -4, -1, 1.8, 3); ctx.fill(); ellipse(ctx, 4, -1, 1.8, 3); ctx.fill();
    ctx.restore();
    return;
  }
  // chocolat chaud
  glow(ctx, cx, cy, 40, "rgba(255,150,150,0.5)");
  ctx.save();
  ctx.translate(cx, cy + 4);
  for (let i = 0; i < 3; i++) {
    ctx.strokeStyle = `rgba(255,255,255,${0.45 - i * 0.1})`; ctx.lineWidth = 2.5;
    ctx.beginPath();
    const ph = t * 3 + i * 2;
    ctx.moveTo(-6 + i * 6, -16);
    ctx.bezierCurveTo(-10 + i * 6 + Math.sin(ph) * 4, -24, -2 + i * 6 - Math.sin(ph) * 4, -28, -6 + i * 6, -36);
    ctx.stroke();
  }
  ctx.fillStyle = lin(ctx, -14, 0, 14, 0, [[0, "#ff5a6a"], [1, "#b81d36"]]);
  rr(ctx, -14, -14, 28, 26, 6); ctx.fill();
  ctx.strokeStyle = "#b81d36"; ctx.lineWidth = 4;
  ctx.beginPath(); ctx.arc(15, -1, 7, -Math.PI / 2, Math.PI / 2); ctx.stroke();
  ctx.fillStyle = "#6b3a22"; ellipse(ctx, 0, -14, 13, 3.5); ctx.fill();
  ctx.fillStyle = "#ffffff"; ellipse(ctx, -3, -16, 6, 3); ctx.fill(); ellipse(ctx, 4, -16, 4, 2.5); ctx.fill();
  heart(ctx, 0, 0, 7, "#ffffff");
  ctx.restore();
}

export function heart(ctx: Ctx, x: number, y: number, s: number, color: string | CanvasGradient) {
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x, y + s * 0.9);
  ctx.bezierCurveTo(x - s * 1.6, y - s * 0.1, x - s * 0.8, y - s * 1.3, x, y - s * 0.45);
  ctx.bezierCurveTo(x + s * 0.8, y - s * 1.3, x + s * 1.6, y - s * 0.1, x, y + s * 0.9);
  ctx.fill();
}

export function drawSnowball(ctx: Ctx, s: Snowball) {
  const cx = s.x + s.w / 2, cy = s.y + s.h / 2;
  glow(ctx, cx, cy, 22, "rgba(190,230,255,0.6)", 0.6);
  ctx.fillStyle = rad(ctx, cx - 3, cy - 3, 1, 11, [[0, "#ffffff"], [1, "#9fc0f0"]]);
  ellipse(ctx, cx, cy, 9, 9); ctx.fill();
}

// ------------------------------------------------------------------ blocs cadeaux

const GIFT_COLORS: Record<string, [string, string, string]> = {
  "?": ["#ff5263", "#c2183a", "#ffd34d"],
  "!": ["#3fd18b", "#178a55", "#fff1a8"],
  "+": ["#5ab0ff", "#2563c9", "#ffffff"],
};

export function drawGiftBlock(ctx: Ctx, cx: number, cy: number, kind: string, t: number, bump: number) {
  const x = cx * TILE, y = cy * TILE - bump;
  if (kind === "u") {
    ctx.fillStyle = lin(ctx, x, y, x, y + TILE, [[0, "#a77b52"], [1, "#6b4527"]]);
    rr(ctx, x + 1, y + 1, TILE - 2, TILE - 2, 6); ctx.fill();
    ctx.strokeStyle = "rgba(60,30,10,0.5)"; ctx.lineWidth = 2;
    ctx.strokeRect(x + 7, y + 7, TILE - 14, TILE - 14);
    ctx.fillStyle = "rgba(255,255,255,0.15)"; ctx.fillRect(x + 4, y + 4, TILE - 8, 3);
    return;
  }
  const [c0, c1, ribbon] = GIFT_COLORS[kind] ?? GIFT_COLORS["?"];
  const pulse = 0.5 + 0.5 * Math.sin(t * 4 + cx);
  glow(ctx, x + TILE / 2, y + TILE / 2, 46, "rgba(255,220,140,0.35)", 0.4 + pulse * 0.3);
  ctx.fillStyle = lin(ctx, x, y, x + TILE, y + TILE, [[0, c0], [1, c1]]);
  rr(ctx, x + 1, y + 1, TILE - 2, TILE - 2, 7); ctx.fill();
  // motif
  ctx.fillStyle = "rgba(255,255,255,0.12)";
  for (let i = 0; i < 4; i++) { ellipse(ctx, x + 10 + (i % 2) * 26, y + 12 + Math.floor(i / 2) * 24, 3, 3); ctx.fill(); }
  // ruban
  ctx.fillStyle = ribbon;
  ctx.fillRect(x + TILE / 2 - 4, y + 1, 8, TILE - 2);
  ctx.fillRect(x + 1, y + TILE / 2 - 4, TILE - 2, 8);
  // nœud
  ctx.save();
  ctx.translate(x + TILE / 2, y + 6);
  ctx.rotate(Math.sin(t * 3 + cx) * 0.08);
  ellipse(ctx, -8, -2, 8, 5, -0.4); ctx.fill();
  ellipse(ctx, 8, -2, 8, 5, 0.4); ctx.fill();
  ellipse(ctx, 0, 0, 4, 4); ctx.fill();
  ctx.restore();
  // reflet brillant qui passe
  ctx.save();
  rr(ctx, x + 1, y + 1, TILE - 2, TILE - 2, 7); ctx.clip();
  const sweep = ((t * 0.6 + cx * 0.13) % 2.4) - 0.7;
  ctx.fillStyle = "rgba(255,255,255,0.35)";
  ctx.beginPath();
  ctx.moveTo(x + sweep * TILE, y); ctx.lineTo(x + sweep * TILE + 12, y);
  ctx.lineTo(x + sweep * TILE - 8, y + TILE); ctx.lineTo(x + sweep * TILE - 20, y + TILE);
  ctx.fill();
  ctx.restore();
  ctx.strokeStyle = "rgba(255,255,255,0.35)"; ctx.lineWidth = 1.5;
  rr(ctx, x + 2, y + 2, TILE - 4, TILE - 4, 6); ctx.stroke();
}

// ------------------------------------------------------------------ décor du terrain

const isGround = (c: string) => c === "#";
const isSolidTile = (c: string) => "#=?!+uH".includes(c);

/** Dessine les colonnes [c0, c1] du terrain statique (sol, glace, planches, pics, cheminées). */
export function drawTerrain(ctx: Ctx, grid: string[][], width: number, c0: number, c1: number, theme: Theme, extraRows: number) {
  const pal = PALETTES[theme];
  const at = (x: number, y: number) => (x < 0 || x >= width ? (y >= 0 && y < ROWS ? grid[y][Math.max(0, Math.min(width - 1, x))] : " ") : y < 0 ? " " : y >= ROWS ? grid[ROWS - 1][x] : grid[y][x]);
  const bottomY = (ROWS + extraRows) * TILE;

  // 1) corps du sol : une colonne continue jusqu'en bas, dégradé vertical
  for (let x = c0; x <= c1; x++) {
    for (let y = 0; y < ROWS; y++) {
      if (!isGround(at(x, y)) || isGround(at(x, y - 1))) continue;
      let y2 = y; while (y2 < ROWS && isGround(at(x, y2))) y2++;
      const top = y * TILE, bot = y2 >= ROWS ? bottomY : y2 * TILE;
      if (theme === "city") drawBuildingColumn(ctx, x, top, bot, pal, at(x - 1, y) !== "#", at(x + 1, y) !== "#");
      else {
        ctx.fillStyle = lin(ctx, 0, top, 0, top + 4 * TILE, [[0, pal.soilTop], [1, pal.soilBottom]]);
        ctx.fillRect(x * TILE, top, TILE, bot - top);
        // pierres et texture
        for (let k = 0; k < 6; k++) {
          const yy = top + 20 + hash(x, k) * (bot - top - 26);
          const xx = x * TILE + 6 + hash(k, x) * (TILE - 12);
          ctx.fillStyle = k % 2 ? pal.soilSpeck : "rgba(0,0,0,0.18)";
          ellipse(ctx, xx, yy, 3 + hash(x + k) * 5, 2 + hash(k * 7, x) * 3); ctx.fill();
        }
        // bords sombres sur les faces exposées
        if (!isGround(at(x - 1, y))) { ctx.fillStyle = lin(ctx, x * TILE, 0, x * TILE + 12, 0, [[0, pal.edge], [1, "rgba(0,0,0,0)"]]); ctx.fillRect(x * TILE, top, 12, bot - top); }
        if (!isGround(at(x + 1, y))) { ctx.fillStyle = lin(ctx, x * TILE + TILE, 0, x * TILE + TILE - 12, 0, [[0, pal.edge], [1, "rgba(0,0,0,0)"]]); ctx.fillRect(x * TILE + TILE - 12, top, 12, bot - top); }
      }
      y = y2;
    }
  }

  // 2) blocs de glace, planches, cheminées, pics
  for (let x = c0; x <= c1; x++) {
    for (let y = 0; y < ROWS; y++) {
      const c = at(x, y);
      const px = x * TILE, py = y * TILE;
      if (c === "=") drawIceBlock(ctx, px, py, x, y);
      else if (c === "-") drawPlank(ctx, px, py, at(x - 1, y) !== "-", at(x + 1, y) !== "-");
      else if (c === "H") drawChimney(ctx, px, py, at(x, y - 1) !== "H");
      else if (c === "^") drawSpikes(ctx, px, py, x);
    }
  }

  // 3) couche de neige sur toute surface exposée
  for (let x = c0; x <= c1; x++) {
    for (let y = 0; y < ROWS; y++) {
      const c = at(x, y);
      if (!("#=-H".includes(c))) continue;
      if (isSolidTile(at(x, y - 1)) || at(x, y - 1) === "-") continue;
      const leftOpen = at(x - 1, y) !== c && !isSolidTile(at(x - 1, y));
      const rightOpen = at(x + 1, y) !== c && !isSolidTile(at(x + 1, y));
      drawSnowCap(ctx, x * TILE, y * TILE, leftOpen, rightOpen, x, y, pal, c === "-" ? 7 : c === "#" ? 15 : 11);
    }
  }
}

function drawBuildingColumn(ctx: Ctx, x: number, top: number, bot: number, pal: Palette, leftEdge: boolean, rightEdge: boolean) {
  const px = x * TILE;
  ctx.fillStyle = lin(ctx, 0, top, 0, top + 5 * TILE, [[0, pal.soilTop], [1, pal.soilBottom]]);
  ctx.fillRect(px, top, TILE, bot - top);
  // briques
  ctx.strokeStyle = "rgba(0,0,0,0.16)"; ctx.lineWidth = 1.2;
  for (let yy = top + 14; yy < bot; yy += 12) {
    ctx.beginPath(); ctx.moveTo(px, yy); ctx.lineTo(px + TILE, yy); ctx.stroke();
    const off = (Math.floor((yy - top) / 12) % 2) * 12;
    for (let xx = px + off; xx < px + TILE; xx += 24) { ctx.beginPath(); ctx.moveTo(xx, yy); ctx.lineTo(xx, yy + 12); ctx.stroke(); }
  }
  // bord de toit
  ctx.fillStyle = "#2a1520"; ctx.fillRect(px, top + 10, TILE, 6);
  // fenêtres, certaines allumées
  for (let yy = top + 40; yy + 34 < bot; yy += 62) {
    const row = Math.floor(yy / 62);
    const lit = hash(x, row) > 0.4;
    const wx = px + 12, wy = yy;
    ctx.fillStyle = "#1a0f18"; rr(ctx, wx - 2, wy - 2, 28, 34, 4); ctx.fill();
    if (lit) {
      ctx.fillStyle = lin(ctx, 0, wy, 0, wy + 30, [[0, "#ffe9a8"], [1, "#ffb347"]]);
      rr(ctx, wx, wy, 24, 30, 3); ctx.fill();
      glow(ctx, wx + 12, wy + 15, 44, "rgba(255,190,90,0.45)", 0.5);
      ctx.fillStyle = "rgba(120,50,20,0.55)"; ctx.fillRect(wx + 11, wy, 2, 30); ctx.fillRect(wx, wy + 14, 24, 2);
    } else {
      ctx.fillStyle = "#2b2550"; rr(ctx, wx, wy, 24, 30, 3); ctx.fill();
      ctx.fillStyle = "rgba(180,200,255,0.15)"; ctx.fillRect(wx + 3, wy + 3, 6, 24);
    }
    ctx.fillStyle = "#f4f6ff"; rr(ctx, wx - 4, wy + 30, 32, 4, 2); ctx.fill();
  }
  if (leftEdge) { ctx.fillStyle = lin(ctx, px, 0, px + 10, 0, [[0, pal.edge], [1, "rgba(0,0,0,0)"]]); ctx.fillRect(px, top, 10, bot - top); }
  if (rightEdge) { ctx.fillStyle = lin(ctx, px + TILE, 0, px + TILE - 10, 0, [[0, pal.edge], [1, "rgba(0,0,0,0)"]]); ctx.fillRect(px + TILE - 10, top, 10, bot - top); }
}

function drawIceBlock(ctx: Ctx, x: number, y: number, cx: number, cy: number) {
  ctx.fillStyle = lin(ctx, x, y, x + TILE, y + TILE, [[0, "#d8f4ff"], [0.5, "#7cc8f2"], [1, "#3b7fc4"]]);
  rr(ctx, x + 1, y + 1, TILE - 2, TILE - 2, 8); ctx.fill();
  ctx.strokeStyle = "rgba(255,255,255,0.65)"; ctx.lineWidth = 2;
  rr(ctx, x + 3, y + 3, TILE - 6, TILE - 6, 6); ctx.stroke();
  ctx.fillStyle = "rgba(255,255,255,0.55)";
  ctx.beginPath(); ctx.moveTo(x + 8, y + 30); ctx.lineTo(x + 26, y + 8); ctx.lineTo(x + 32, y + 8); ctx.lineTo(x + 14, y + 30); ctx.fill();
  // fissures
  ctx.strokeStyle = "rgba(30,80,140,0.35)"; ctx.lineWidth = 1.2;
  ctx.beginPath();
  const a = hash(cx, cy);
  ctx.moveTo(x + 30 + a * 10, y + 20); ctx.lineTo(x + 36, y + 32); ctx.lineTo(x + 30, y + 40);
  ctx.stroke();
}

function drawPlank(ctx: Ctx, x: number, y: number, left: boolean, right: boolean) {
  ctx.fillStyle = lin(ctx, 0, y, 0, y + 16, [[0, "#b07a48"], [1, "#6e4425"]]);
  rr(ctx, x - (left ? 0 : 1), y, TILE + (left ? 0 : 1) + (right ? 0 : 1), 15, left || right ? 5 : 0); ctx.fill();
  ctx.strokeStyle = "rgba(50,25,10,0.4)"; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(x, y + 7); ctx.lineTo(x + TILE, y + 7); ctx.stroke();
  ctx.fillStyle = "#4a2c16";
  ellipse(ctx, x + 8, y + 7, 2, 2); ctx.fill(); ellipse(ctx, x + TILE - 8, y + 7, 2, 2); ctx.fill();
  // cordes / guirlande sous la planche
  if (left || right) {
    ctx.strokeStyle = "#d4c1a1"; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(x + (left ? 6 : TILE - 6), y + 14); ctx.lineTo(x + (left ? 6 : TILE - 6), y + 30); ctx.stroke();
  }
  ctx.strokeStyle = "#2f8f5a"; ctx.lineWidth = 3;
  ctx.beginPath(); ctx.moveTo(x, y + 15); ctx.quadraticCurveTo(x + TILE / 2, y + 26, x + TILE, y + 15); ctx.stroke();
  const colors = ["#ff5a6a", "#ffd34d", "#5ab0ff"];
  ctx.fillStyle = colors[Math.floor(x / TILE) % 3];
  ellipse(ctx, x + TILE / 2, y + 21, 3, 3); ctx.fill();
}

function drawChimney(ctx: Ctx, x: number, y: number, top: boolean) {
  ctx.fillStyle = lin(ctx, x, 0, x + TILE, 0, [[0, "#c2453f"], [0.6, "#a3302c"], [1, "#6e1c1d"]]);
  ctx.fillRect(x + 4, y, TILE - 8, TILE);
  ctx.strokeStyle = "rgba(255,220,200,0.25)"; ctx.lineWidth = 1.2;
  for (let yy = y + 8; yy < y + TILE; yy += 10) {
    ctx.beginPath(); ctx.moveTo(x + 4, yy); ctx.lineTo(x + TILE - 4, yy); ctx.stroke();
  }
  if (top) {
    ctx.fillStyle = lin(ctx, 0, y, 0, y + 10, [[0, "#8a8aa0"], [1, "#4d4d63"]]);
    rr(ctx, x, y, TILE, 10, 3); ctx.fill();
  }
}

function drawSpikes(ctx: Ctx, x: number, y: number, cx: number) {
  for (let i = 0; i < 3; i++) {
    const bx = x + 4 + i * 14, h = 22 + hash(cx, i) * 10;
    ctx.fillStyle = lin(ctx, bx, y + TILE - h, bx + 12, y + TILE, [[0, "#ffffff"], [0.4, "#a8dcff"], [1, "#3c7fc0"]]);
    ctx.beginPath(); ctx.moveTo(bx, y + TILE); ctx.lineTo(bx + 6, y + TILE - h); ctx.lineTo(bx + 13, y + TILE); ctx.fill();
  }
}

function drawSnowCap(ctx: Ctx, x: number, y: number, leftOpen: boolean, rightOpen: boolean, cx: number, cy: number, pal: Palette, thick: number) {
  const over = 5;
  const x0 = x - (leftOpen ? over : 0), x1 = x + TILE + (rightOpen ? over : 0);
  // ombre bleutée sous la neige
  ctx.fillStyle = pal.snowShade;
  ctx.beginPath();
  ctx.moveTo(x0, y - 2);
  ctx.lineTo(x1, y - 2);
  ctx.lineTo(x1, y + thick - 2);
  const drips = 3;
  for (let i = drips; i >= 0; i--) {
    const px = x0 + ((x1 - x0) * i) / drips;
    const d = thick + 2 + hash(cx * 3 + i, cy) * 8;
    ctx.quadraticCurveTo(px + (x1 - x0) / drips / 2, y + d + 4, px, y + thick);
  }
  ctx.closePath();
  ctx.fill();
  // couche blanche, bord arrondi et bosselé
  ctx.fillStyle = lin(ctx, 0, y - 8, 0, y + thick, [[0, "#ffffff"], [1, pal.snow]]);
  ctx.beginPath();
  ctx.moveTo(x0, y + thick - 4);
  if (leftOpen) ctx.quadraticCurveTo(x0 - 2, y - 6, x0 + 10, y - 7); else ctx.lineTo(x0, y - 6);
  const bumps = 3;
  for (let i = 1; i <= bumps; i++) {
    const px = x0 + ((x1 - x0) * i) / bumps;
    ctx.quadraticCurveTo(px - (x1 - x0) / bumps / 2, y - 9 - hash(cx, i + cy) * 4, px - (i === bumps && rightOpen ? 10 : 0), y - 6);
  }
  if (rightOpen) ctx.quadraticCurveTo(x1 + 2, y - 6, x1, y + thick - 4); else ctx.lineTo(x1, y + thick - 4);
  for (let i = drips; i >= 0; i--) {
    const px = x0 + ((x1 - x0) * i) / drips;
    const d = thick - 2 + hash(cx * 3 + i, cy) * 7;
    ctx.quadraticCurveTo(px + (x1 - x0) / drips / 2, y + d, px, y + thick - 4);
  }
  ctx.closePath();
  ctx.fill();
  // paillettes
  ctx.fillStyle = "rgba(255,255,255,0.95)";
  for (let k = 0; k < 2; k++) {
    const sx = x + 6 + hash(cx, k + 40) * (TILE - 12), sy = y - 3 + hash(k + 9, cx) * 6;
    ellipse(ctx, sx, sy, 1.2, 1.2); ctx.fill();
  }
}

// ------------------------------------------------------------------ éléments de décor

/** Taille (en unités) du sprite mis en cache pour un décor, et son point d'ancrage (bas-centre). */
export function decorBox(kind: Decor["kind"]): { w: number; h: number } {
  switch (kind) {
    case "pine": return { w: 150, h: 250 };
    case "pineSmall": return { w: 100, h: 150 };
    case "tree": return { w: 220, h: 330 };
    case "house": return { w: 260, h: 250 };
    case "lamp": return { w: 60, h: 170 };
    case "fence": return { w: 150, h: 60 };
    case "candy": return { w: 50, h: 110 };
    case "sign": return { w: 100, h: 100 };
    case "gifts": return { w: 130, h: 80 };
    case "antenna": return { w: 70, h: 120 };
    case "garland": return { w: 200, h: 60 };
    case "snowpile": return { w: 140, h: 50 };
  }
}

/** Dessine un décor avec son pied en (0,0). */
export function drawDecorShape(ctx: Ctx, d: Decor, pal: Palette) {
  const r = rng(d.seed || 0.5);
  switch (d.kind) {
    case "pine": case "pineSmall": case "tree": {
      const big = d.kind === "tree";
      const s = d.kind === "pineSmall" ? 0.6 : big ? 1.3 : 1;
      ctx.fillStyle = "#4a2d1b"; ctx.fillRect(-7 * s, -26 * s, 14 * s, 28 * s);
      const layers = 4;
      for (let i = 0; i < layers; i++) {
        const w = (68 - i * 13) * s, yb = (-22 - i * 42) * s, ht = 70 * s;
        ctx.fillStyle = lin(ctx, -w, yb - ht, w, yb, [[0, "#3a9a6a"], [0.5, "#1f6b4a"], [1, "#0f3f2e"]]);
        ctx.beginPath();
        ctx.moveTo(0, yb - ht);
        ctx.quadraticCurveTo(w * 0.3, yb - ht * 0.4, w, yb);
        ctx.quadraticCurveTo(0, yb + 10 * s, -w, yb);
        ctx.quadraticCurveTo(-w * 0.3, yb - ht * 0.4, 0, yb - ht);
        ctx.fill();
        // neige sur les branches
        ctx.fillStyle = "#f4f8ff";
        ctx.beginPath();
        ctx.moveTo(0, yb - ht);
        ctx.quadraticCurveTo(w * 0.25, yb - ht * 0.5, w * 0.75, yb - 6 * s);
        ctx.quadraticCurveTo(w * 0.4, yb - ht * 0.3, w * 0.1, yb - ht * 0.45);
        ctx.quadraticCurveTo(-w * 0.3, yb - ht * 0.25, -w * 0.8, yb - 4 * s);
        ctx.quadraticCurveTo(-w * 0.3, yb - ht * 0.55, 0, yb - ht);
        ctx.fill();
      }
      if (big) {
        // boules et guirlande lumineuse
        const cols = ["#ff4d5e", "#ffd34d", "#5ab0ff", "#ff8ad8", "#ffffff"];
        for (let i = 0; i < 26; i++) {
          const lv = Math.floor(r() * 4);
          const w = (60 - lv * 13) * s;
          const yy = (-30 - lv * 42 - r() * 30) * s;
          const xx = (r() * 2 - 1) * w * (1 - ((-yy / s - 30 - lv * 42) / 70)) * 0.8;
          ctx.fillStyle = cols[i % cols.length];
          ellipse(ctx, xx, yy, 4.5 * s, 4.5 * s); ctx.fill();
          ctx.fillStyle = "rgba(255,255,255,0.7)"; ellipse(ctx, xx - 1.5, yy - 1.5, 1.4, 1.4); ctx.fill();
        }
        ctx.fillStyle = lin(ctx, -16, -260, 16, -230, [[0, "#fff6b0"], [1, "#ffb21f"]]);
        star5(ctx, 0, (-22 - 3 * 42 - 70) * s - 6, 20); ctx.fill();
      }
      break;
    }
    case "house": {
      // maison à colombages, fenêtres chaudes
      ctx.fillStyle = lin(ctx, -100, -150, 100, 0, [[0, "#6d4a73"], [1, "#3e2748"]]);
      ctx.fillRect(-100, -140, 200, 140);
      ctx.fillStyle = "#4b2f2a"; ctx.fillRect(-100, -140, 200, 8);
      for (const wx of [-70, 30]) {
        ctx.fillStyle = "#24131f"; rr(ctx, wx - 3, -103, 46, 50, 5); ctx.fill();
        ctx.fillStyle = lin(ctx, 0, -100, 0, -56, [[0, "#fff1b8"], [1, "#ffa940"]]);
        rr(ctx, wx, -100, 40, 44, 4); ctx.fill();
        ctx.fillStyle = "rgba(110,50,20,0.6)"; ctx.fillRect(wx + 19, -100, 2, 44); ctx.fillRect(wx, -80, 40, 2);
        ctx.fillStyle = "#f6f9ff"; rr(ctx, wx - 6, -56, 52, 7, 3); ctx.fill();
        ctx.fillStyle = "#2f8f5a"; ellipse(ctx, wx + 20, -112, 12, 5); ctx.fill();
      }
      // porte avec couronne
      ctx.fillStyle = "#7a2a2a"; rr(ctx, -16, -72, 32, 72, [10, 10, 0, 0]); ctx.fill();
      ctx.strokeStyle = "#2f8f5a"; ctx.lineWidth = 6; ctx.beginPath(); ctx.arc(0, -48, 9, 0, Math.PI * 2); ctx.stroke();
      ctx.fillStyle = "#e5394a"; ellipse(ctx, 0, -38, 4, 3); ctx.fill();
      ctx.fillStyle = "#ffd34d"; ellipse(ctx, 10, -34, 2, 2); ctx.fill();
      // toit
      ctx.fillStyle = lin(ctx, 0, -250, 0, -130, [[0, "#9a3a46"], [1, "#5e1f2c"]]);
      ctx.beginPath(); ctx.moveTo(-122, -130); ctx.lineTo(0, -230); ctx.lineTo(122, -130); ctx.closePath(); ctx.fill();
      ctx.fillStyle = "#6e2a2a"; ctx.fillRect(50, -228, 26, 60);
      ctx.fillStyle = "#f6f9ff";
      ctx.beginPath(); ctx.moveTo(-128, -128); ctx.lineTo(0, -238); ctx.lineTo(128, -128);
      ctx.quadraticCurveTo(110, -120, 96, -132); ctx.lineTo(0, -214); ctx.lineTo(-96, -132); ctx.quadraticCurveTo(-110, -120, -128, -128); ctx.fill();
      rr(ctx, 46, -236, 34, 10, 5); ctx.fill();
      // guirlande sous le toit
      for (let i = 0; i < 11; i++) {
        const t = i / 10;
        const lx = -110 + t * 220, ly = -128 + Math.sin(t * Math.PI * 4) * 5 + 6;
        ctx.fillStyle = ["#ff4d5e", "#ffd34d", "#5ab0ff", "#3fd18b"][i % 4];
        ellipse(ctx, lx, ly, 3.5, 4.5); ctx.fill();
      }
      break;
    }
    case "lamp": {
      ctx.fillStyle = "#1d2236"; ctx.fillRect(-4, -150, 8, 150); rr(ctx, -10, -8, 20, 8, 3); ctx.fill();
      ctx.strokeStyle = "#1d2236"; ctx.lineWidth = 4;
      ctx.beginPath(); ctx.moveTo(0, -150); ctx.quadraticCurveTo(14, -160, 12, -146); ctx.stroke();
      ctx.fillStyle = "#1d2236"; ctx.beginPath(); ctx.moveTo(-14, -146); ctx.lineTo(14, -146); ctx.lineTo(8, -158); ctx.lineTo(-8, -158); ctx.fill();
      ctx.fillStyle = lin(ctx, 0, -146, 0, -124, [[0, "#fff6d0"], [1, "#ffbe55"]]); rr(ctx, -10, -146, 20, 22, 3); ctx.fill();
      ctx.fillStyle = "#f6f9ff"; rr(ctx, -16, -163, 32, 7, 3.5); ctx.fill();
      // ruban rayé
      ctx.strokeStyle = "#e5394a"; ctx.lineWidth = 3;
      for (let yy = -110; yy < -10; yy += 18) { ctx.beginPath(); ctx.moveTo(-4, yy); ctx.lineTo(4, yy + 8); ctx.stroke(); }
      break;
    }
    case "fence": {
      ctx.fillStyle = "#7a5230";
      for (let i = 0; i < 5; i++) { rr(ctx, -70 + i * 34, -46, 10, 46, [5, 5, 0, 0]); ctx.fill(); }
      ctx.fillRect(-72, -34, 148, 6); ctx.fillRect(-72, -16, 148, 6);
      ctx.fillStyle = "#f6f9ff";
      for (let i = 0; i < 5; i++) { ellipse(ctx, -65 + i * 34, -47, 7, 4); ctx.fill(); }
      rr(ctx, -74, -38, 152, 5, 2.5); ctx.fill();
      break;
    }
    case "candy": {
      ctx.save();
      ctx.lineCap = "round";
      ctx.strokeStyle = "#ffffff"; ctx.lineWidth = 12;
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, -80); ctx.arc(-14, -80, 14, 0, Math.PI, true); ctx.stroke();
      ctx.strokeStyle = "#e5394a"; ctx.lineWidth = 12; ctx.setLineDash([8, 10]);
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, -80); ctx.arc(-14, -80, 14, 0, Math.PI, true); ctx.stroke();
      ctx.restore();
      ctx.fillStyle = "#2f8f5a"; ellipse(ctx, 0, -60, 9, 5, 0.5); ctx.fill(); ellipse(ctx, 8, -62, 9, 5, -0.5); ctx.fill();
      ctx.fillStyle = "#e5394a"; ellipse(ctx, 3, -58, 4, 4); ctx.fill();
      break;
    }
    case "sign": {
      ctx.fillStyle = "#6b4527"; ctx.fillRect(-4, -70, 8, 70);
      ctx.fillStyle = lin(ctx, 0, -95, 0, -55, [[0, "#b98454"], [1, "#7a4f2c"]]);
      ctx.beginPath(); ctx.moveTo(-40, -92); ctx.lineTo(30, -92); ctx.lineTo(44, -74); ctx.lineTo(30, -56); ctx.lineTo(-40, -56); ctx.fill();
      ctx.fillStyle = "#fff4dc"; ctx.font = "700 16px Fredoka, system-ui, sans-serif"; ctx.textAlign = "center";
      ctx.fillText("Pôle Nord", 0, -68);
      ctx.fillStyle = "#f6f9ff"; rr(ctx, -42, -97, 76, 7, 3.5); ctx.fill();
      break;
    }
    case "gifts": {
      const g = (x: number, w: number, h: number, c: string, rib: string) => {
        ctx.fillStyle = c; rr(ctx, x, -h, w, h, 4); ctx.fill();
        ctx.fillStyle = rib; ctx.fillRect(x + w / 2 - 3, -h, 6, h); ctx.fillRect(x, -h * 0.55, w, 5);
        ellipse(ctx, x + w / 2 - 6, -h - 3, 6, 4, -0.4); ctx.fill(); ellipse(ctx, x + w / 2 + 6, -h - 3, 6, 4, 0.4); ctx.fill();
      };
      g(-55, 44, 44, "#e5394a", "#ffd34d");
      g(-8, 36, 60, "#2f9e63", "#ffffff");
      g(30, 30, 30, "#5ab0ff", "#ff8ad8");
      break;
    }
    case "antenna": {
      ctx.strokeStyle = "#2a2840"; ctx.lineWidth = 4;
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(0, -100); ctx.moveTo(-24, -80); ctx.lineTo(24, -80); ctx.moveTo(-16, -94); ctx.lineTo(16, -94); ctx.stroke();
      ctx.fillStyle = "#ff4d5e"; ellipse(ctx, 0, -104, 4, 4); ctx.fill();
      ctx.fillStyle = "#f6f9ff"; rr(ctx, -26, -84, 52, 5, 2.5); ctx.fill();
      break;
    }
    case "garland": {
      ctx.strokeStyle = "#1e1c33"; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(-90, -50); ctx.quadraticCurveTo(0, -10, 90, -50); ctx.stroke();
      ctx.fillStyle = "#2a2840"; ctx.fillRect(-92, -54, 4, 54); ctx.fillRect(88, -54, 4, 54);
      for (let i = 1; i < 12; i++) {
        const t = i / 12, lx = -90 + 180 * t, ly = -50 + 160 * t * (1 - t) * 0.5 + 1;
        ctx.fillStyle = ["#ff4d5e", "#ffd34d", "#5ab0ff", "#3fd18b"][i % 4];
        ellipse(ctx, lx, ly + 4, 3.5, 5); ctx.fill();
      }
      break;
    }
    case "snowpile": {
      ctx.fillStyle = pal.snowShade;
      ellipse(ctx, 0, 0, 66, 30); ctx.fill();
      ctx.fillStyle = "#ffffff";
      ellipse(ctx, -18, -6, 40, 24); ctx.fill(); ellipse(ctx, 26, -2, 34, 18); ctx.fill();
      break;
    }
  }
}

/** Petites lumières qui scintillent par-dessus le sprite en cache. */
export function decorLights(kind: Decor["kind"]): { x: number; y: number; r: number; color: string }[] {
  switch (kind) {
    case "lamp": return [{ x: 0, y: -136, r: 90, color: "rgba(255,200,110,0.55)" }];
    case "house": return [{ x: -50, y: -78, r: 80, color: "rgba(255,190,90,0.45)" }, { x: 50, y: -78, r: 80, color: "rgba(255,190,90,0.45)" }];
    case "tree": return [{ x: 0, y: -260, r: 80, color: "rgba(255,230,140,0.6)" }];
    case "antenna": return [{ x: 0, y: -104, r: 24, color: "rgba(255,80,90,0.7)" }];
    default: return [];
  }
}

// ------------------------------------------------------------------ point de passage et arrivée

export function drawLantern(ctx: Ctx, x: number, y: number, lit: boolean, t: number) {
  ctx.save();
  ctx.translate(x, y);
  ctx.fillStyle = "#2a2a3f"; ctx.fillRect(-3, -96, 6, 96);
  ctx.fillStyle = "#2a2a3f"; rr(ctx, -16, -128, 32, 6, 3); ctx.fill(); rr(ctx, -13, -100, 26, 5, 2); ctx.fill();
  ctx.fillStyle = lit ? lin(ctx, 0, -122, 0, -100, [[0, "#fff6c8"], [1, "#ffb84a"]]) : "rgba(160,180,230,0.35)";
  rr(ctx, -11, -122, 22, 22, 3); ctx.fill();
  ctx.strokeStyle = "#2a2a3f"; ctx.lineWidth = 2; ctx.strokeRect(-11, -122, 22, 22);
  if (lit) {
    const f = 0.85 + Math.sin(t * 9) * 0.08 + Math.sin(t * 23) * 0.05;
    glow(ctx, 0, -111, 120 * f, "rgba(255,190,90,0.7)", 0.9);
    ctx.fillStyle = "#fff"; ellipse(ctx, 0, -110, 3, 5 * f); ctx.fill();
  }
  // ruban
  ctx.fillStyle = "#e5394a"; ctx.beginPath(); ctx.moveTo(-3, -92); ctx.lineTo(-14, -80); ctx.lineTo(-3, -84); ctx.fill();
  ctx.fillStyle = "#f6f9ff"; rr(ctx, -18, -133, 36, 7, 3.5); ctx.fill();
  ctx.restore();
}

export function drawSleigh(ctx: Ctx, x: number, y: number, t: number) {
  ctx.save();
  ctx.translate(x, y);
  // patins
  ctx.strokeStyle = "#ffd34d"; ctx.lineWidth = 5; ctx.lineCap = "round";
  ctx.beginPath(); ctx.moveTo(-90, -4); ctx.lineTo(70, -4); ctx.quadraticCurveTo(100, -4, 96, -30); ctx.stroke();
  ctx.lineWidth = 4;
  for (const sx of [-60, 30]) { ctx.beginPath(); ctx.moveTo(sx, -4); ctx.lineTo(sx, -24); ctx.stroke(); }
  // caisse
  ctx.fillStyle = lin(ctx, 0, -100, 0, -20, [[0, "#ff4a4a"], [1, "#8e0f1d"]]);
  ctx.beginPath();
  ctx.moveTo(-100, -90); ctx.quadraticCurveTo(-110, -40, -80, -22); ctx.lineTo(60, -22);
  ctx.quadraticCurveTo(90, -26, 86, -60); ctx.lineTo(40, -60); ctx.quadraticCurveTo(10, -60, 0, -80); ctx.lineTo(-100, -90);
  ctx.fill();
  ctx.strokeStyle = "#ffd34d"; ctx.lineWidth = 3;
  ctx.beginPath(); ctx.moveTo(-96, -82); ctx.quadraticCurveTo(-104, -40, -78, -30); ctx.lineTo(58, -30); ctx.stroke();
  // cadeaux dans le traîneau
  const gifts: [number, number, number, string, string][] = [[-90, 40, 40, "#2f9e63", "#ffd34d"], [-55, 34, 52, "#5ab0ff", "#ffffff"], [-25, 30, 34, "#ffd34d", "#e5394a"]];
  for (const [gx, w, h, c, rb] of gifts) {
    ctx.fillStyle = c; rr(ctx, gx, -80 - h + 20, w, h, 4); ctx.fill();
    ctx.fillStyle = rb; ctx.fillRect(gx + w / 2 - 3, -80 - h + 20, 6, h);
  }
  ctx.fillStyle = "#f6f9ff"; rr(ctx, -104, -96, 108, 9, 4.5); ctx.fill();
  // étincelles magiques
  for (let i = 0; i < 6; i++) {
    const a = t * 1.5 + i;
    const sx = Math.cos(a) * 110, sy = -60 + Math.sin(a * 1.3) * 50;
    ctx.fillStyle = `rgba(255,240,170,${0.5 + 0.5 * Math.sin(t * 6 + i)})`;
    star5(ctx, sx, sy, 5, 0.4, t + i); ctx.fill();
  }
  glow(ctx, 0, -60, 160, "rgba(255,220,140,0.5)", 0.5 + 0.1 * Math.sin(t * 3));
  ctx.restore();
}

export function drawBigChimney(ctx: Ctx, x: number, y: number, t: number) {
  ctx.save();
  ctx.translate(x, y);
  ctx.fillStyle = lin(ctx, -50, 0, 50, 0, [[0, "#c2453f"], [0.6, "#a3302c"], [1, "#5e1718"]]);
  ctx.fillRect(-46, -170, 92, 170);
  ctx.strokeStyle = "rgba(255,220,200,0.22)"; ctx.lineWidth = 1.5;
  for (let yy = -160; yy < 0; yy += 14) {
    ctx.beginPath(); ctx.moveTo(-46, yy); ctx.lineTo(46, yy); ctx.stroke();
    const off = (Math.floor(yy / 14) % 2) * 15;
    for (let xx = -46 + off; xx < 46; xx += 30) { ctx.beginPath(); ctx.moveTo(xx, yy); ctx.lineTo(xx, yy + 14); ctx.stroke(); }
  }
  ctx.fillStyle = lin(ctx, 0, -184, 0, -166, [[0, "#9a9ab2"], [1, "#4d4d63"]]);
  rr(ctx, -58, -186, 116, 20, 5); ctx.fill();
  ctx.fillStyle = "#f6f9ff"; rr(ctx, -62, -194, 124, 12, 6); ctx.fill();
  // lueur du foyer
  glow(ctx, 0, -190, 120, "rgba(255,160,70,0.6)", 0.6 + Math.sin(t * 5) * 0.1);
  // fumée
  for (let i = 0; i < 6; i++) {
    const k = ((t * 0.35 + i / 6) % 1);
    ctx.fillStyle = `rgba(230,230,255,${0.35 * (1 - k)})`;
    ellipse(ctx, Math.sin(k * 6 + i) * 18 + k * 40, -200 - k * 200, 18 + k * 30, 14 + k * 24); ctx.fill();
  }
  // couronne
  ctx.strokeStyle = "#2f8f5a"; ctx.lineWidth = 9; ctx.beginPath(); ctx.arc(0, -100, 22, 0, Math.PI * 2); ctx.stroke();
  ctx.fillStyle = "#e5394a"; ellipse(ctx, -6, -78, 6, 5); ctx.fill(); ellipse(ctx, 6, -78, 6, 5); ctx.fill();
  ctx.restore();
}

// ------------------------------------------------------------------ arrière-plans (mis en cache)

export interface BgLayer { canvas: HTMLCanvasElement; factor: number; worldW: number; worldH: number; yWorld: number }

/** Couches lointaines, dessinées une fois à l'échelle de l'écran puis répétées horizontalement. */
export function buildBackground(theme: Theme, scale: number, viewTopWorld: number): BgLayer[] {
  const pal = PALETTES[theme];
  const layers: BgLayer[] = [];
  const make = (worldW: number, worldH: number, yWorld: number, factor: number, draw: (c: Ctx) => void) => {
    const cv = document.createElement("canvas");
    cv.width = Math.ceil(worldW * scale);
    cv.height = Math.ceil(worldH * scale);
    const c = cv.getContext("2d")!;
    c.scale(scale, scale);
    draw(c);
    layers.push({ canvas: cv, factor, worldW, worldH, yWorld });
  };
  const r = rng(theme === "village" ? 0.21 : theme === "forest" ? 0.47 : 0.73);
  const horizon = 560;

  // montagnes lointaines
  make(2400, 520, horizon - 420, 0.12, (c) => {
    const peaks: [number, number][] = [];
    for (let x = -100; x <= 2500; x += 160 + r() * 140) peaks.push([x, 60 + r() * 220]);
    c.fillStyle = lin(c, 0, 0, 0, 520, [[0, pal.far], [1, pal.mid]]);
    c.beginPath(); c.moveTo(0, 520);
    peaks.forEach(([x, h], i) => {
      const prev = peaks[i - 1];
      if (prev) c.quadraticCurveTo((prev[0] + x) / 2, 420 - (prev[1] + h) / 3, x, 420 - h);
      else c.lineTo(x, 420 - h);
    });
    c.lineTo(2400, 520); c.closePath(); c.fill();
    // neige sur les sommets
    c.fillStyle = pal.farSnow;
    c.globalAlpha = 0.85;
    for (const [x, h] of peaks) {
      c.beginPath();
      c.moveTo(x, 420 - h);
      c.lineTo(x + 34, 420 - h + 46); c.lineTo(x + 14, 420 - h + 38); c.lineTo(x, 420 - h + 54); c.lineTo(x - 16, 420 - h + 36); c.lineTo(x - 34, 420 - h + 46);
      c.closePath(); c.fill();
    }
    c.globalAlpha = 1;
  });

  if (theme === "city") {
    // silhouettes de la ville
    for (const [factor, col, yOff, hMax, lights] of [[0.25, pal.mid, 120, 300, 0.35], [0.45, pal.near, 220, 260, 0.45]] as const) {
      make(2000, 520, horizon - 420 + yOff, factor, (c) => {
        let x = 0;
        while (x < 2000) {
          const w = 60 + r() * 110, h = 80 + r() * hMax;
          c.fillStyle = col;
          c.fillRect(x, 520 - h, w, h);
          if (r() > 0.6) { c.beginPath(); c.moveTo(x, 520 - h); c.lineTo(x + w / 2, 520 - h - 40 - r() * 60); c.lineTo(x + w, 520 - h); c.fill(); }
          c.fillStyle = "rgba(242,244,255,0.8)"; c.beginPath(); c.roundRect(x - 2, 520 - h - 3, w + 4, 5, 2.5); c.fill();
          for (let wy = 520 - h + 18; wy < 500; wy += 26) for (let wx = x + 10; wx < x + w - 14; wx += 22) {
            if (r() < lights) { c.fillStyle = r() > 0.5 ? "#ffd27a" : "#ffb35a"; c.fillRect(wx, wy, 10, 13); }
          }
          x += w + r() * 20;
        }
      });
    }
  } else {
    // forêts de sapins, deux plans
    for (const [factor, col, yOff, size] of [[0.3, pal.mid, 140, 0.8], [0.5, pal.near, 230, 1.1]] as const) {
      make(2000, 520, horizon - 420 + yOff, factor, (c) => {
        c.fillStyle = col;
        c.beginPath(); c.moveTo(0, 520);
        for (let x = 0; x <= 2000; x += 40) c.lineTo(x, 400 - Math.sin(x * 0.004) * 30 - r() * 10);
        c.lineTo(2000, 520); c.fill();
        for (let x = -20; x < 2020; x += 22 + r() * 30) {
          const h = (90 + r() * 120) * size, base = 420 - Math.sin(x * 0.004) * 30;
          c.fillStyle = col;
          c.beginPath(); c.moveTo(x, base - h);
          c.lineTo(x + h * 0.28, base); c.lineTo(x - h * 0.28, base); c.fill();
          c.fillStyle = "rgba(240,246,255,0.75)";
          for (let k = 0; k < 3; k++) {
            const ty = base - h + (k + 0.6) * h * 0.26, tw = (k + 0.6) * h * 0.075;
            c.beginPath(); c.moveTo(x - tw, ty); c.quadraticCurveTo(x, ty - 6, x + tw, ty); c.lineTo(x + tw * 0.4, ty + 4); c.lineTo(x - tw * 0.6, ty + 4); c.fill();
          }
        }
        if (theme === "village" && factor > 0.4) {
          // hameau lointain avec fenêtres allumées
          for (let i = 0; i < 6; i++) {
            const hx = 150 + i * 330 + r() * 80, hy = 430;
            c.fillStyle = "#3a2c55"; c.fillRect(hx, hy - 50, 70, 50);
            c.beginPath(); c.moveTo(hx - 10, hy - 48); c.lineTo(hx + 35, hy - 90); c.lineTo(hx + 80, hy - 48); c.fill();
            c.fillStyle = "#f3f6ff"; c.beginPath(); c.moveTo(hx - 12, hy - 46); c.lineTo(hx + 35, hy - 94); c.lineTo(hx + 82, hy - 46); c.lineTo(hx + 70, hy - 50); c.lineTo(hx + 35, hy - 84); c.lineTo(hx, hy - 50); c.fill();
            c.fillStyle = "#ffcf7a"; c.fillRect(hx + 12, hy - 36, 14, 14); c.fillRect(hx + 44, hy - 36, 14, 14);
          }
        }
      });
    }
  }
  void viewTopWorld;
  return layers;
}

export function skyGradient(ctx: Ctx, theme: Theme, h: number) {
  const s = PALETTES[theme].sky;
  return lin(ctx, 0, 0, 0, h, s.map((c, i) => [i / (s.length - 1), c] as [number, string]));
}
