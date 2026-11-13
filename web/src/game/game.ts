// Boucle de jeu, caméra, effets, interface et écrans.
// Pas fixe 60 Hz + rendu à la fréquence de l'écran ; écran tactile, clavier et manette.

import {
  BgLayer, PALETTES, buildBackground, decorBox, decorLights, drawBell, drawBigChimney, drawDecorShape, drawEnemy,
  drawGiftBlock, drawItem, drawLantern, drawSanta, drawSleigh, drawSnowball, drawTerrain, glow, hash, heart, skyGradient,
} from "./art";
import { Sound } from "./audio";
import { DT, MAX_HEARTS, ROWS, TILE } from "./config";
import { Controls, type TouchButton } from "./input";
import { LEVELS, type Decor, type Theme } from "./levels";
import { World, type GameEvent } from "./world";

const LEVEL_H = ROWS * TILE;
const CHUNK = 8;
const FONT = "Fredoka, 'Baloo 2', 'Trebuchet MS', system-ui, sans-serif";

type Screen = "title" | "play" | "pause" | "over" | "win";

interface Particle {
  x: number; y: number; vx: number; vy: number; life: number; max: number; size: number;
  color: string; g: number; kind: "dot" | "spark" | "ring" | "confetti" | "text"; rot?: number; text?: string; add?: boolean;
}

interface UiButton { x: number; y: number; w: number; h: number; label: string; action: () => void; primary?: boolean; icon?: string }

interface Saved { unlocked: number; best: number; music: boolean; shake: boolean }

function loadSaved(): Saved {
  const base: Saved = { unlocked: 0, best: 0, music: true, shake: true };
  try {
    const raw = localStorage.getItem("relance.noel.v1");
    if (raw) return { ...base, ...JSON.parse(raw) };
  } catch { /* stockage indisponible : on joue sans sauvegarde */ }
  return base;
}
function store(s: Saved) {
  try { localStorage.setItem("relance.noel.v1", JSON.stringify(s)); } catch { /* ignoré */ }
}

export interface GameHandle { stop: () => void }

