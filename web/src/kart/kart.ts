// « Le Grand Prix du Père Noël » : boucle principale, caméra, effets, affichage et menus.
// La course est simulée dans sim.ts (pas fixe 60 Hz) ; ici on ne fait que lire son état et le montrer.

import * as THREE from "three";
import { Controls, type TouchButton } from "../game/input";
import { KartSound } from "./audio";
import { LOOKS, buildTrackScene, makeIceMesh, makeItemBoxMesh, makeKart, makeSnowballMesh, type KartModel, type TrackScene } from "./scene";
import { DT, MAX_SPEED, Race, type ItemKind, type Kart, type KartInput, type RaceEvent } from "./sim";
import { buildTrack, TRACKS, type Track } from "./tracks";

const FONT = "Fredoka, 'Baloo 2', 'Trebuchet MS', system-ui, sans-serif";
type Screen = "menu" | "race" | "pause" | "results";

interface Saved { best: Record<string, number>; music: boolean }
function loadSaved(): Saved {
  const base: Saved = { best: {}, music: true };
  try { const raw = localStorage.getItem("relance.kart.v2"); if (raw) return { ...base, ...JSON.parse(raw) }; } catch { /* sans sauvegarde */ }
  return base;
}
function store(s: Saved) { try { localStorage.setItem("relance.kart.v2", JSON.stringify(s)); } catch { /* ignoré */ } }

const fmtTime = (t: number) => { const m = Math.floor(t / 60), s = t - m * 60; return `${m}:${s < 10 ? "0" : ""}${s.toFixed(2)}`; };
const ordinal = (n: number) => (n === 1 ? "1er" : `${n}e`);

interface UiButton { x: number; y: number; w: number; h: number; action: () => void }

export function startKart(host: HTMLElement, opts: { onExit: () => void }): { stop: () => void } {
  const glCanvas = document.createElement("canvas");
  const hud = document.createElement("canvas");
  for (const c of [glCanvas, hud]) { c.style.cssText = "position:absolute;inset:0;width:100%;height:100%;display:block;touch-action:none;"; host.appendChild(c); }
  hud.setAttribute("aria-label", "Jeu : Le Grand Prix du Père Noël");
  const ctx = hud.getContext("2d")!;

  const saved = loadSaved();
  const sound = new KartSound(saved.music);
  const controls = new Controls(host, () => sound.unlock());
  const coarse = window.matchMedia?.("(pointer: coarse)").matches ?? false;

  const renderer = new THREE.WebGLRenderer({ canvas: glCanvas, antialias: true, powerPreference: "high-performance" });
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(64, 1, 0.3, 1400);
  const hemi = new THREE.HemisphereLight(0xffffff, 0x8899bb, 1.5);
  const sun = new THREE.DirectionalLight(0xffffff, 2);
  sun.position.set(60, 120, 40);
  scene.add(hemi, sun);

  // ------------------------------------------------------------------ état
  let screen: Screen = "menu";
  let selected = 0;
  let trackIndex = -1;
  let track: Track = buildTrack(TRACKS[0]);
  const builtTracks = new Map<number, Track>();
  let trackScene: TrackScene | null = null;
  let race = new Race(track);
  let karts: { kart: Kart; model: KartModel; flame: THREE.Mesh; wheelAngle: number }[] = [];
  let boxMeshes: THREE.Mesh[] = [];
  const shotMeshes = new Map<number, THREE.Mesh>();
  const iceMeshes = new Map<number, THREE.Mesh>();
  const raceGroup = new THREE.Group();
  scene.add(raceGroup);
  let camH = 0, camFov = 64;
  let time = 0;
  let resultsT = 0;
  let banner = { text: "", t: 0 };
  let flash = 0;
  let menuFocus = 0;
  let uiButtons: UiButton[] = [];
  let cssW = 1, cssH = 1, dpr = 1;
  let stopped = false;
  let acc = 0;
  let pendingItem = false;

  // particules : étincelles de dérapage, gerbes de neige, étoiles
  const MAXP = 320;
  const pPos = new Float32Array(MAXP * 3).fill(-999), pCol = new Float32Array(MAXP * 3);
  const pVel = new Float32Array(MAXP * 3), pLife = new Float32Array(MAXP), pMax = new Float32Array(MAXP), pG = new Float32Array(MAXP);
  let pNext = 0;
  const pGeo = new THREE.BufferGeometry();
  pGeo.setAttribute("position", new THREE.BufferAttribute(pPos, 3));
  pGeo.setAttribute("color", new THREE.BufferAttribute(pCol, 3));
  // pastille ronde et douce, pour que les particules ne soient pas des carrés
  const dotCv = document.createElement("canvas");
  dotCv.width = dotCv.height = 32;
  const dctx = dotCv.getContext("2d")!;
  const dg = dctx.createRadialGradient(16, 16, 1, 16, 16, 16);
  dg.addColorStop(0, "rgba(255,255,255,1)"); dg.addColorStop(0.5, "rgba(255,255,255,0.9)"); dg.addColorStop(1, "rgba(255,255,255,0)");
  dctx.fillStyle = dg; dctx.fillRect(0, 0, 32, 32);
  const dotTex = new THREE.CanvasTexture(dotCv);
  const effects = new THREE.Points(pGeo, new THREE.PointsMaterial({ size: 0.7, map: dotTex, vertexColors: true, transparent: true, depthWrite: false, sizeAttenuation: true }));
  effects.frustumCulled = false;
  scene.add(effects);
  const col = new THREE.Color();
  function emit(x: number, y: number, z: number, vx: number, vy: number, vz: number, color: number, life: number, g = 9) {
    const i = pNext; pNext = (pNext + 1) % MAXP;
    pPos[i * 3] = x; pPos[i * 3 + 1] = y; pPos[i * 3 + 2] = z;
    pVel[i * 3] = vx; pVel[i * 3 + 1] = vy; pVel[i * 3 + 2] = vz;
    col.set(color); pCol[i * 3] = col.r; pCol[i * 3 + 1] = col.g; pCol[i * 3 + 2] = col.b;
    pLife[i] = life; pMax[i] = life; pG[i] = g;
  }

  // neige qui tombe autour de la caméra
  const SNOW = 700;
  const sPos = new Float32Array(SNOW * 3);
  for (let i = 0; i < SNOW; i++) { sPos[i * 3] = (Math.random() - 0.5) * 80; sPos[i * 3 + 1] = Math.random() * 34; sPos[i * 3 + 2] = (Math.random() - 0.5) * 80; }
  const sGeo = new THREE.BufferGeometry();
  sGeo.setAttribute("position", new THREE.BufferAttribute(sPos, 3));
  const snow = new THREE.Points(sGeo, new THREE.PointsMaterial({ size: 0.34, map: dotTex, color: 0xffffff, transparent: true, opacity: 0.9, depthWrite: false, fog: false }));
  snow.frustumCulled = false;
  scene.add(snow);

  // ------------------------------------------------------------------ dimensions
  function resize() {
    const r = host.getBoundingClientRect();
    cssW = Math.max(1, r.width); cssH = Math.max(1, r.height);
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    renderer.setPixelRatio(Math.min(dpr, cssW * cssH > 700000 ? 1.5 : 2));
    renderer.setSize(cssW, cssH, false);
    hud.width = Math.round(cssW * dpr); hud.height = Math.round(cssH * dpr);
    camera.aspect = cssW / cssH;
    camera.updateProjectionMatrix();
    layoutTouch();
  }

  function layoutTouch() {
    const R = Math.max(30, Math.min(48, Math.min(cssW, cssH) * 0.1));
    const m = 16 + (cssW > cssH ? 14 : 0);
    const cy = cssH - m - R;
    const btn = (id: string, cx: number, cy2: number, r: number): TouchButton => ({ id, cx, cy: cy2, r, x: 0, y: 0, w: 0, h: 0 });
    controls.touchButtons = [
      btn("item", cssW - m - R * 1.2, cy - R * 0.2, R * 1.2),
      btn("drift", cssW - m - R * 3.7, cy + R * 0.2, R * 0.95),
      btn("brake", cssW - m - R * 0.95, cy - R * 2.85, R * 0.8),
    ];
    const j = controls.joy;
    j.radius = Math.max(42, Math.min(64, Math.min(cssW, cssH) * 0.13));
    j.homeX = m + j.radius + 6; j.homeY = cssH - m - j.radius;
    j.zone = { x: 0, y: Math.min(cssH * 0.3, 90), w: cssW * 0.48, h: cssH - Math.min(cssH * 0.3, 90) };
    controls.releaseJoy();
  }

  const ro = new ResizeObserver(resize);
  ro.observe(host);
  resize();

  // ------------------------------------------------------------------ chargement d'un circuit
  function loadTrack(i: number) {
    if (trackIndex === i && trackScene) return;
    trackIndex = i;
    track = builtTracks.get(i) ?? buildTrack(TRACKS[i]);
    builtTracks.set(i, track);
    if (trackScene) { scene.remove(trackScene.group); trackScene.dispose(); }
    trackScene = buildTrackScene(track);
    scene.add(trackScene.group);
    const L = LOOKS[track.def.theme];
    scene.fog = new THREE.Fog(L.fog, L.fogNear, L.fogFar);
    renderer.setClearColor(L.fog);
    hemi.color.set(L.ambient); hemi.intensity = L.ambientI;
    sun.color.set(L.sun); sun.intensity = L.sunI;
    (snow.material as THREE.PointsMaterial).color.set(track.def.theme === "stars" ? 0xffe9a0 : 0xffffff);
    newRace();
  }

  function newRace() {
    race = new Race(track);
    raceGroup.clear();
    shotMeshes.clear(); iceMeshes.clear();
    karts = race.karts.map((kart) => {
      const model = makeKart(kart.racer.id, kart.isPlayer ? null : kart.racer.name);
      const flame = new THREE.Mesh(new THREE.ConeGeometry(0.28, 1.5, 6), new THREE.MeshBasicMaterial({ color: 0xffa62a, transparent: true, opacity: 0.9 }));
      flame.rotation.z = Math.PI / 2;
      flame.position.set(-2.0, 0.8, 0);
      flame.visible = false;
      model.group.add(flame);
      raceGroup.add(model.group, model.shadow);
      return { kart, model, flame, wheelAngle: 0 };
    });
    boxMeshes = race.boxes.map((b) => { const m = makeItemBoxMesh(); m.position.set(b.x, 1.5, b.z); raceGroup.add(m); return m; });
    camH = race.player.heading;
    pPos.fill(-999);
    resultsT = 0;
    banner = { text: "", t: 0 };
  }

  function startRace(i: number) {
    loadTrack(i);
    newRace();
    screen = "race";
    acc = 0;
    sound.playing = true;
  }

  function goMenu() { screen = "menu"; menuFocus = 0; sound.playing = false; newRace(); }
  function pause() { if (screen === "race") { screen = "pause"; menuFocus = 0; } }
  function resume() { screen = "race"; }
  function toggleMusic() { saved.music = !saved.music; sound.setMusic(saved.music); store(saved); }

  const onVisibility = () => { if (document.hidden) { pause(); sound.suspend(); } else sound.resume(); };
  document.addEventListener("visibilitychange", onVisibility);

  // ------------------------------------------------------------------ commandes
  function readInput(): KartInput {
    const pad = controls.pad();
    const touch = coarse || controls.touchSeen;
    const joy = controls.joyAxis();
    const keySteer = (controls.key("ArrowRight", "KeyD") ? 1 : 0) - (controls.key("ArrowLeft", "KeyA") ? 1 : 0);
    if (controls.takePressed("item")) pendingItem = true;
    const brake = controls.key("ArrowDown", "KeyS") || pad.btn(1) || pad.btn(6) || controls.heldButton("brake");
    return {
      steer: keySteer !== 0 ? keySteer : joy !== 0 ? joy : pad.axis,
      // sur écran tactile le kart accélère tout seul : il reste à diriger et à freiner
      accel: controls.key("ArrowUp", "KeyW") || pad.btn(0) || pad.btn(7) || (touch && !brake),
      brake,
      drift: controls.key("ShiftLeft", "ShiftRight", "KeyX") || pad.btn(5) || pad.btn(4) || controls.heldButton("drift"),
      item: controls.key("Space", "KeyE") || pad.btn(2) || pad.btn(3) || controls.heldButton("item") || pendingItem,
    };
  }

  // ------------------------------------------------------------------ événements de course → sons et effets
  function onEvent(e: RaceEvent) {
    const mine = e.kart?.isPlayer;
    const k = e.kart;
    switch (e.type) {
      case "beep": sound.play("beep"); break;
      case "go": sound.play("go"); banner = { text: "PARTEZ !", t: 1.2 }; break;
      case "pickup": if (mine) sound.play("pickup"); break;
      case "item": if (mine) sound.play("item"); break;
      case "boost": case "driftBoost": if (mine) { sound.play("boost"); flash = 0.25; } break;
      case "throw": if (mine) sound.play("throw"); break;
      case "bump": sound.play("bump"); break;
      case "fall": if (mine) sound.play("fall"); break;
      case "hit":
        if (k) for (let i = 0; i < 26; i++) emit(k.x, 1, k.z, (Math.random() - 0.5) * 16, 4 + Math.random() * 9, (Math.random() - 0.5) * 16, i % 2 ? 0xffffff : 0xa8e4ff, 0.9, 18);
        if (mine) { sound.play("hit"); flash = 0.3; }
        break;
      case "lap":
        if (mine) { sound.play("lap"); banner = { text: e.n === track.def.laps ? `Dernier tour ! (${e.n}/${track.def.laps})` : `Tour ${e.n} sur ${track.def.laps}`, t: 2.2 }; }
        break;
      case "finish":
        if (mine && k) {
          sound.play("finish");
          banner = { text: k.rank === 1 ? "Victoire !" : `Arrivée : ${ordinal(k.rank)}`, t: 3 };
          const best = saved.best[track.def.id];
          if (!best || k.finishTime < best) { saved.best[track.def.id] = k.finishTime; store(saved); }
        }
        break;
    }
  }

  // ------------------------------------------------------------------ boucle
  const timer = new THREE.Timer();
  timer.connect(document);

  function frame() {
    if (stopped) return;
    timer.update();
    const dt = Math.min(Math.max(timer.getDelta(), 0), 0.1);
    time += dt;
    handleUi();
    controls.joyEnabled = screen === "race";
    if (screen !== "race" && controls.joy.pointer !== null) controls.releaseJoy();

    if (screen === "race" || screen === "results") {
      acc += dt;
      const input = screen === "race" ? readInput() : { steer: 0, accel: false, brake: false, drift: false, item: false };
      while (acc >= DT) {
        acc -= DT;
        race.step(input);
        pendingItem = false; // l'appui bref a été vu par au moins un pas de simulation
        for (const e of race.events) onEvent(e);
        race.events.length = 0;
      }
      if (screen === "race" && race.player.finished) { resultsT += dt; if (resultsT > 2.6) { screen = "results"; menuFocus = 0; sound.playing = false; banner.t = 0; } }
    } else acc = 0;

    const p = race.player;
    sound.setEngine(p.speed / MAX_SPEED, screen === "race" && race.time > -3.2 && !p.finished, p.boostT > 0);
    syncScene(dt);
    renderer.render(scene, camera);
    drawHud();
  }

  // ------------------------------------------------------------------ synchronisation de la scène
  function syncScene(dt: number) {
    const racing = screen === "race" || screen === "results" || screen === "pause";
    for (const k of karts) {
      const { kart, model } = k;
      const g = model.group;
      const spin = kart.spinT > 0 ? (1 - kart.spinT / 1.25) * Math.PI * 4 : 0;
      const slide = kart.drifting ? kart.driftDir * 0.38 : 0;
      g.position.set(kart.x, kart.y, kart.z);
      g.rotation.y = -(kart.heading + spin + slide);
      model.body.rotation.x = kart.steer * 0.07 * Math.min(1, kart.speed / 12) + (kart.drifting ? kart.driftDir * 0.08 : 0);
      model.body.position.y = kart.boostT > 0 ? Math.abs(Math.sin(time * 40)) * 0.04 : 0;
      k.wheelAngle -= (kart.speed / 0.38) * dt;
      model.wheels.forEach((w, i) => { w.rotation.set(0, i < 2 ? -kart.steer * 0.45 : 0, k.wheelAngle, "YXZ"); });
      model.shadow.position.set(kart.x, 0.045, kart.z);
      model.shadow.visible = kart.fallT <= 0;
      k.flame.visible = kart.boostT > 0;
      k.flame.scale.setScalar(0.8 + Math.random() * 0.5);
      if (model.tag) model.tag.visible = screen !== "menu";
      if (!racing || kart.fallT > 0) continue;
      // effets au sol
      const fx = Math.cos(kart.heading), fz = Math.sin(kart.heading);
      const rx = kart.x - fx * 1.1, rz = kart.z - fz * 1.1;
      if (kart.drifting && Math.random() < 0.7) {
        const c = kart.driftT > 1.9 ? 0xff7a2a : kart.driftT > 0.9 ? 0xffe36a : 0xffffff;
        for (const s of [-1, 1]) emit(rx - fz * s * 0.8, 0.2, rz + fx * s * 0.8, -fx * 3 + (Math.random() - 0.5) * 4, 2 + Math.random() * 3, -fz * 3 + (Math.random() - 0.5) * 4, c, 0.35, 14);
      }
      if (kart.offroad && kart.speed > 6 && track.def.outside === "slow" && Math.random() < 0.6) emit(rx, 0.2, rz, -fx * 2 + (Math.random() - 0.5) * 5, 2 + Math.random() * 4, -fz * 2 + (Math.random() - 0.5) * 5, 0xffffff, 0.5, 14);
      if (kart.boostT > 0 && Math.random() < 0.8) emit(rx - fx, 0.8, rz - fz, -fx * 6 + (Math.random() - 0.5) * 2, Math.random() * 2, -fz * 6 + (Math.random() - 0.5) * 2, Math.random() < 0.5 ? 0xffd23a : 0xff7a2a, 0.3, 0);
    }
    race.boxes.forEach((b, i) => {
      const m = boxMeshes[i];
      m.visible = b.respawn <= 0;
      m.rotation.set(time * 1.3, time * 1.9 + i, 0);
      m.position.y = 1.5 + Math.sin(time * 3 + i) * 0.2;
    });
    syncPool(race.snowballs, shotMeshes, makeSnowballMesh, (m, s) => { m.position.set(s.x, 0.9, s.z); m.rotation.x += 0.4; if (Math.random() < 0.5) emit(s.x, 0.9, s.z, 0, 1, 0, 0xffffff, 0.3, 4); });
    syncPool(race.ice, iceMeshes, makeIceMesh, (m, s) => { m.position.set(s.x, 0.02, s.z); });

    // particules
    for (let i = 0; i < MAXP; i++) {
      if (pLife[i] <= 0) continue;
      pLife[i] -= dt;
      if (pLife[i] <= 0) { pPos[i * 3 + 1] = -999; continue; }
      pVel[i * 3 + 1] -= pG[i] * dt;
      pPos[i * 3] += pVel[i * 3] * dt; pPos[i * 3 + 1] += pVel[i * 3 + 1] * dt; pPos[i * 3 + 2] += pVel[i * 3 + 2] * dt;
      if (pPos[i * 3 + 1] < 0.05 && pG[i] > 0) { pPos[i * 3 + 1] = 0.05; pVel[i * 3 + 1] *= -0.3; }
    }
    pGeo.attributes.position.needsUpdate = true;
    pGeo.attributes.color.needsUpdate = true;

    // décor animé
    if (trackScene) {
      for (const a of trackScene.animated) {
        if (a.kind === "spin") a.mesh.rotation.y = time * 0.8 + a.phase;
        else if (a.kind === "bob") a.mesh.position.y += Math.sin(time * 0.7 + a.phase) * 0.01;
      }
      if (track.def.theme === "stars") trackScene.roadTex.offset.y = -time * 0.12;
    }

    // caméra
    const p = race.player;
    if (screen === "menu") {
      const S = track.samples, n = S.length;
      const f = (time * 14) / track.step;
      const a = S[Math.floor(f) % n], b = S[(Math.floor(f) + 26) % n];
      camera.position.set(a.x - a.nx * 5, 13, a.z - a.nz * 5);
      camera.lookAt(b.x, 0.5, b.z);
      camFov = 60;
    } else {
      let d = p.heading - camH;
      while (d > Math.PI) d -= 2 * Math.PI;
      while (d < -Math.PI) d += 2 * Math.PI;
      if (p.spinT <= 0 && p.fallT <= 0) camH += d * Math.min(1, dt * 5.5);
      const fx = Math.cos(camH), fz = Math.sin(camH);
      if (screen === "results" || p.finished) {
        // tour d'honneur : la caméra tourne autour du kart
        const a = time * 0.5;
        camera.position.set(p.x + Math.cos(a) * 8.5, 3.6, p.z + Math.sin(a) * 8.5);
        camera.lookAt(p.x, 1.3, p.z);
      } else if (p.fallT > 0) {
        camera.lookAt(p.x, p.y, p.z);
      } else {
        const back = 8.4 - Math.min(1.2, p.speed / MAX_SPEED) * 0.9;
        camera.position.set(p.x - fx * back, 3.9, p.z - fz * back);
        camera.lookAt(p.x + fx * 6, 1.2, p.z + fz * 6);
      }
      const want = p.boostT > 0 ? 80 : 64 + Math.min(1, p.speed / MAX_SPEED) * 6;
      camFov += (want - camFov) * Math.min(1, dt * 6);
    }
    if (Math.abs(camera.fov - camFov) > 0.05) { camera.fov = camFov; camera.updateProjectionMatrix(); }
    if (trackScene) trackScene.sky.position.copy(camera.position);

    // neige : la boîte de flocons suit la caméra
    const fall = track.def.theme === "stars" ? 1.2 : 7;
    for (let i = 0; i < SNOW; i++) {
      sPos[i * 3 + 1] -= (fall + (i % 5)) * dt;
      sPos[i * 3] += Math.sin(time + i) * dt * 0.8;
      if (sPos[i * 3 + 1] < -2) sPos[i * 3 + 1] += 34;
    }
    sGeo.attributes.position.needsUpdate = true;
    snow.position.set(camera.position.x, 0, camera.position.z);
  }

  function syncPool<T extends { id: number }>(list: T[], pool: Map<number, THREE.Mesh>, make: () => THREE.Mesh, place: (m: THREE.Mesh, t: T) => void) {
    const alive = new Set<number>();
    for (const t of list) {
      alive.add(t.id);
      let m = pool.get(t.id);
      if (!m) { m = make(); pool.set(t.id, m); raceGroup.add(m); }
      place(m, t);
    }
    for (const [id, m] of pool) if (!alive.has(id)) { raceGroup.remove(m); pool.delete(id); }
  }

  // ------------------------------------------------------------------ interface 2D
  function tapIn(b: UiButton, t: { x: number; y: number }) { return t.x >= b.x && t.x <= b.x + b.w && t.y >= b.y && t.y <= b.y + b.h; }

  function handleUi() {
    const taps = controls.takeTaps();
    const keys = controls.takeKeys();
    for (const t of taps) {
      const hit = uiButtons.find((b) => tapIn(b, t));
      if (hit) { sound.play("ui"); hit.action(); return; }
    }
    if (screen === "race") { if (keys.includes("Escape") || keys.includes("KeyP")) pause(); return; }
    const enter = keys.some((k) => k === "Enter" || k === "Space" || k === "NumpadEnter");
    if (screen === "menu") {
      const cols = menuCols();
      let s = selected;
      if (keys.includes("ArrowRight")) s = Math.min(TRACKS.length - 1, s + 1);
      if (keys.includes("ArrowLeft")) s = Math.max(0, s - 1);
      if (keys.includes("ArrowDown")) s = Math.min(TRACKS.length - 1, s + cols);
      if (keys.includes("ArrowUp")) s = Math.max(0, s - cols);
      if (s !== selected) { selected = s; sound.play("ui"); loadTrack(s); }
      if (enter) { sound.play("ui"); startRace(selected); }
      if (keys.includes("Escape")) opts.onExit();
      return;
    }
    const list = menuItems();
    if (keys.includes("ArrowDown")) menuFocus = (menuFocus + 1) % list.length;
    if (keys.includes("ArrowUp")) menuFocus = (menuFocus - 1 + list.length) % list.length;
    if (enter && list[menuFocus]) { sound.play("ui"); list[menuFocus].action(); }
    if (screen === "pause" && keys.includes("Escape")) resume();
  }

  function menuItems(): { label: string; action: () => void; primary?: boolean }[] {
    if (screen === "pause") return [
      { label: "Reprendre", action: resume, primary: true },
      { label: "Recommencer la course", action: () => startRace(trackIndex) },
      { label: "Choisir un circuit", action: goMenu },
    ];
    return [
      { label: "Rejouer", action: () => startRace(trackIndex), primary: true },
      { label: "Choisir un circuit", action: goMenu },
    ];
  }

  function panel(x: number, y: number, w: number, h: number, r = 18, alpha = 0.55) {
    ctx.fillStyle = `rgba(18,16,48,${alpha})`;
    ctx.beginPath(); ctx.roundRect(x, y, w, h, r); ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,0.2)"; ctx.lineWidth = 1.5; ctx.stroke();
  }

  function text(s: string, x: number, y: number, size: number, color = "#fff", align: CanvasTextAlign = "left", weight = 700, stroke = 0) {
    ctx.font = `${weight} ${size}px ${FONT}`;
    ctx.textAlign = align; ctx.textBaseline = "middle";
    if (stroke) { ctx.lineJoin = "round"; ctx.lineWidth = stroke; ctx.strokeStyle = "rgba(20,12,50,0.9)"; ctx.strokeText(s, x, y); }
    ctx.fillStyle = color; ctx.fillText(s, x, y);
  }

  function iconButton(x: number, y: number, size: number, icon: string, action: () => void) {
    uiButtons.push({ x, y, w: size, h: size, action });
    panel(x, y, size, size, size / 2, 0.5);
    const c = x + size / 2, m = y + size / 2, s = size * 0.22;
    ctx.fillStyle = "#fff"; ctx.strokeStyle = "#fff"; ctx.lineWidth = 3; ctx.lineCap = "round";
    if (icon === "pause") { ctx.fillRect(c - s * 0.8, m - s, s * 0.55, s * 2); ctx.fillRect(c + s * 0.25, m - s, s * 0.55, s * 2); }
    else if (icon === "music" || icon === "musicOff") {
      ctx.beginPath(); ctx.ellipse(c - s * 0.5, m + s * 0.7, s * 0.45, s * 0.35, -0.3, 0, Math.PI * 2); ctx.fill();
      ctx.fillRect(c - s * 0.1, m - s * 1.1, 3, s * 1.8);
      ctx.beginPath(); ctx.moveTo(c - s * 0.1, m - s * 1.1); ctx.lineTo(c + s * 0.9, m - s * 0.6); ctx.stroke();
      if (icon === "musicOff") { ctx.strokeStyle = "#ff6b7a"; ctx.beginPath(); ctx.moveTo(c - s * 1.2, m - s * 1.2); ctx.lineTo(c + s * 1.2, m + s * 1.2); ctx.stroke(); }
    } else { ctx.beginPath(); ctx.moveTo(c - s, m - s); ctx.lineTo(c + s, m + s); ctx.moveTo(c + s, m - s); ctx.lineTo(c - s, m + s); ctx.stroke(); }
  }

  function topRight(withPause: boolean) {
    const size = cssW < 500 ? 40 : 46, gap = 10;
    let x = cssW - 14 - size;
    iconButton(x, 12, size, "close", () => opts.onExit()); x -= size + gap;
    iconButton(x, 12, size, saved.music ? "music" : "musicOff", toggleMusic); x -= size + gap;
    if (withPause) iconButton(x, 12, size, "pause", pause);
    return size;
  }

  function trackOutline(t: Track, x: number, y: number, w: number, h: number, color: string, width: number) {
    const S = t.samples;
    let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity;
    for (const s of S) { x0 = Math.min(x0, s.x); x1 = Math.max(x1, s.x); z0 = Math.min(z0, s.z); z1 = Math.max(z1, s.z); }
    const k = Math.min(w / (x1 - x0), h / (z1 - z0));
    const ox = x + (w - (x1 - x0) * k) / 2 - x0 * k, oz = y + (h - (z1 - z0) * k) / 2 - z0 * k;
    ctx.beginPath();
    for (let i = 0; i <= S.length; i += 3) { const s = S[i % S.length]; if (i) ctx.lineTo(ox + s.x * k, oz + s.z * k); else ctx.moveTo(ox + s.x * k, oz + s.z * k); }
    ctx.closePath();
    ctx.lineJoin = "round"; ctx.lineCap = "round";
    ctx.strokeStyle = "rgba(10,8,35,0.75)"; ctx.lineWidth = width + 3; ctx.stroke();
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke();
    return (px: number, pz: number): [number, number] => [ox + px * k, oz + pz * k];
  }

  function itemIcon(kind: ItemKind, x: number, y: number, s: number) {
    ctx.save(); ctx.translate(x, y);
    if (kind === "boost") {
      // sucre d'orge turbo
      ctx.lineCap = "round"; ctx.lineWidth = s * 0.22;
      ctx.strokeStyle = "#fff"; ctx.beginPath(); ctx.moveTo(s * 0.15, s * 0.45); ctx.lineTo(s * 0.15, -s * 0.15); ctx.arc(-s * 0.1, -s * 0.15, s * 0.25, 0, Math.PI, true); ctx.stroke();
      ctx.strokeStyle = "#e5283a"; ctx.setLineDash([s * 0.14, s * 0.16]); ctx.beginPath(); ctx.moveTo(s * 0.15, s * 0.45); ctx.lineTo(s * 0.15, -s * 0.15); ctx.arc(-s * 0.1, -s * 0.15, s * 0.25, 0, Math.PI, true); ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = "#ffd23a"; ctx.beginPath(); ctx.moveTo(-s * 0.5, s * 0.1); ctx.lineTo(-s * 0.2, s * 0.1); ctx.lineTo(-s * 0.3, s * 0.3); ctx.lineTo(-s * 0.05, s * 0.3); ctx.lineTo(-s * 0.5, s * 0.6); ctx.lineTo(-s * 0.38, s * 0.36); ctx.lineTo(-s * 0.58, s * 0.36); ctx.fill();
    } else if (kind === "snowball") {
      const g = ctx.createRadialGradient(-s * 0.12, -s * 0.12, s * 0.05, 0, 0, s * 0.42);
      g.addColorStop(0, "#fff"); g.addColorStop(1, "#9fc0f0");
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(0, 0, s * 0.4, 0, Math.PI * 2); ctx.fill();
    } else {
      ctx.fillStyle = "#a8e4ff";
      for (const [dx, h] of [[-0.25, 0.5], [0, 0.8], [0.25, 0.55]]) { ctx.beginPath(); ctx.moveTo((dx - 0.14) * s, s * 0.4); ctx.lineTo(dx * s, s * 0.4 - h * s); ctx.lineTo((dx + 0.14) * s, s * 0.4); ctx.fill(); }
      ctx.fillStyle = "#d8f6ff"; ctx.beginPath(); ctx.ellipse(0, s * 0.4, s * 0.48, s * 0.1, 0, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  }

  const ITEM_NAMES: Record<ItemKind, string> = { boost: "Turbo", snowball: "Boule de neige", ice: "Glaçons" };

  function drawHud() {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);
    uiButtons = [];
    if (flash > 0) { ctx.fillStyle = `rgba(255,255,255,${flash * 0.5})`; ctx.fillRect(0, 0, cssW, cssH); flash = Math.max(0, flash - 1 / 60 * 2); }
    if (screen === "menu") { drawMenu(); return; }
    drawRaceHud();
    if (screen === "pause") drawOverlayMenu("Pause", track.def.name);
    if (screen === "results") drawResults();
  }

  function drawRaceHud() {
    const p = race.player;
    const small = cssW < 560 || cssH < 380;
    // lignes de vitesse pendant un turbo
    if (p.boostT > 0 && screen === "race") {
      ctx.strokeStyle = "rgba(255,255,255,0.35)"; ctx.lineWidth = 2;
      for (let i = 0; i < 26; i++) {
        const a = (i / 26) * Math.PI * 2 + (i * 1.7), r0 = Math.max(cssW, cssH) * (0.32 + ((time * 2.4 + i * 0.37) % 1) * 0.3);
        ctx.beginPath(); ctx.moveTo(cssW / 2 + Math.cos(a) * r0, cssH / 2 + Math.sin(a) * r0 * 0.7); ctx.lineTo(cssW / 2 + Math.cos(a) * (r0 + 60), cssH / 2 + Math.sin(a) * (r0 + 60) * 0.7); ctx.stroke();
      }
    }
    // position, tour, temps
    const pw = small ? 132 : 170, ph = small ? 64 : 82;
    panel(12, 12, pw, ph, 20);
    const rankCol = ["#ffd86b", "#e8ecf8", "#f0a060", "#fff", "#fff"][p.rank - 1];
    text(ordinal(p.rank), 24, 12 + ph * 0.5, small ? 34 : 44, rankCol, "left", 700, 5);
    const rankW = ctx.measureText(ordinal(p.rank)).width;
    if (!small) text("/5", 24 + rankW + 4, 12 + ph * 0.62, 16, "rgba(255,255,255,0.75)", "left", 600);
    const lap = Math.max(1, Math.min(track.def.laps, p.lap));
    text(`Tour ${lap}/${track.def.laps}`, 12 + pw - 12, 12 + ph * 0.3, small ? 13 : 16, "#fff", "right", 600);
    text(fmtTime(Math.max(0, p.finished ? p.finishTime : race.time)), 12 + pw - 12, 12 + ph * 0.6, small ? 14 : 17, "#ffe9a0", "right", 600);
    // une pastille par tour : pleine quand le tour est bouclé, cerclée pour le tour en cours
    const done = Math.max(0, Math.min(track.def.laps, p.lap - 1));
    for (let i = 0; i < track.def.laps; i++) {
      const dx = 12 + pw - 16 - (track.def.laps - 1 - i) * (small ? 13 : 16), dy = 12 + ph * 0.86;
      ctx.beginPath(); ctx.arc(dx, dy, small ? 4 : 5, 0, Math.PI * 2);
      ctx.fillStyle = i < done ? "#7dff9a" : "rgba(255,255,255,0.18)"; ctx.fill();
      if (i === done && !p.finished) { ctx.strokeStyle = "#ffe9a0"; ctx.lineWidth = 2; ctx.stroke(); }
    }

    // case objet
    const is = small ? 54 : 66, ix = 12 + pw + 10, iy = 12;
    panel(ix, iy, is, is, 16, 0.6);
    if (p.roulette > 0) {
      const kinds: ItemKind[] = ["boost", "snowball", "ice"];
      itemIcon(kinds[Math.floor(time * 12) % 3], ix + is / 2, iy + is / 2, is * 0.8);
    } else if (p.item) {
      const pulse = 1 + Math.sin(time * 8) * 0.05;
      itemIcon(p.item, ix + is / 2, iy + is / 2, is * 0.82 * pulse);
      if (!small) text(ITEM_NAMES[p.item], ix + is + 10, iy + is / 2, 15, "#fff", "left", 600, 4);
    }

    const bs = topRight(screen === "race");

    // mini-carte
    // sur un écran bas (téléphone à l'horizontale), elle se range en haut, à gauche des boutons
    const low = cssH < 480 && cssW > cssH;
    const ms = low ? Math.max(70, Math.min(96, cssH * 0.24)) : Math.max(84, Math.min(150, Math.min(cssW, cssH) * 0.26));
    const mx = low ? cssW - 14 - (bs + 10) * 3 - ms - 4 : cssW - ms - 14, my = low ? 12 : 12 + bs + 12;
    panel(mx, my, ms, ms, 16, 0.4);
    const map = trackOutline(track, mx + 10, my + 10, ms - 20, ms - 20, "rgba(255,255,255,0.9)", 3.5);
    for (const k of [...race.karts].sort((a, b) => (a.isPlayer ? 1 : 0) - (b.isPlayer ? 1 : 0))) {
      const [dx, dy] = map(k.x, k.z);
      ctx.fillStyle = k.racer.color; ctx.strokeStyle = k.isPlayer ? "#fff" : "rgba(10,8,35,0.9)"; ctx.lineWidth = k.isPlayer ? 2.5 : 1.5;
      ctx.beginPath(); ctx.arc(dx, dy, k.isPlayer ? 5.5 : 4, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    }

    // décompte
    if (race.time < 0 && race.time > -3.2) {
      const n = Math.ceil(-race.time);
      const k = 1 - (-race.time % 1);
      ctx.save();
      ctx.translate(cssW / 2, cssH * 0.36); ctx.scale(1.4 - k * 0.4, 1.4 - k * 0.4);
      ctx.globalAlpha = Math.min(1, (1 - k) * 3);
      text(String(n), 0, 0, Math.min(140, cssH * 0.3), ["#7dff9a", "#ffd86b", "#ff6b7a"][n - 1] ?? "#fff", "center", 700, 10);
      ctx.restore();
    } else if (race.time <= -3.2 && screen === "race") {
      text("À vos marques…", cssW / 2, cssH * 0.34, Math.min(40, cssW / 14), "#fff", "center", 700, 7);
    }
    if (banner.t > 0) {
      banner.t -= 1 / 60;
      ctx.globalAlpha = Math.min(1, banner.t * 3);
      text(banner.text, cssW / 2, cssH * 0.3, Math.min(64, cssW / 9), "#fff8ec", "center", 700, 9);
      ctx.globalAlpha = 1;
    }
    if (p.fallT > 0) text("Oups !", cssW / 2, cssH * 0.3, Math.min(54, cssW / 10), "#ffd86b", "center", 700, 8);
    if (screen === "race") drawTouch();
  }

  function drawTouch() {
    if (!(coarse || controls.touchSeen)) return;
    const p = race.player;
    const j = controls.joy, active = j.pointer !== null, axis = controls.joyAxis();
    ctx.save();
    ctx.globalAlpha = active ? 1 : 0.75;
    const g = ctx.createRadialGradient(j.baseX, j.baseY, j.radius * 0.2, j.baseX, j.baseY, j.radius);
    g.addColorStop(0, "rgba(20,18,55,0.15)"); g.addColorStop(1, "rgba(20,18,55,0.42)");
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(j.baseX, j.baseY, j.radius, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,0.5)"; ctx.lineWidth = 2.5; ctx.stroke();
    const a = j.radius * 0.72, s2 = j.radius * 0.16;
    for (const d of [-1, 1]) {
      ctx.fillStyle = axis * d > 0 ? "rgba(255,233,160,0.95)" : "rgba(255,255,255,0.5)";
      ctx.beginPath(); ctx.moveTo(j.baseX + d * (a + s2), j.baseY); ctx.lineTo(j.baseX + d * (a - s2 * 0.4), j.baseY - s2); ctx.lineTo(j.baseX + d * (a - s2 * 0.4), j.baseY + s2); ctx.fill();
    }
    const kr = j.radius * 0.46;
    ctx.fillStyle = active ? "rgba(255,255,255,0.95)" : "rgba(255,255,255,0.7)";
    ctx.beginPath(); ctx.arc(j.knobX, j.knobY, kr, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
    for (const b of controls.touchButtons) {
      const on = controls.heldButton(b.id);
      const dim = b.id === "item" && !p.item;
      ctx.globalAlpha = dim ? 0.45 : 1;
      ctx.fillStyle = on ? "rgba(255,255,255,0.4)" : b.id === "item" ? "rgba(196,20,47,0.5)" : "rgba(20,18,55,0.35)";
      ctx.beginPath(); ctx.arc(b.cx, b.cy, b.r, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = on ? "rgba(255,255,255,0.95)" : "rgba(255,255,255,0.55)"; ctx.lineWidth = 2.5; ctx.stroke();
      if (b.id === "item") {
        if (p.item && p.roulette <= 0) itemIcon(p.item, b.cx, b.cy - b.r * 0.12, b.r * 1.1);
        text("Objet", b.cx, b.cy + b.r * 0.62, Math.max(10, b.r * 0.26), "rgba(255,255,255,0.9)", "center", 600);
      } else text(b.id === "drift" ? "Dérapage" : "Frein", b.cx, b.cy, Math.max(10, b.r * 0.3), "rgba(255,255,255,0.92)", "center", 600);
      ctx.globalAlpha = 1;
    }
  }

  function buttons(items: { label: string; action: () => void; primary?: boolean }[], top: number) {
    const w = Math.min(cssW - 48, 320), h = cssH < 420 ? 42 : 50, gap = 10;
    items.forEach((it, i) => {
      const x = (cssW - w) / 2, y = top + i * (h + gap);
      uiButtons.push({ x, y, w, h, action: it.action });
      const g = ctx.createLinearGradient(0, y, 0, y + h);
      if (it.primary) { g.addColorStop(0, "#ff6170"); g.addColorStop(1, "#c4142f"); } else { g.addColorStop(0, "rgba(255,255,255,0.24)"); g.addColorStop(1, "rgba(255,255,255,0.1)"); }
      ctx.fillStyle = g; ctx.beginPath(); ctx.roundRect(x, y, w, h, h / 2); ctx.fill();
      ctx.strokeStyle = i === menuFocus ? "#ffe9a0" : "rgba(255,255,255,0.35)"; ctx.lineWidth = i === menuFocus ? 3 : 1.5; ctx.stroke();
      text(it.label, cssW / 2, y + h / 2 + 1, cssH < 420 ? 17 : 20, "#fff", "center", 700, it.primary ? 4 : 0);
    });
    return top + items.length * (h + gap);
  }

  function bigTitle(main: string, sub: string, y: number, size: number) {
    ctx.save(); ctx.shadowColor = "rgba(255,200,120,0.8)"; ctx.shadowBlur = 22;
    text(main, cssW / 2, y, size, "#fff8ec", "center", 700, Math.max(5, size / 7));
    ctx.restore();
    if (sub) text(sub, cssW / 2, y + size * 0.82, Math.max(13, size * 0.36), "#ffd9a0", "center", 600, 4);
  }

  function drawOverlayMenu(title: string, sub: string) {
    ctx.fillStyle = "rgba(10,8,35,0.55)"; ctx.fillRect(0, 0, cssW, cssH);
    const size = Math.min(60, cssW / 10, cssH / 7);
    bigTitle(title, sub, cssH * 0.2, size);
    buttons(menuItems(), cssH * 0.2 + size * 1.6);
    topRight(false);
  }

  function drawResults() {
    ctx.fillStyle = "rgba(10,8,35,0.5)"; ctx.fillRect(0, 0, cssW, cssH);
    const p = race.player;
    const compact = cssH < 430;
    const size = Math.min(compact ? 34 : 52, cssW / 11);
    const ty = compact ? 30 : cssH * 0.1;
    bigTitle(p.rank === 1 ? "Victoire !" : p.rank <= 3 ? "Sur le podium !" : "Course terminée", "", ty, size);
    const rows = race.standings();
    const rh = compact ? 30 : 40, w = Math.min(cssW - 32, 440);
    const x = (cssW - w) / 2;
    let y = ty + size * 0.9;
    rows.forEach((k, i) => {
      if (k.isPlayer) { ctx.fillStyle = "rgba(255,210,110,0.28)"; ctx.beginPath(); ctx.roundRect(x, y, w, rh - 4, 12); ctx.fill(); ctx.strokeStyle = "#ffe9a0"; ctx.lineWidth = 2; ctx.stroke(); }
      else panel(x, y, w, rh - 4, 12, 0.5);
      text(ordinal(i + 1), x + 14, y + rh / 2 - 2, compact ? 16 : 20, ["#ffd86b", "#e8ecf8", "#f0a060"][i] ?? "#fff", "left", 700);
      ctx.fillStyle = k.racer.color; ctx.beginPath(); ctx.arc(x + 70, y + rh / 2 - 2, compact ? 7 : 9, 0, Math.PI * 2); ctx.fill();
      text(k.racer.name, x + 88, y + rh / 2 - 2, compact ? 15 : 19, "#fff", "left", 600);
      text(k.finished ? fmtTime(k.finishTime) : "en course", x + w - 14, y + rh / 2 - 2, compact ? 14 : 17, k.finished ? "#ffe9a0" : "rgba(255,255,255,0.6)", "right", 600);
      y += rh;
    });
    const best = saved.best[track.def.id];
    if (best) text(`Meilleur temps : ${fmtTime(best)}`, cssW / 2, y + 8, compact ? 13 : 15, "rgba(255,255,255,0.85)", "center", 600, 3);
    buttons(menuItems(), y + (compact ? 22 : 30));
    topRight(false);
  }

  function menuCols() { return cssW >= 820 ? 4 : cssW >= 420 ? 2 : 1; }

  function drawMenu() {
    // voile léger pour que le texte reste lisible sur le survol du circuit
    const g = ctx.createLinearGradient(0, 0, 0, cssH);
    g.addColorStop(0, "rgba(10,8,35,0.6)"); g.addColorStop(0.35, "rgba(10,8,35,0.15)"); g.addColorStop(1, "rgba(10,8,35,0.6)");
    ctx.fillStyle = g; ctx.fillRect(0, 0, cssW, cssH);
    const compact = cssH < 460;
    const size = Math.min(compact ? 30 : 50, cssW / 13);
    bigTitle("Le Grand Prix du Père Noël", compact ? "" : "Choisis ton circuit", compact ? 30 : 44, size);
    topRight(false);

    const cols = menuCols(), rows = Math.ceil(TRACKS.length / cols);
    const top = compact ? 58 : 44 + size * 1.5;
    const bottomBar = compact ? 58 : 74;
    const gap = 12;
    const cw = Math.min(250, (cssW - 28 - gap * (cols - 1)) / cols);
    const ch = Math.min(rows === 1 ? 250 : 190, (cssH - top - bottomBar - gap * (rows - 1)) / rows);
    const ox = (cssW - (cw * cols + gap * (cols - 1))) / 2;
    const oy = top + Math.max(0, (cssH - top - bottomBar - (ch * rows + gap * (rows - 1))) / 2);
    TRACKS.forEach((def, i) => {
      const x = ox + (i % cols) * (cw + gap), y = oy + Math.floor(i / cols) * (ch + gap);
      const sel = i === selected;
      uiButtons.push({ x, y, w: cw, h: ch, action: () => { if (selected === i) startRace(i); else { selected = i; loadTrack(i); } } });
      const L = LOOKS[def.theme];
      const cg = ctx.createLinearGradient(0, y, 0, y + ch);
      cg.addColorStop(0, L.skyTop); cg.addColorStop(1, L.skyMid);
      ctx.save();
      ctx.globalAlpha = sel ? 0.96 : 0.78;
      ctx.fillStyle = cg; ctx.beginPath(); ctx.roundRect(x, y, cw, ch, 18); ctx.fill();
      ctx.restore();
      ctx.strokeStyle = sel ? "#ffe9a0" : "rgba(255,255,255,0.35)"; ctx.lineWidth = sel ? 4 : 1.5;
      ctx.beginPath(); ctx.roundRect(x, y, cw, ch, 18); ctx.stroke();
      const textH = ch < 130 ? 40 : 62;
      const t = builtTracks.get(i) ?? buildTrack(def);
      builtTracks.set(i, t);
      if (ch - textH > 40) trackOutline(t, x + 18, y + 12, cw - 36, ch - textH - 18, "#ffffff", ch < 130 ? 3 : 4.5);
      text(def.name, x + cw / 2, y + ch - textH + 12, Math.min(19, cw / 11), "#fff", "center", 700, 4);
      if (ch >= 130) text(def.tagline, x + cw / 2, y + ch - textH + 34, Math.min(13, cw / 15), "rgba(255,255,255,0.9)", "center", 500, 3);
      const stars = "★".repeat(def.difficulty) + "☆".repeat(3 - def.difficulty);
      text(stars, x + 12, y + ch - 13, 13, "#ffd86b", "left", 700, 3);
      const best = saved.best[def.id];
      if (best) text(fmtTime(best), x + cw - 12, y + ch - 13, 13, "#fff", "right", 600, 3);
    });
    const bw = Math.min(cssW - 48, 300), bh = compact ? 42 : 52;
    const bx = (cssW - bw) / 2, by = cssH - bh - (compact ? 8 : 14);
    uiButtons.push({ x: bx, y: by, w: bw, h: bh, action: () => startRace(selected) });
    const bg = ctx.createLinearGradient(0, by, 0, by + bh);
    bg.addColorStop(0, "#ff6170"); bg.addColorStop(1, "#c4142f");
    ctx.fillStyle = bg; ctx.beginPath(); ctx.roundRect(bx, by, bw, bh, bh / 2); ctx.fill();
    ctx.strokeStyle = "#ffe9a0"; ctx.lineWidth = 3; ctx.stroke();
    text("Départ !", cssW / 2, by + bh / 2 + 1, compact ? 19 : 23, "#fff", "center", 700, 4);
  }

  loadTrack(0);
  renderer.setAnimationLoop(frame);
  Object.assign(window, { __kart: { get race() { return race; }, get screen() { return screen; }, start: (i: number) => startRace(i), select: (i: number) => { selected = i; loadTrack(i); } } });

  return {
    stop() {
      stopped = true;
      renderer.setAnimationLoop(null);
      timer.dispose();
      ro.disconnect();
      controls.dispose();
      sound.dispose();
      document.removeEventListener("visibilitychange", onVisibility);
      trackScene?.dispose();
      dotTex.dispose();
      renderer.dispose();
      glCanvas.remove(); hud.remove();
      delete (window as unknown as { __kart?: unknown }).__kart;
    },
  };
}