export function startGame(host: HTMLElement, opts: { onExit: () => void }): GameHandle {
  const canvas = document.createElement("canvas");
  canvas.setAttribute("aria-label", "Jeu : La grande tournée du Père Noël");
  canvas.style.cssText = "position:absolute;inset:0;width:100%;height:100%;display:block;touch-action:none;";
  host.appendChild(canvas);
  const ctx = canvas.getContext("2d", { alpha: false })!;

  const saved = loadSaved();
  const sound = new Sound(saved.music);
  const controls = new Controls(host, () => sound.unlock());
  const coarse = window.matchMedia?.("(pointer: coarse)").matches ?? false;

  // ------------------------------------------------------------------ état
  let screen: Screen = "title";
  let levelIndex = 0;
  let world = new World(LEVELS[0]);
  let lives = 3;
  let totalBells = 0;
  let bellsAtLevelStart = 0;
  let camX = 0, camY = 0;
  let trauma = 0;
  let hitStop = 0;
  let flash = 0, flashColor = "255,255,255";
  let introT = 0;
  let time = 0;
  let particles: Particle[] = [];
  let pom = 0, pomV = 0;
  let blinkT = 3;
  let titleScroll = 0;
  let menuFocus = 0;
  let uiButtons: UiButton[] = [];
  let hudButtons: UiButton[] = [];
  let celebrateT = 0;

  // ------------------------------------------------------------------ dimensions
  let cssW = 0, cssH = 0, dpr = 1, viewScale = 1, S = 1, viewW = 0, viewH = 0;
  let bg: { theme: Theme; S: number; layers: BgLayer[]; aurora: HTMLCanvasElement | null } | null = null;
  let chunkCache = new Map<number, HTMLCanvasElement>();
  let chunkKey = "";
  let spriteCache = new Map<string, HTMLCanvasElement>();
  let vignette: HTMLCanvasElement | null = null;
  const stars = Array.from({ length: 110 }, (_, i) => ({ x: hash(i, 1), y: hash(i, 2) * 0.62, r: 0.6 + hash(i, 3) * 1.6, p: hash(i, 4) * 6.28 }));
  const flakes = Array.from({ length: 150 }, (_, i) => ({ x: hash(i, 11), y: hash(i, 12), z: 0.35 + hash(i, 13) * 1.1, p: hash(i, 14) * 6.28 }));

  function resize() {
    const r = host.getBoundingClientRect();
    cssW = Math.max(1, r.width); cssH = Math.max(1, r.height);
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    const maxPixels = 2.6e6;
    if (cssW * cssH * dpr * dpr > maxPixels) dpr = Math.max(1, Math.sqrt(maxPixels / (cssW * cssH)));
    canvas.width = Math.round(cssW * dpr);
    canvas.height = Math.round(cssH * dpr);
    viewScale = Math.min(cssH / LEVEL_H, cssW / 480);
    S = viewScale * dpr;
    viewW = cssW / viewScale;
    viewH = cssH / viewScale;
    bg = null; vignette = null;
    chunkCache = new Map(); spriteCache = new Map();
    layoutTouch();
  }

  function layoutTouch() {
    const R = Math.max(30, Math.min(48, Math.min(cssW, cssH) * 0.1));
    const m = 16 + (cssW > cssH ? 14 : 0);
    const cy = cssH - m - R;
    const lx = m + R, rx = m + R * 3.3;
    const mid = (lx + rx) / 2;
    const top = cy - R * 1.8;
    const btn = (id: TouchButton["id"], cx: number, cy2: number, r: number, x: number, y: number, w: number, h: number): TouchButton => ({ id, cx, cy: cy2, r, x, y, w, h });
    controls.touchButtons = [
      btn("left", lx, cy, R, 0, top, mid, cssH - top),
      btn("right", rx, cy, R, mid, top, rx + R * 1.4 - mid, cssH - top),
      btn("jump", cssW - m - R * 1.15, cy - R * 0.15, R * 1.2, 0, 0, 0, 0),
      btn("action", cssW - m - R * 3.6, cy + R * 0.25, R * 0.95, 0, 0, 0, 0),
    ];
  }

  const ro = new ResizeObserver(resize);
  ro.observe(host);
  resize();

  // ------------------------------------------------------------------ niveaux
  function startLevel(i: number, fresh: boolean) {
    levelIndex = i;
    if (fresh) bellsAtLevelStart = totalBells;
    world = new World(LEVELS[i]);
    bg = null; chunkCache = new Map();
    chunkKey = "";
    introT = 2.6;
    particles = [];
    camX = Math.max(0, world.player.x - viewW * 0.35);
    sound.setMood(i);
    screen = "play";
  }

  function newGame(from: number) {
    lives = 3;
    totalBells = 0;
    startLevel(from, true);
  }

  function respawn() {
    const at = world.respawnPoint();
    const levelBells = world.bells;
    if (!at) totalBells = bellsAtLevelStart; else totalBells = bellsAtLevelStart + Math.floor(levelBells * 0.5);
    const lit = world.checkpoints.map((c) => c.lit);
    world = new World(LEVELS[levelIndex], at);
    world.checkpoints.forEach((c, k) => (c.lit = lit[k]));
    chunkCache = new Map();
    camX = Math.max(0, world.player.x - viewW * 0.35);
  }

  // ------------------------------------------------------------------ effets (skill game-feel : un appel par palier)
  function feedback(e: GameEvent) {
    sound.play(e.type);
    const P = (n: number, f: (k: number) => Partial<Particle>) => {
      for (let k = 0; k < n; k++) particles.push({ x: e.x, y: e.y, vx: 0, vy: 0, life: 0, max: 0.6, size: 4, color: "#fff", g: 0, kind: "dot", ...f(k) });
    };
    switch (e.type) {
      case "jump":
        P(5, () => ({ vx: (Math.random() - 0.5) * 160, vy: -Math.random() * 60, size: 4 + Math.random() * 4, max: 0.4, color: "rgba(255,255,255,0.9)", g: 300 }));
        break;
      case "land":
        P(e.big ? 10 : 5, (k) => ({ vx: (k % 2 ? 1 : -1) * (60 + Math.random() * 140), vy: -40 - Math.random() * 80, size: 4 + Math.random() * 5, max: 0.45, color: "rgba(240,246,255,0.95)", g: 400 }));
        if (e.big) addTrauma(0.12);
        break;
      case "step":
        if (Math.random() < 0.6) P(1, () => ({ vx: -world.player.facing * 40, vy: -40, size: 3 + Math.random() * 3, max: 0.3, color: "rgba(255,255,255,0.8)", g: 200 }));
        break;
      case "bell": {
        P(8, (k) => { const a = (k / 8) * Math.PI * 2; return { vx: Math.cos(a) * 160, vy: Math.sin(a) * 160, kind: "spark", size: 6, max: 0.45, color: "255,220,110", add: true }; });
        P(1, () => ({ kind: "text", text: "+1", vy: -70, max: 0.7, size: 22, color: "#ffe9a0" }));
        totalBells++;
        if (totalBells % 100 === 0) { lives++; P(1, () => ({ kind: "text", text: "+1 vie !", vy: -60, max: 1.4, size: 28, color: "#ffffff" })); sound.play("heart"); }
        addTrauma(0.04);
        break;
      }
      case "stomp":
      case "hit":
        P(14, () => ({ vx: (Math.random() - 0.5) * 420, vy: -Math.random() * 360, size: 4 + Math.random() * 6, max: 0.6, color: "rgba(255,255,255,0.95)", g: 900 }));
        P(1, () => ({ kind: "ring", size: 10, max: 0.35, color: "255,255,255" }));
        addTrauma(0.32);
        hitStop = 3;
        break;
      case "bump":
        P(4, () => ({ vx: (Math.random() - 0.5) * 140, vy: -120 - Math.random() * 100, size: 3 + Math.random() * 3, max: 0.4, color: "rgba(255,255,255,0.9)", g: 800 }));
        addTrauma(0.12);
        break;
      case "spawnItem":
        P(10, () => ({ vx: (Math.random() - 0.5) * 200, vy: -Math.random() * 220, kind: "spark", size: 5, max: 0.6, color: "255,240,170", add: true, g: 300 }));
        break;
      case "power":
      case "heart":
        P(24, (k) => { const a = (k / 24) * Math.PI * 2; return { vx: Math.cos(a) * 260, vy: Math.sin(a) * 260, kind: "spark", size: 7, max: 0.7, color: e.type === "power" ? "255,235,120" : "255,140,160", add: true }; });
        P(1, () => ({ kind: "ring", size: 20, max: 0.5, color: "255,240,180" }));
        P(1, () => ({ kind: "text", text: e.type === "power" ? "Boules de neige !" : "+1 cœur", vy: -50, max: 1.3, size: 24, color: "#fffbe0" }));
        flash = 0.35; flashColor = "255,250,220";
        break;
      case "hurt":
        addTrauma(0.6); hitStop = 6; flash = 0.35; flashColor = "255,60,80";
        break;
      case "die":
        addTrauma(0.8); hitStop = 8; flash = 0.5; flashColor = "255,60,80";
        break;
      case "throw":
        P(4, () => ({ vx: world.player.facing * 120 + (Math.random() - 0.5) * 60, vy: -Math.random() * 60, size: 3, max: 0.3, color: "rgba(220,235,255,0.9)" }));
        break;
      case "checkpoint":
        P(30, () => ({ vx: (Math.random() - 0.5) * 320, vy: -Math.random() * 380, kind: "spark", size: 6, max: 0.9, color: "255,200,120", add: true, g: 400 }));
        P(1, () => ({ kind: "text", text: "Point de passage", vy: -40, max: 1.4, size: 24, color: "#fff2c4" }));
        break;
      case "clear":
        celebrateT = 0;
        break;
    }
  }

  function addTrauma(a: number) { trauma = Math.min(1, trauma + a); }

  function fireworks(x: number, y: number) {
    const hue = Math.floor(Math.random() * 360);
    for (let k = 0; k < 36; k++) {
      const a = (k / 36) * Math.PI * 2, sp = 200 + Math.random() * 140;
      particles.push({ x, y, vx: Math.cos(a) * sp, vy: Math.sin(a) * sp, life: 0, max: 1.1, size: 5, color: `hsl(${hue + Math.random() * 40},100%,70%)`, g: 220, kind: "confetti", rot: Math.random() * 6 });
    }
    particles.push({ x, y, vx: 0, vy: 0, life: 0, max: 0.4, size: 30, color: "255,240,200", g: 0, kind: "ring" });
    sound.play("ui");
  }

  // ------------------------------------------------------------------ boucle
  let raf = 0;
  let last = performance.now();
  let acc = 0;
  let stopped = false;

  function frame(now: number) {
    if (stopped) return;
    raf = requestAnimationFrame(frame);
    const real = Math.min(0.1, (now - last) / 1000);
    last = now;
    time += real;
    handleUi();
    if (screen === "play") {
      acc += real;
      while (acc >= DT) {
        acc -= DT;
        if (hitStop > 0) { hitStop--; continue; }
        const input = controls.read();
        world.step(input);
        for (const e of world.events) feedback(e);
        world.events.length = 0;
        afterTick();
      }
    } else {
      acc = 0;
      if (screen === "title") titleScroll += real * 60;
    }
    updateEffects(real);
    render();
  }

  function afterTick() {
    if (world.status === "dead" && world.statusT > 110) {
      lives--;
      if (lives < 0) { screen = "over"; menuFocus = 0; return; }
      respawn();
    }
    if (world.status === "clear") {
      celebrateT++;
      const lvl = LEVELS[levelIndex];
      if (celebrateT % 22 === 0 && celebrateT < 200) fireworks(camX + viewW * (0.25 + Math.random() * 0.5), 120 + Math.random() * 220);
      if (celebrateT === 1) sound.play("clear");
      if (celebrateT > 230) {
        saved.unlocked = Math.max(saved.unlocked, Math.min(LEVELS.length - 1, levelIndex + 1));
        saved.best = Math.max(saved.best, totalBells);
        store(saved);
        if (lvl.finale || levelIndex >= LEVELS.length - 1) { screen = "win"; menuFocus = 0; }
        else startLevel(levelIndex + 1, true);
      }
    }
  }

  function updateEffects(dt: number) {
    trauma = Math.max(0, trauma - dt * 1.4);
    flash = Math.max(0, flash - dt * 2);
    introT = Math.max(0, introT - dt);
    // pompon du bonnet : ressort amorti (mouvement secondaire)
    const p = world.player;
    const target = Math.max(-1, Math.min(1.3, -p.vx * p.facing * 0.0016 + p.vy * 0.0006 + 0.25));
    pomV += (target - pom) * 60 * dt - pomV * 7 * dt;
    pom += pomV * dt * 4;
    blinkT -= dt;
    if (blinkT < -0.12) blinkT = 2 + Math.random() * 3;
    for (const q of particles) {
      q.life += dt;
      q.vy += q.g * dt;
      q.x += q.vx * dt; q.y += q.vy * dt;
      if (q.kind === "spark" || q.kind === "confetti") { q.vx *= 1 - dt * 2.5; q.vy *= q.kind === "spark" ? 1 - dt * 2.5 : 1; }
    }
    particles = particles.filter((q) => q.life < q.max);
    if (screen === "play") {
      // caméra : avance vers l'avant du regard, lissée (skill camera-systems)
      const look = p.facing * Math.min(viewW * 0.14, 180);
      let target2 = p.x + p.w / 2 - viewW * 0.42 + look;
      if (world.status === "clear") target2 = world.goal.x - viewW * 0.5 + 60;
      target2 = Math.max(0, Math.min(world.widthPx - viewW, target2));
      camX += (target2 - camX) * Math.min(1, dt * 5);
    }
    camY = viewH > LEVEL_H ? -(viewH - LEVEL_H) * 0.62 : 0;
  }

  // ------------------------------------------------------------------ interface
  function tapIn(b: { x: number; y: number; w: number; h: number }, t: { x: number; y: number }) {
    return t.x >= b.x && t.x <= b.x + b.w && t.y >= b.y && t.y <= b.y + b.h;
  }

  function handleUi() {
    const taps = controls.takeTaps();
    const keys = controls.takeKeys();
    for (const t of taps) {
      const hit = [...hudButtons, ...uiButtons].find((b) => tapIn(b, t));
      if (hit) { sound.play("ui"); hit.action(); return; }
    }
    if (screen === "play" && controls.pausePressed(keys)) { pause(); return; }
    if (screen !== "play") {
      if (keys.some((k) => k === "ArrowDown" || k === "ArrowRight")) menuFocus = (menuFocus + 1) % Math.max(1, uiButtons.length);
      if (keys.some((k) => k === "ArrowUp" || k === "ArrowLeft")) menuFocus = (menuFocus - 1 + uiButtons.length) % Math.max(1, uiButtons.length);
      if (keys.some((k) => k === "Enter" || k === "Space" || k === "NumpadEnter") && uiButtons[menuFocus]) { sound.play("ui"); uiButtons[menuFocus].action(); }
      if (screen === "pause" && keys.includes("Escape")) resume();
    }
  }

  function goTitle() {
    screen = "title";
    menuFocus = 0;
    levelIndex = 0;
    world = new World(LEVELS[0]);
    particles = [];
    sound.setMood(0);
  }

  function pause() { if (screen === "play") { screen = "pause"; menuFocus = 0; } }
  function resume() { screen = "play"; last = performance.now(); }

  function toggleMusic() { saved.music = !saved.music; sound.setMusic(saved.music); store(saved); }
  function toggleShake() { saved.shake = !saved.shake; store(saved); }
  function toggleFullscreen() {
    const doc = document as Document & { webkitFullscreenElement?: Element; webkitExitFullscreen?: () => void };
    const el = host as HTMLElement & { webkitRequestFullscreen?: () => void };
    if (document.fullscreenElement || doc.webkitFullscreenElement) { (document.exitFullscreen?.() ?? doc.webkitExitFullscreen?.()); return; }
    const p = el.requestFullscreen?.() ?? el.webkitRequestFullscreen?.();
    const orientation = window.screen.orientation as unknown as { lock?: (o: string) => Promise<void> } | undefined;
    Promise.resolve(p).then(() => orientation?.lock?.("landscape")).catch(() => {});
  }
  const canFullscreen = !!(document.documentElement.requestFullscreen || (document.documentElement as unknown as { webkitRequestFullscreen?: unknown }).webkitRequestFullscreen);

  const onVisibility = () => { if (document.hidden) { pause(); sound.suspend(); } else sound.resume(); };
  document.addEventListener("visibilitychange", onVisibility);

  // ------------------------------------------------------------------ rendu
  function render() {
    const theme: Theme = screen === "title" ? "village" : LEVELS[levelIndex].theme;
    if (!bg || bg.theme !== theme || bg.S !== S) bg = { theme, S, layers: buildBackground(theme, S, camY), aurora: theme === "forest" ? buildAurora() : null };
    const cx = screen === "title" ? (titleScroll % (world.widthPx - viewW)) : camX;
    if (screen === "title") camY = viewH > LEVEL_H ? -(viewH - LEVEL_H) * 0.62 : 0;

    // secousse (trauma²), en temps réel
    let shx = 0, shy = 0;
    if (saved.shake && trauma > 0) {
      const k = trauma * trauma;
      shx = Math.sin(time * 71) * 14 * k;
      shy = Math.cos(time * 53) * 10 * k;
    }
    const camXr = cx + shx, camYr = camY + shy;

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = skyGradient(ctx, theme, canvas.height);
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    drawSky(theme, camXr);
    ctx.setTransform(1, 0, 0, 1, 0, 0);

    // couches de parallaxe
    for (const L of bg.layers) {
      const wpx = L.worldW * S;
      let ox = -((camXr * L.factor * S) % wpx);
      if (ox > 0) ox -= wpx;
      const oy = Math.round((L.yWorld - camYr * 0.5) * S);
      for (let x = ox; x < canvas.width; x += wpx - 1) ctx.drawImage(L.canvas, Math.round(x), oy);
    }
    // brume au pied des montagnes
    const pal = PALETTES[theme];
    const fogTop = (380 - camYr) * S, fogBot = (720 - camYr) * S;
    const fg = ctx.createLinearGradient(0, fogTop, 0, fogBot);
    fg.addColorStop(0, "rgba(0,0,0,0)"); fg.addColorStop(0.6, pal.fog); fg.addColorStop(1, pal.fog);
    ctx.fillStyle = fg; ctx.fillRect(0, fogTop, canvas.width, canvas.height - fogTop);

    // ---------- monde
    const tx = Math.round(-camXr * S), ty = Math.round(-camYr * S);
    ctx.setTransform(1, 0, 0, 1, tx, ty);
    ctx.scale(S, S);

    // gouffre sous le niveau (portrait)
    const abyss = ctx.createLinearGradient(0, LEVEL_H - 140, 0, LEVEL_H + 200);
    abyss.addColorStop(0, "rgba(5,8,25,0)"); abyss.addColorStop(1, "rgba(5,8,25,0.95)");
    ctx.fillStyle = abyss;
    ctx.fillRect(camXr - 10, LEVEL_H - 140, viewW + 20, viewH + 400);

    const x0 = camXr - 300, x1 = camXr + viewW + 300;
    const lvl = screen === "title" ? LEVELS[0] : LEVELS[levelIndex];
    drawDecor(lvl.decor, x0, x1, pal, theme);
    drawChunks(camXr, theme);

    // cadeaux (dynamiques)
    const c0 = Math.max(0, Math.floor(camXr / TILE) - 1), c1 = Math.min(world.width - 1, Math.ceil((camXr + viewW) / TILE) + 1);
    for (let y = 0; y < ROWS; y++) for (let x = c0; x <= c1; x++) {
      const t = world.grid[y][x];
      if (t === "?" || t === "!" || t === "+" || t === "u") {
        const bt = world.bumps.get(`${x},${y}`);
        const age = bt === undefined ? 99 : world.tick - bt;
        const bump = age < 10 ? Math.sin((age / 10) * Math.PI) * 12 : 0;
        drawGiftBlock(ctx, x, y, t, time, bump);
      }
    }

    for (const c of world.checkpoints) if (c.x > x0 && c.x < x1) drawLantern(ctx, c.x, c.y, c.lit, time);

    const finale = lvl.finale;
    const clearing = world.status === "clear" && screen !== "title";
    const sleighX = world.goal.x + 90;
    let sleighDX = 0, sleighDY = 0;
    if (clearing && !finale && celebrateT > 75) { const k = (celebrateT - 75) / 60; sleighDX = k * k * 260; sleighDY = -k * k * 200; }
    if (!finale && world.goal.x > x0 && world.goal.x < x1 + 400) drawSleigh(ctx, sleighX + sleighDX, world.goal.y + sleighDY, time);
    if (finale && !clearing) drawBigChimney(ctx, world.goal.x + 60, world.goal.y, time);

    for (const it of world.items) if (it.alive && it.x > x0 && it.x < x1) drawItem(ctx, it, time);
    for (const e of world.enemies) {
      if (e.x < x0 || e.x > x1) continue;
      if (!e.alive && e.deadT > 60) continue;
      drawEnemy(ctx, e, time);
    }
    for (const s of world.snowballs) drawSnowball(ctx, s);

    if (screen !== "title") {
      const p = world.player;
      if (clearing) {
        // le Père Noël saute dans le traîneau, ou dans la cheminée au dernier niveau
        const k = Math.min(1, celebrateT / 45);
        const fromX = p.x, fromY = p.y;
        const toX = finale ? world.goal.x + 60 - p.w / 2 : sleighX - 30 - p.w / 2;
        const toY = finale ? world.goal.y - 200 - p.h : world.goal.y - 62 - p.h;
        const arc = Math.sin(k * Math.PI) * 120;
        const sx = fromX + (toX - fromX) * k, sy = fromY + (toY - fromY) * k - arc;
        let dive = 0;
        if (finale && celebrateT > 50) dive = Math.min(120, (celebrateT - 50) * 3);
        const ghost = { ...p, x: sx + sleighDX * (k >= 1 ? 1 : 0), y: sy + sleighDY * (k >= 1 ? 1 : 0) + dive, onGround: k >= 1, vx: 0, vy: 0, facing: 1, invuln: 0, landSquash: 0 };
        if (dive < 110) drawSanta(ctx, ghost, time, { pom, blink: false });
        if (finale) drawBigChimney(ctx, world.goal.x + 60, world.goal.y, time);
      } else if (!(world.status === "dead" && p.y > LEVEL_H + 300)) {
        drawSanta(ctx, p, time, { pom, blink: blinkT < 0 });
        if (p.power) glow(ctx, p.x + p.w / 2, p.y + p.h / 2, 70, "rgba(170,220,255,0.5)", 0.35 + 0.1 * Math.sin(time * 6));
      }
    }

    drawParticles();

    // ---------- écran
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    drawSnowfall(theme);
    if (!vignette) vignette = buildVignette();
    ctx.drawImage(vignette, 0, 0);
    if (flash > 0) { ctx.fillStyle = `rgba(${flashColor},${flash * 0.5})`; ctx.fillRect(0, 0, canvas.width, canvas.height); }

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    uiButtons = []; hudButtons = [];
    if (screen === "title") drawTitle();
    else {
      drawHud();
      if (screen === "play") drawTouch();
      if (screen === "play" && introT > 0) drawIntro();
      if (screen === "pause") drawPause();
      if (screen === "over") drawOver();
      if (screen === "win") drawWin();
    }
  }

  function buildAurora() {
    const cv = document.createElement("canvas");
    const w = 1600, h = 420;
    cv.width = Math.ceil(w * S * 0.5); cv.height = Math.ceil(h * S * 0.5);
    const c = cv.getContext("2d")!;
    c.scale(S * 0.5, S * 0.5);
    c.globalCompositeOperation = "lighter";
    const bands: [number, number, string, string][] = [[120, 0.004, "rgba(80,255,170,", "rgba(60,180,255,"], [180, 0.0055, "rgba(140,120,255,", "rgba(80,255,200,"]];
    for (const [base, f, a, b] of bands) {
      for (let x = 0; x < w; x += 3) {
        const y = base + Math.sin(x * f) * 50 + Math.sin(x * f * 2.7 + 1) * 22;
        const hh = 120 + Math.sin(x * f * 1.7) * 60;
        const g = c.createLinearGradient(0, y - hh, 0, y + 20);
        const al = 0.16 + 0.2 * Math.sin(x * 0.013) ** 2;
        g.addColorStop(0, a + "0)"); g.addColorStop(0.7, b + al + ")"); g.addColorStop(1, a + "0)");
        c.fillStyle = g;
        c.fillRect(x, y - hh, 4, hh + 20);
      }
    }
    return cv;
  }

  function drawSky(theme: Theme, camXr: number) {
    ctx.setTransform(S, 0, 0, S, 0, 0);
    const skyH = viewH * 0.75;
    for (const s of stars) {
      const a = theme === "village" ? 0.5 * (1 - s.y * 1.4) : 0.9;
      const tw = 0.55 + 0.45 * Math.sin(time * 2 + s.p);
      if (a <= 0) continue;
      ctx.fillStyle = `rgba(255,255,240,${a * tw})`;
      const x = ((s.x * viewW * 1.3 - camXr * 0.02) % (viewW * 1.3) + viewW * 1.3) % (viewW * 1.3);
      ctx.beginPath(); ctx.arc(x, s.y * skyH, s.r, 0, Math.PI * 2); ctx.fill();
    }
    if (theme === "forest" && bg?.aurora) {
      ctx.globalAlpha = 0.75 + 0.25 * Math.sin(time * 0.6);
      const w = 1600;
      const ox = -((camXr * 0.05 + time * 8) % w);
      ctx.drawImage(bg.aurora, ox, 0, w, 420);
      ctx.drawImage(bg.aurora, ox + w, 0, w, 420);
      ctx.globalAlpha = 1;
    }
    // astre
    const mx = viewW * (theme === "village" ? 0.78 : 0.72), my = theme === "village" ? 120 : 110;
    const r = theme === "city" ? 62 : 40;
    const halo = ctx.createRadialGradient(mx, my, r * 0.6, mx, my, r * 5);
    halo.addColorStop(0, theme === "village" ? "rgba(255,230,200,0.4)" : "rgba(220,230,255,0.35)");
    halo.addColorStop(1, "rgba(0,0,0,0)");
    ctx.fillStyle = halo; ctx.fillRect(mx - r * 5, my - r * 5, r * 10, r * 10);
    const mg = ctx.createRadialGradient(mx - r * 0.3, my - r * 0.3, r * 0.1, mx, my, r);
    mg.addColorStop(0, "#fffef4"); mg.addColorStop(1, theme === "village" ? "#ffe0b8" : "#dfe4ff");
    if (theme !== "village") { ctx.fillStyle = mg; ctx.beginPath(); ctx.arc(mx, my, r, 0, Math.PI * 2); ctx.fill(); }
    if (theme === "village") {
      ctx.drawImage(crescent(r, mg), mx - r - 2, my - r - 2, r * 2 + 4, r * 2 + 4);
    } else {
      ctx.fillStyle = "rgba(160,170,210,0.25)";
      for (const [dx, dy, rr2] of [[-0.3, -0.1, 0.18], [0.25, 0.3, 0.12], [0.1, -0.35, 0.09]]) { ctx.beginPath(); ctx.arc(mx + dx * r, my + dy * r, rr2 * r, 0, Math.PI * 2); ctx.fill(); }
    }
    // traîneau qui passe devant la lune, de temps en temps
    const cyc = (time % 24) / 24;
    if (cyc < 0.25) {
      const k = cyc / 0.25;
      const sx = -100 + k * (viewW + 200), sy = my + 40 - Math.sin(k * Math.PI) * 60;
      ctx.fillStyle = "rgba(20,20,45,0.85)";
      for (let i = 0; i < 4; i++) {
        const rx = sx + 30 + i * 22, ry = sy - 4 + Math.sin(time * 10 + i) * 2;
        ctx.beginPath(); ctx.ellipse(rx, ry, 8, 4, 0, 0, Math.PI * 2); ctx.fill();
        ctx.fillRect(rx - 6, ry, 2, 7); ctx.fillRect(rx + 4, ry, 2, 7);
        ctx.fillRect(rx + 6, ry - 7, 2, 5);
      }
      ctx.beginPath(); ctx.moveTo(sx - 10, sy - 8); ctx.lineTo(sx + 18, sy - 4); ctx.lineTo(sx + 14, sy + 4); ctx.lineTo(sx - 8, sy + 4); ctx.fill();
      ctx.fillStyle = "rgba(255,240,170,0.7)";
      for (let i = 1; i < 8; i++) { ctx.beginPath(); ctx.arc(sx - i * 12, sy + Math.sin(time * 4 + i) * 3, 1.8 - i * 0.15, 0, Math.PI * 2); ctx.fill(); }
    }
  }

  let crescentCv: HTMLCanvasElement | null = null;
  function crescent(r: number, _g: CanvasGradient) {
    if (crescentCv && crescentCv.width === Math.ceil((r * 2 + 4) * S)) return crescentCv;
    const cv = document.createElement("canvas");
    cv.width = cv.height = Math.ceil((r * 2 + 4) * S);
    const c = cv.getContext("2d")!;
    c.scale(S, S);
    const m = r + 2;
    const g = c.createRadialGradient(m - r * 0.3, m - r * 0.3, r * 0.1, m, m, r);
    g.addColorStop(0, "#fffef4"); g.addColorStop(1, "#ffd9a8");
    c.fillStyle = g; c.beginPath(); c.arc(m, m, r, 0, Math.PI * 2); c.fill();
    c.globalCompositeOperation = "destination-out";
    c.beginPath(); c.arc(m + r * 0.45, m - r * 0.25, r * 0.88, 0, Math.PI * 2); c.fill();
    crescentCv = cv;
    return cv;
  }

  function sprite(key: string, w: number, h: number, draw: (c: CanvasRenderingContext2D) => void) {
    let cv = spriteCache.get(key);
    if (!cv) {
      cv = document.createElement("canvas");
      cv.width = Math.ceil(w * S); cv.height = Math.ceil(h * S);
      const c = cv.getContext("2d")!;
      c.scale(S, S);
      draw(c);
      spriteCache.set(key, cv);
    }
    return cv;
  }

  function drawDecor(list: Decor[], x0: number, x1: number, pal: (typeof PALETTES)[Theme], theme: Theme) {
    for (const d of list) {
      const box = decorBox(d.kind);
      const fx = d.x * TILE + TILE / 2, fy = d.y * TILE + 4;
      if (fx + box.w < x0 || fx - box.w > x1) continue;
      const cv = sprite(`${theme}-${d.kind}-${d.seed}`, box.w, box.h + 10, (c) => { c.translate(box.w / 2, box.h); drawDecorShape(c, d, pal); });
      ctx.save();
      ctx.translate(fx, fy);
      if (d.flip) ctx.scale(-1, 1);
      ctx.drawImage(cv, -box.w / 2, -box.h, box.w, box.h + 10);
      ctx.restore();
      for (const L of decorLights(d.kind)) glow(ctx, fx + (d.flip ? -L.x : L.x), fy + L.y, L.r * (0.92 + 0.08 * Math.sin(time * 3 + d.seed * 10)), L.color);
    }
  }

  function drawChunks(camXr: number, theme: Theme) {
    const extra = Math.max(0, Math.ceil((camY + viewH - LEVEL_H) / TILE) + 1);
    const key = `${theme}-${S}-${extra}-${levelIndex}-${screen === "title"}`;
    if (key !== chunkKey) { chunkCache = new Map(); chunkKey = key; }
    const first = Math.floor(camXr / (CHUNK * TILE)) - 1, lastC = Math.floor((camXr + viewW) / (CHUNK * TILE)) + 1;
    const pad = 12; // place pour la neige qui déborde au-dessus du sol
    for (let k = Math.max(0, first); k <= lastC; k++) {
      if (k * CHUNK >= world.width) break;
      let cv = chunkCache.get(k);
      if (!cv) {
        cv = document.createElement("canvas");
        const wW = CHUNK * TILE, hW = (ROWS + extra) * TILE + pad;
        cv.width = Math.ceil(wW * S); cv.height = Math.ceil(hW * S);
        const c = cv.getContext("2d")!;
        c.scale(S, S);
        c.translate(-k * CHUNK * TILE, pad);
        c.beginPath(); c.rect(k * CHUNK * TILE, -pad, wW, hW); c.clip();
        drawTerrain(c, world.grid, world.width, k * CHUNK - 1, k * CHUNK + CHUNK, theme, extra);
        chunkCache.set(k, cv);
        if (chunkCache.size > 24) chunkCache.delete(chunkCache.keys().next().value!);
      }
      ctx.drawImage(cv, k * CHUNK * TILE, -pad, cv.width / S, cv.height / S);
    }
  }

  function drawParticles() {
    for (const q of particles) {
      const k = q.life / q.max;
      if (q.kind === "dot") {
        ctx.globalAlpha = 1 - k;
        ctx.fillStyle = q.color;
        ctx.beginPath(); ctx.arc(q.x, q.y, q.size * (1 - k * 0.5), 0, Math.PI * 2); ctx.fill();
      } else if (q.kind === "spark") {
        glow(ctx, q.x, q.y, q.size * 3.5, `rgba(${q.color},0.9)`, 1 - k);
        ctx.globalAlpha = 1 - k;
        ctx.fillStyle = "#fff";
        ctx.beginPath(); ctx.arc(q.x, q.y, q.size * 0.4, 0, Math.PI * 2); ctx.fill();
      } else if (q.kind === "ring") {
        ctx.globalAlpha = 1 - k;
        ctx.strokeStyle = `rgba(${q.color},1)`; ctx.lineWidth = 4 * (1 - k) + 1;
        ctx.beginPath(); ctx.arc(q.x, q.y, q.size + k * 70, 0, Math.PI * 2); ctx.stroke();
      } else if (q.kind === "confetti") {
        ctx.globalAlpha = 1 - k * k;
        ctx.fillStyle = q.color;
        ctx.save(); ctx.translate(q.x, q.y); ctx.rotate((q.rot ?? 0) + q.life * 8);
        ctx.fillRect(-q.size / 2, -q.size / 4, q.size, q.size / 2);
        ctx.restore();
        glow(ctx, q.x, q.y, 12, "rgba(255,240,200,0.5)", (1 - k) * 0.5);
      } else if (q.kind === "text") {
        ctx.globalAlpha = Math.min(1, (1 - k) * 2);
        ctx.font = `700 ${q.size}px ${FONT}`;
        ctx.textAlign = "center";
        ctx.lineWidth = 5; ctx.strokeStyle = "rgba(30,20,60,0.7)";
        ctx.strokeText(q.text ?? "", q.x, q.y - 30);
        ctx.fillStyle = q.color; ctx.fillText(q.text ?? "", q.x, q.y - 30);
      }
      ctx.globalAlpha = 1;
    }
  }

  function drawSnowfall(theme: Theme) {
    const W = canvas.width, H = canvas.height;
    const wind = Math.sin(time * 0.3) * 30 + (theme === "forest" ? 10 : -20);
    for (const f of flakes) {
      const z = f.z;
      const y = ((f.y * H + time * (24 + 48 * z) * S) % (H + 20)) - 10;
      const drift = Math.sin(time * (0.8 + z) + f.p) * 18 * z * S;
      const x = (((f.x * W + time * wind * z * S - camX * S * 0.4 * z) % (W + 20)) + W + 20) % (W + 20) - 10 + drift;
      const r = (0.9 + z * 2.3) * S * 0.9;
      ctx.globalAlpha = z > 1.15 ? 0.45 : 0.85;
      ctx.fillStyle = "#ffffff";
      ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  function buildVignette() {
    const cv = document.createElement("canvas");
    cv.width = canvas.width; cv.height = canvas.height;
    const c = cv.getContext("2d")!;
    const g = c.createRadialGradient(cv.width / 2, cv.height * 0.45, Math.min(cv.width, cv.height) * 0.35, cv.width / 2, cv.height / 2, Math.max(cv.width, cv.height) * 0.75);
    g.addColorStop(0, "rgba(0,0,0,0)"); g.addColorStop(1, "rgba(5,5,30,0.45)");
    c.fillStyle = g; c.fillRect(0, 0, cv.width, cv.height);
    return cv;
  }

  // ------------------------------------------------------------------ interface (en px CSS)
  function panel(x: number, y: number, w: number, h: number, r = 18, alpha = 0.55) {
    ctx.fillStyle = `rgba(18,16,48,${alpha})`;
    ctx.beginPath(); ctx.roundRect(x, y, w, h, r); ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,0.18)"; ctx.lineWidth = 1.5; ctx.stroke();
  }

  function text(s: string, x: number, y: number, size: number, color = "#fff", align: CanvasTextAlign = "left", weight = 700, stroke = 0) {
    ctx.font = `${weight} ${size}px ${FONT}`;
    ctx.textAlign = align;
    ctx.textBaseline = "middle";
    if (stroke) { ctx.lineJoin = "round"; ctx.lineWidth = stroke; ctx.strokeStyle = "rgba(25,15,55,0.85)"; ctx.strokeText(s, x, y); }
    ctx.fillStyle = color;
    ctx.fillText(s, x, y);
  }

  function iconButton(x: number, y: number, size: number, icon: string, action: () => void) {
    const b: UiButton = { x, y, w: size, h: size, label: icon, action, icon };
    hudButtons.push(b);
    panel(x, y, size, size, size / 2, 0.5);
    const c = x + size / 2, m = y + size / 2, s = size * 0.22;
    ctx.fillStyle = "#fff"; ctx.strokeStyle = "#fff"; ctx.lineWidth = 3; ctx.lineCap = "round";
    if (icon === "pause") { ctx.fillRect(c - s * 0.8, m - s, s * 0.55, s * 2); ctx.fillRect(c + s * 0.25, m - s, s * 0.55, s * 2); }
    else if (icon === "music" || icon === "musicOff") {
      ctx.beginPath(); ctx.ellipse(c - s * 0.5, m + s * 0.7, s * 0.45, s * 0.35, -0.3, 0, Math.PI * 2); ctx.fill();
      ctx.fillRect(c - s * 0.1, m - s * 1.1, 3, s * 1.8);
      ctx.beginPath(); ctx.moveTo(c - s * 0.1, m - s * 1.1); ctx.lineTo(c + s * 0.9, m - s * 0.6); ctx.stroke();
      if (icon === "musicOff") { ctx.strokeStyle = "#ff6b7a"; ctx.beginPath(); ctx.moveTo(c - s * 1.2, m - s * 1.2); ctx.lineTo(c + s * 1.2, m + s * 1.2); ctx.stroke(); }
    } else if (icon === "full") {
      for (const [dx, dy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
        ctx.beginPath(); ctx.moveTo(c + dx * s, m + dy * s * 0.3); ctx.lineTo(c + dx * s, m + dy * s); ctx.lineTo(c + dx * s * 0.3, m + dy * s); ctx.stroke();
      }
    } else if (icon === "close") {
      ctx.beginPath(); ctx.moveTo(c - s, m - s); ctx.lineTo(c + s, m + s); ctx.moveTo(c + s, m - s); ctx.lineTo(c - s, m + s); ctx.stroke();
    }
  }

  function topRightButtons(withPause: boolean) {
    const size = cssW < 500 ? 40 : 46, gap = 10;
    let x = cssW - 14 - size;
    const y = 12;
    iconButton(x, y, size, "close", () => opts.onExit()); x -= size + gap;
    if (canFullscreen && cssW >= 430) { iconButton(x, y, size, "full", toggleFullscreen); x -= size + gap; }
    iconButton(x, y, size, saved.music ? "music" : "musicOff", toggleMusic); x -= size + gap;
    if (withPause) iconButton(x, y, size, "pause", pause);
  }

  function drawHud() {
    const small = cssW < 560;
    const h = small ? 42 : 50;
    panel(12, 12, small ? 200 : 260, h, h / 2);
    const s = small ? 0.75 : 0.9;
    ctx.save(); ctx.translate(36, 12 + h / 2); ctx.scale(s, s); drawBell(ctx, 0, 0, time * 0.5); ctx.restore();
    text(`${totalBells}`, 56, 12 + h / 2 + 1, small ? 20 : 24, "#ffe9a0", "left", 700);
    let hx = small ? 104 : 124;
    for (let i = 0; i < MAX_HEARTS; i++) {
      const full = i < world.player.hearts;
      const g = ctx.createLinearGradient(0, 12, 0, 12 + h);
      g.addColorStop(0, full ? "#ff8a9a" : "rgba(255,255,255,0.18)"); g.addColorStop(1, full ? "#e0203f" : "rgba(255,255,255,0.12)");
      heart(ctx, hx, 12 + h / 2 + 1, small ? 9 : 11, g);
      hx += small ? 22 : 28;
    }
    text(`×${Math.max(0, lives)}`, hx + 4, 12 + h / 2 + 1, small ? 16 : 19, "#fff", "left", 600);
    // nom du niveau
    if (!small) text(`${LEVELS[levelIndex].subtitle.split(" · ")[0]}`, 12 + 260 + 14, 12 + h / 2 + 1, 15, "rgba(255,255,255,0.75)", "left", 600, 4);
    topRightButtons(screen === "play");
  }

  function drawTouch() {
    if (!(coarse || controls.touchSeen)) return;
    const held = { left: controls.heldButton("left"), right: controls.heldButton("right"), jump: controls.heldButton("jump"), action: controls.heldButton("action") };
    for (const b of controls.touchButtons) {
      if (b.id === "action" && !world.player.power) continue;
      const on = held[b.id];
      ctx.fillStyle = on ? "rgba(255,255,255,0.38)" : "rgba(20,18,55,0.32)";
      ctx.beginPath(); ctx.arc(b.cx, b.cy, b.r, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = on ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.5)"; ctx.lineWidth = 2.5; ctx.stroke();
      ctx.fillStyle = "rgba(255,255,255,0.92)";
      const r = b.r * 0.38;
      if (b.id === "left" || b.id === "right") {
        const d = b.id === "left" ? -1 : 1;
        ctx.beginPath(); ctx.moveTo(b.cx + d * r, b.cy); ctx.lineTo(b.cx - d * r * 0.6, b.cy - r); ctx.lineTo(b.cx - d * r * 0.6, b.cy + r); ctx.fill();
      } else if (b.id === "jump") {
        ctx.beginPath(); ctx.moveTo(b.cx, b.cy - r * 1.1); ctx.lineTo(b.cx + r, b.cy + r * 0.4); ctx.lineTo(b.cx - r, b.cy + r * 0.4); ctx.fill();
        text("Saut", b.cx, b.cy + b.r * 0.62, Math.max(10, b.r * 0.26), "rgba(255,255,255,0.8)", "center", 600);
      } else {
        ctx.beginPath(); ctx.arc(b.cx, b.cy - 3, r * 0.8, 0, Math.PI * 2); ctx.fill();
        text("Lancer", b.cx, b.cy + b.r * 0.6, Math.max(10, b.r * 0.26), "rgba(255,255,255,0.8)", "center", 600);
      }
    }
  }

  function drawIntro() {
    const lvl = LEVELS[levelIndex];
    const a = Math.min(1, introT / 0.5, (2.6 - introT) / 0.3);
    ctx.globalAlpha = Math.max(0, a);
    const w = Math.min(cssW - 40, 460), h = 110;
    const x = (cssW - w) / 2, y = cssH * 0.28 - h / 2;
    panel(x, y, w, h, 24, 0.6);
    text(lvl.subtitle, cssW / 2, y + 32, 17, "#ffd9a0", "center", 600);
    text(lvl.name, cssW / 2, y + 70, Math.min(36, w / 13), "#fff", "center", 700, 6);
    ctx.globalAlpha = 1;
  }

  function menu(items: { label: string; action: () => void; primary?: boolean }[], top: number) {
    const w = Math.min(cssW - 48, 320), h = cssH < 420 ? 44 : 52, gap = 12;
    items.forEach((it, i) => {
      const x = (cssW - w) / 2, y = top + i * (h + gap);
      const b: UiButton = { x, y, w, h, label: it.label, action: it.action, primary: it.primary };
      uiButtons.push(b);
      const focused = i === menuFocus;
      const g = ctx.createLinearGradient(0, y, 0, y + h);
      if (it.primary) { g.addColorStop(0, "#ff6170"); g.addColorStop(1, "#c4142f"); }
      else { g.addColorStop(0, "rgba(255,255,255,0.22)"); g.addColorStop(1, "rgba(255,255,255,0.1)"); }
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.roundRect(x, y, w, h, h / 2); ctx.fill();
      ctx.strokeStyle = focused ? "#ffe9a0" : "rgba(255,255,255,0.35)"; ctx.lineWidth = focused ? 3 : 1.5; ctx.stroke();
      if (it.primary) { ctx.fillStyle = "rgba(255,255,255,0.25)"; ctx.beginPath(); ctx.roundRect(x + 10, y + 4, w - 20, h * 0.35, h / 4); ctx.fill(); }
      text(it.label, cssW / 2, y + h / 2 + 1, cssH < 420 ? 18 : 21, "#fff", "center", 700, it.primary ? 4 : 0);
    });
  }

  function titleText(main: string, sub: string, y: number) {
    const size = Math.min(64, cssW / 10.5, cssH / 7);
    ctx.save();
    ctx.shadowColor = "rgba(255,200,120,0.8)"; ctx.shadowBlur = 24;
    text(main, cssW / 2, y, size, "#fff8ec", "center", 700, Math.max(6, size / 7));
    ctx.restore();
    const g = ctx.createLinearGradient(0, y - size / 2, 0, y + size / 2);
    g.addColorStop(0, "#ffffff"); g.addColorStop(1, "#ffd6a0");
    ctx.font = `700 ${size}px ${FONT}`; ctx.fillStyle = g; ctx.textAlign = "center"; ctx.fillText(main, cssW / 2, y);
    text(sub, cssW / 2, y + size * 0.85, Math.max(15, size * 0.36), "#ffd9a0", "center", 600, 5);
  }

  function drawTitle() {
    const compact = cssH < 460;
    const ty = compact ? cssH * 0.18 : cssH * 0.22;
    titleText("La Grande Tournée", "du Père Noël", ty);
    // le Père Noël qui court sur l'écran titre
    const sz = Math.min(2.2, cssH / 260, cssW / 300);
    ctx.save();
    const runX = cssW * 0.5 + Math.sin(time * 0.6) * Math.min(160, cssW * 0.25);
    ctx.translate(runX, ty + (compact ? 70 : 120) * (sz / 1.6) + 40);
    ctx.scale(sz * 0.6, sz * 0.6);
    const fake = { ...world.player, x: -15, y: -56, vx: Math.cos(time * 0.6) >= 0 ? 336 : -336, vy: 0, onGround: true, facing: Math.cos(time * 0.6) >= 0 ? 1 : -1, runPhase: time * 11, invuln: 0, landSquash: 0, throwAnim: 0 };
    drawSanta(ctx, fake, time, { pom: 0.5 + Math.sin(time * 8) * 0.3, blink: blinkT < 0 });
    ctx.restore();
    const items: { label: string; action: () => void; primary?: boolean }[] = [];
    if (saved.unlocked > 0) items.push({ label: `Continuer · niveau ${saved.unlocked + 1}`, action: () => newGame(saved.unlocked), primary: true });
    items.push({ label: saved.unlocked > 0 ? "Nouvelle partie" : "Jouer", action: () => newGame(0), primary: saved.unlocked === 0 });
    const h = cssH < 420 ? 44 : 52;
    const top = Math.min(cssH - items.length * (h + 12) - 60, ty + (compact ? 110 : 190) * Math.max(0.8, sz / 1.6));
    menu(items, top);
    const hint = coarse ? "Flèches à gauche pour courir, bouton de droite pour sauter" : "← → pour courir · Espace pour sauter · X pour lancer";
    text(hint, cssW / 2, cssH - 26, Math.max(12, Math.min(15, cssW / 40)), "rgba(255,255,255,0.8)", "center", 500, 4);
    if (coarse && cssH > cssW) text("Tourne l'écran pour jouer en grand", cssW / 2, cssH - 50, 14, "#ffe9a0", "center", 600, 4);
    if (saved.best > 0) text(`Record : ${saved.best} clochettes`, 16, cssH - 26, 13, "rgba(255,255,255,0.7)", "left", 500, 3);
    topRightButtons(false);
  }

  function dim() { ctx.fillStyle = "rgba(10,8,35,0.55)"; ctx.fillRect(0, 0, cssW, cssH); }

  function drawPause() {
    dim();
    titleText("Pause", LEVELS[levelIndex].name, cssH * 0.2);
    menu([
      { label: "Reprendre", action: resume, primary: true },
      { label: "Recommencer le niveau", action: () => { totalBells = bellsAtLevelStart; startLevel(levelIndex, false); } },
      { label: `Secousses : ${saved.shake ? "oui" : "non"}`, action: toggleShake },
      { label: "Menu du jeu", action: goTitle },
    ], cssH * 0.2 + Math.min(110, cssH * 0.2));
    topRightButtons(false);
  }

  function drawOver() {
    dim();
    titleText("Oh non !", "Les lutins t'attendent pour réessayer", cssH * 0.25);
    menu([
      { label: "Réessayer le niveau", action: () => { lives = 3; totalBells = bellsAtLevelStart; startLevel(levelIndex, false); }, primary: true },
      { label: "Menu du jeu", action: goTitle },
    ], cssH * 0.25 + Math.min(120, cssH * 0.24));
    topRightButtons(false);
  }

  function drawWin() {
    dim();
    if (Math.random() < 0.05) fireworks(camX + Math.random() * viewW, 80 + Math.random() * 260);
    titleText("Joyeux Noël !", `Tous les cadeaux sont livrés · ${totalBells} clochettes`, cssH * 0.25);
    menu([
      { label: "Rejouer", action: () => newGame(0), primary: true },
      { label: "Retour au coach", action: () => opts.onExit() },
    ], cssH * 0.25 + Math.min(120, cssH * 0.24));
    topRightButtons(false);
  }

  raf = requestAnimationFrame(frame);
  Object.assign(window, { __noel: { get world() { return world; }, get screen() { return screen; }, start: (i: number) => newGame(i) } });

  return {
    stop() {
      stopped = true;
      cancelAnimationFrame(raf);
      ro.disconnect();
      controls.dispose();
      sound.dispose();
      document.removeEventListener("visibilitychange", onVisibility);
      canvas.remove();
      delete (window as unknown as { __noel?: unknown }).__noel;
    },
  };
}

