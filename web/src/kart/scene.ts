// Scène 3D « low-poly » : circuits, décors et karts construits à partir de formes simples (three.js r187).
// Aucun modèle ni image à charger : géométries fusionnées avec couleurs par sommet, textures dessinées sur canvas.
// Convention : simulation (x, z) → monde (x, 0, z) ; un kart regarde vers +X, rotation.y = -cap.

import * as THREE from "three";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import type { RacerId } from "./sim";
import { distanceToTrack, type Track, type TrackTheme } from "./tracks";

export interface ThemeLook {
  skyTop: string; skyMid: string; skyBottom: string;
  fog: number; fogNear: number; fogFar: number;
  ground: number | null;
  ambient: number; ambientI: number; sun: number; sunI: number;
  stars: boolean;
}

export const LOOKS: Record<TrackTheme, ThemeLook> = {
  north: { skyTop: "#2f86e0", skyMid: "#8fd0ff", skyBottom: "#eaf6ff", fog: 0xdcefff, fogNear: 90, fogFar: 330, ground: 0xf3f8ff, ambient: 0xbfd8ff, ambientI: 1.5, sun: 0xfff4dc, sunI: 2.2, stars: false },
  lake: { skyTop: "#27307a", skyMid: "#c0669a", skyBottom: "#ffc48a", fog: 0xe0a0a8, fogNear: 80, fogFar: 320, ground: 0x9fd0f0, ambient: 0xc8b8ff, ambientI: 1.4, sun: 0xffc890, sunI: 2.1, stars: false },
  village: { skyTop: "#050720", skyMid: "#141a52", skyBottom: "#3a3f8a", fog: 0x1a1e52, fogNear: 60, fogFar: 260, ground: 0x8fa2d8, ambient: 0x8a9cff, ambientI: 1.5, sun: 0xcfd8ff, sunI: 1.3, stars: true },
  stars: { skyTop: "#03020f", skyMid: "#1c0f4a", skyBottom: "#5a2a8a", fog: 0x2a1560, fogNear: 120, fogFar: 420, ground: null, ambient: 0xb8a8ff, ambientI: 1.7, sun: 0xffffff, sunI: 1.6, stars: true },
};

// ------------------------------------------------------------------ utilitaires

function rng(seed: number) {
  let s = seed;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

/** Colore une géométrie (attribut `color`) après lui avoir appliqué une transformation. */
function part(geo: THREE.BufferGeometry, color: number | string, pos: [number, number, number] = [0, 0, 0], rot: [number, number, number] = [0, 0, 0], scale: [number, number, number] = [1, 1, 1]) {
  const g = geo.index ? geo.toNonIndexed() : geo.clone();
  const m = new THREE.Matrix4().compose(new THREE.Vector3(...pos), new THREE.Quaternion().setFromEuler(new THREE.Euler(...rot)), new THREE.Vector3(...scale));
  g.applyMatrix4(m);
  const c = new THREE.Color(color);
  const n = g.attributes.position.count;
  const arr = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) { arr[i * 3] = c.r; arr[i * 3 + 1] = c.g; arr[i * 3 + 2] = c.b; }
  g.setAttribute("color", new THREE.BufferAttribute(arr, 3));
  return g;
}

function merge(parts: THREE.BufferGeometry[]) {
  const g = mergeGeometries(parts, false)!;
  parts.forEach((p) => p.dispose());
  g.computeVertexNormals();
  return g;
}

const box = (w: number, h: number, d: number) => new THREE.BoxGeometry(w, h, d);
const ball = (r: number, seg = 10) => new THREE.SphereGeometry(r, seg, Math.max(6, Math.round(seg * 0.7)));
const cone = (r: number, h: number, seg = 8) => new THREE.ConeGeometry(r, h, seg);
const cyl = (r0: number, r1: number, h: number, seg = 10) => new THREE.CylinderGeometry(r0, r1, h, seg);

function canvasTexture(w: number, h: number, draw: (c: CanvasRenderingContext2D) => void, repeat = true) {
  const cv = document.createElement("canvas");
  cv.width = w; cv.height = h;
  draw(cv.getContext("2d")!);
  const t = new THREE.CanvasTexture(cv);
  t.colorSpace = THREE.SRGBColorSpace;
  if (repeat) t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.anisotropy = 4;
  return t;
}

const flat = () => new THREE.MeshLambertMaterial({ vertexColors: true, flatShading: true });

// ------------------------------------------------------------------ personnages et karts

interface KartLook { body: number; accent: number; dark: number }
const KART_LOOKS: Record<RacerId, KartLook> = {
  santa: { body: 0xe0202e, accent: 0xffd23a, dark: 0x8e0f1d },
  grincheux: { body: 0x6a2fb0, accent: 0x1c1626, dark: 0x3a1766 },
  mere: { body: 0xff8fbd, accent: 0xffffff, dark: 0xd9548c },
  renne: { body: 0x1f7a4d, accent: 0xe8c36a, dark: 0x124a2e },
  lutin: { body: 0xffd23a, accent: 0x2fbf71, dark: 0xc79a10 },
};

function driverParts(id: RacerId): THREE.BufferGeometry[] {
  const skin = 0xffd9b8, white = 0xffffff;
  const P: THREE.BufferGeometry[] = [];
  const torso = (c: number, spiky = false) => P.push(part(spiky ? new THREE.IcosahedronGeometry(0.5, 1) : ball(0.46), c, [-0.1, 1.02, 0], [0, 0, 0], [1, 1.12, 1.05]));
  const arms = (c: number, glove: number) => {
    for (const s of [-1, 1]) {
      P.push(part(box(0.5, 0.17, 0.17), c, [0.16, 1.08, s * 0.4], [0, s * -0.35, -0.25]));
      P.push(part(ball(0.11, 6), glove, [0.42, 1.0, s * 0.3]));
    }
  };
  const head = (c: number, sy = 1) => P.push(part(ball(0.37), c, [0, 1.68, 0], [0, 0, 0], [1, sy, 1]));
  const eyes = (c: number, r = 0.055) => { for (const s of [-1, 1]) P.push(part(ball(r, 6), c, [0.32, 1.74, s * 0.14])); };

  if (id === "santa") {
    torso(0xe0202e); arms(0xe0202e, 0x23232e); head(skin); eyes(0x1e1420);
    P.push(part(cyl(0.47, 0.47, 0.1), 0x16161f, [-0.1, 0.92, 0]));
    P.push(part(box(0.1, 0.14, 0.16), 0xffd23a, [0.36, 0.92, 0]));
    P.push(part(ball(0.3), white, [0.17, 1.5, 0], [0, 0, 0], [0.8, 1.05, 1.05])); // barbe
    P.push(part(ball(0.33), white, [-0.14, 1.66, 0], [0, 0, 0], [0.8, 0.9, 1.08])); // cheveux
    P.push(part(ball(0.07, 6), 0xff8f8a, [0.37, 1.66, 0]));
    P.push(part(cyl(0.39, 0.39, 0.13, 12), white, [0, 1.93, 0])); // bord du bonnet
    P.push(part(cone(0.33, 0.62, 10), 0xe0202e, [-0.1, 2.24, 0], [0, 0, 0.42]));
    P.push(part(ball(0.12, 6), white, [-0.36, 2.44, 0]));
    // hotte à l'arrière
    P.push(part(ball(0.36), 0x9a6a36, [-1.0, 1.02, 0], [0, 0, 0], [1, 1.05, 1.15]));
    P.push(part(box(0.24, 0.24, 0.24), 0x2fbf71, [-0.96, 1.4, 0.12], [0.3, 0.4, 0.2]));
    P.push(part(box(0.2, 0.2, 0.2), 0xffd23a, [-1.06, 1.38, -0.14], [0.2, 0.9, 0]));
  } else if (id === "mere") {
    torso(0xd81f48); arms(0xd81f48, white); head(skin); eyes(0x1e1420);
    P.push(part(box(0.2, 0.5, 0.5), white, [0.26, 1.0, 0])); // tablier
    P.push(part(cyl(0.3, 0.42, 0.14, 12), white, [-0.04, 1.4, 0])); // col
    P.push(part(ball(0.4), 0xeef0f8, [-0.1, 1.74, 0], [0, 0, 0], [0.95, 0.95, 1.05])); // cheveux
    P.push(part(ball(0.2, 8), 0xeef0f8, [-0.22, 2.12, 0])); // chignon
    P.push(part(cyl(0.21, 0.21, 0.06, 10), 0xd81f48, [-0.22, 2.0, 0]));
    for (const s of [-1, 1]) P.push(part(ball(0.07, 6), 0xff9aa8, [0.3, 1.62, s * 0.2]));
    for (const s of [-1, 1]) P.push(part(new THREE.TorusGeometry(0.09, 0.02, 4, 10), 0x4a3a2a, [0.35, 1.74, s * 0.14], [0, Math.PI / 2, 0])); // lunettes
  } else if (id === "renne") {
    torso(0x9a5f2c); arms(0x9a5f2c, 0x3a2414); head(0xa8692f);
    P.push(part(box(0.36, 0.26, 0.3), 0xd9a86c, [0.34, 1.6, 0])); // museau
    P.push(part(ball(0.12, 8), 0xff2a2a, [0.56, 1.66, 0])); // nez rouge
    eyes(0x1a120c, 0.06);
    for (const s of [-1, 1]) {
      P.push(part(cone(0.1, 0.3, 6), 0xa8692f, [-0.05, 1.92, s * 0.36], [s * 0.9, 0, 0])); // oreilles
      P.push(part(cyl(0.04, 0.05, 0.6, 6), 0xe8d2a0, [-0.05, 2.24, s * 0.2], [s * 0.25, 0, 0])); // bois
      P.push(part(cyl(0.03, 0.04, 0.3, 6), 0xe8d2a0, [0.08, 2.36, s * 0.28], [s * 0.3, 0, -0.9]));
      P.push(part(cyl(0.03, 0.04, 0.28, 6), 0xe8d2a0, [-0.16, 2.44, s * 0.3], [s * 0.6, 0, 0.7]));
    }
    P.push(part(cyl(0.32, 0.36, 0.14, 10), 0xe0202e, [-0.04, 1.36, 0])); // écharpe
    P.push(part(box(0.12, 0.4, 0.16), 0xe0202e, [-0.3, 1.2, 0.3], [0.2, 0, 0.3]));
  } else if (id === "lutin") {
    torso(0x2fbf71); arms(0x2fbf71, 0xe0202e); head(skin); eyes(0x1e1420);
    P.push(part(cyl(0.3, 0.44, 0.12, 8), 0xe0202e, [-0.04, 1.38, 0]));
    for (const y of [0.9, 1.1]) P.push(part(ball(0.05, 6), 0xffd23a, [0.36, y, 0]));
    for (const s of [-1, 1]) P.push(part(cone(0.1, 0.42, 6), skin, [-0.02, 1.74, s * 0.48], [s * 1.25, 0, 0])); // grandes oreilles
    for (const s of [-1, 1]) P.push(part(ball(0.06, 6), 0xff9aa8, [0.3, 1.62, s * 0.2]));
    P.push(part(cyl(0.38, 0.38, 0.1, 10), 0xe0202e, [0, 1.94, 0]));
    P.push(part(cone(0.33, 0.85, 8), 0x2fbf71, [-0.14, 2.32, 0], [0, 0, 0.5]));
    P.push(part(ball(0.1, 6), 0xffd23a, [-0.52, 2.58, 0])); // grelot
  } else {
    // le Grincheux : boule de poils vert acide, cornes et écharpe violette
    torso(0x8bd23c, true); arms(0x8bd23c, 0x5a9a1e); head(0x9be04a, 1.12);
    for (const s of [-1, 1]) {
      P.push(part(ball(0.09, 6), 0xffe93a, [0.3, 1.78, s * 0.15]));
      P.push(part(ball(0.04, 5), 0x16161f, [0.37, 1.78, s * 0.15]));
      P.push(part(box(0.06, 0.05, 0.24), 0x2a4a10, [0.3, 1.92, s * 0.15], [s * 0.5, 0, 0])); // sourcils froncés
      P.push(part(cone(0.09, 0.34, 6), 0xf3e6c0, [-0.06, 2.12, s * 0.22], [s * 0.35, 0, 0])); // cornes
    }
    P.push(part(box(0.06, 0.05, 0.3), 0x2a4a10, [0.34, 1.52, 0])); // bouche boudeuse
    P.push(part(cyl(0.32, 0.38, 0.16, 8), 0x6a2fb0, [-0.04, 1.38, 0]));
    P.push(part(box(0.5, 0.14, 0.14), 0x6a2fb0, [-0.46, 1.34, 0.24], [0, 0.3, 0.2]));
    P.push(part(new THREE.IcosahedronGeometry(0.2, 0), 0x8bd23c, [-0.1, 2.14, 0])); // touffe
  }
  return P;
}

export interface KartModel { group: THREE.Group; body: THREE.Mesh; wheels: THREE.Mesh[]; shadow: THREE.Mesh; tag: THREE.Sprite | null }

let wheelGeo: THREE.BufferGeometry | null = null;

export function makeKart(id: RacerId, name: string | null): KartModel {
  const L = KART_LOOKS[id];
  const P: THREE.BufferGeometry[] = [
    part(box(2.3, 0.3, 1.2), L.body, [0, 0.45, 0]),
    part(box(0.75, 0.24, 0.9), L.dark, [1.3, 0.42, 0]),
    part(box(0.5, 0.2, 0.7), L.body, [1.0, 0.62, 0], [0, 0, -0.25]),
    part(box(0.16, 0.16, 1.6), L.accent, [1.72, 0.36, 0]),
    part(box(0.16, 0.16, 1.5), L.accent, [-1.22, 0.4, 0]),
    part(box(0.22, 0.72, 0.92), L.dark, [-0.58, 0.92, 0], [0, 0, 0.12]),
    part(box(0.55, 0.42, 0.8), 0x3a3d4a, [-0.98, 0.66, 0]),
    part(cyl(0.08, 0.1, 0.5, 6), 0x9aa0b0, [-1.26, 0.82, 0.24], [0, 0, 1.2]),
    part(cyl(0.08, 0.1, 0.5, 6), 0x9aa0b0, [-1.26, 0.82, -0.24], [0, 0, 1.2]),
    part(cyl(0.04, 0.04, 0.5, 6), 0x2a2c36, [0.5, 0.8, 0], [0, 0, -0.9]),
    part(new THREE.TorusGeometry(0.17, 0.045, 5, 10), 0x1c1d24, [0.32, 1.0, 0], [0, Math.PI / 2, 0.5]),
    ...driverParts(id),
  ];
  for (const s of [-1, 1]) P.push(part(box(1.1, 0.22, 0.24), L.dark, [0.05, 0.42, s * 0.72]));
  P.push(part(box(0.12, 0.34, 1.0), L.body, [-1.27, 0.66, 0])); // plaque arrière aux couleurs du pilote
  if (id === "grincheux") for (const z of [-0.5, 0, 0.5]) P.push(part(cone(0.1, 0.36, 5), 0xf3e6c0, [1.88, 0.38, z], [0, 0, -Math.PI / 2]));
  const body = new THREE.Mesh(merge(P), flat());
  const group = new THREE.Group();
  group.add(body);

  wheelGeo ??= merge([
    part(cyl(0.38, 0.38, 0.32, 10), 0x1c1d24, [0, 0, 0], [Math.PI / 2, 0, 0]),
    part(cyl(0.2, 0.2, 0.34, 6), 0xd8dce8, [0, 0, 0], [Math.PI / 2, 0, 0]),
  ]);
  const wheelMat = flat();
  const wheels: THREE.Mesh[] = [];
  for (const [x, z] of [[0.9, 0.82], [0.9, -0.82], [-0.85, 0.84], [-0.85, -0.84]]) {
    const w = new THREE.Mesh(wheelGeo, wheelMat);
    w.position.set(x, 0.38, z);
    group.add(w);
    wheels.push(w);
  }
  const shadow = new THREE.Mesh(new THREE.CircleGeometry(1.35, 24), new THREE.MeshBasicMaterial({ color: 0x000820, transparent: true, opacity: 0.28, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2;
  shadow.position.y = 0.04;
  shadow.scale.set(1.15, 0.8, 1);

  let tag: THREE.Sprite | null = null;
  if (name) {
    const tex = canvasTexture(256, 64, (c) => {
      c.font = "700 34px Fredoka, 'Trebuchet MS', sans-serif";
      c.textAlign = "center"; c.textBaseline = "middle";
      c.lineWidth = 8; c.strokeStyle = "rgba(15,10,40,0.85)"; c.lineJoin = "round";
      c.strokeText(name, 128, 34); c.fillStyle = "#fff"; c.fillText(name, 128, 34);
    }, false);
    tag = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false, fog: false }));
    tag.scale.set(3.4, 0.85, 1);
    tag.position.y = 3.3;
    group.add(tag);
  }
  return { group, body, wheels, shadow, tag };
}

// ------------------------------------------------------------------ circuit

function ribbon(track: Track, off0: number, off1: number, y0: number, y1: number, vScale: number, keep?: (i: number) => boolean) {
  const S = track.samples, n = S.length;
  const pos: number[] = [], uv: number[] = [], idx: number[] = [];
  for (let i = 0; i <= n; i++) {
    const s = S[i % n];
    pos.push(s.x + s.nx * off0, y0, s.z + s.nz * off0, s.x + s.nx * off1, y1, s.z + s.nz * off1);
    const v = (i * track.step) / vScale;
    uv.push(0, v, 1, v);
  }
  for (let i = 0; i < n; i++) {
    if (keep && !(keep(i) && keep((i + 1) % n))) continue;
    const a = i * 2;
    if (off0 < off1) idx.push(a, a + 1, a + 2, a + 1, a + 3, a + 2); else idx.push(a, a + 2, a + 1, a + 1, a + 2, a + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute("uv", new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

function roadTexture(theme: TrackTheme) {
  return canvasTexture(256, 256, (c) => {
    const r = rng(7);
    if (theme === "north") {
      c.fillStyle = "#5d6b86"; c.fillRect(0, 0, 256, 256);
      for (let i = 0; i < 900; i++) { c.fillStyle = r() > 0.5 ? "rgba(255,255,255,0.10)" : "rgba(0,0,20,0.12)"; c.fillRect(r() * 256, r() * 256, 2 + r() * 3, 2 + r() * 3); }
      c.fillStyle = "rgba(240,248,255,0.5)"; for (let i = 0; i < 26; i++) { c.beginPath(); c.ellipse(r() * 256, r() * 256, 8 + r() * 16, 4 + r() * 6, 0, 0, 7); c.fill(); }
      c.fillStyle = "#fff"; c.fillRect(124, 0, 8, 110); c.fillRect(8, 0, 6, 256); c.fillRect(242, 0, 6, 256);
    } else if (theme === "lake") {
      const g = c.createLinearGradient(0, 0, 256, 0);
      g.addColorStop(0, "#a8dcff"); g.addColorStop(0.5, "#e2f5ff"); g.addColorStop(1, "#a8dcff");
      c.fillStyle = g; c.fillRect(0, 0, 256, 256);
      c.strokeStyle = "rgba(60,130,200,0.45)"; c.lineWidth = 1.5;
      for (let i = 0; i < 22; i++) { c.beginPath(); let x = r() * 256, y = r() * 256; c.moveTo(x, y); for (let k = 0; k < 4; k++) { x += (r() - 0.5) * 70; y += (r() - 0.5) * 70; c.lineTo(x, y); } c.stroke(); }
      c.fillStyle = "rgba(255,255,255,0.7)"; for (let i = 0; i < 14; i++) { c.beginPath(); c.ellipse(r() * 256, r() * 256, 16, 3, -0.6, 0, 7); c.fill(); }
      c.fillStyle = "#3d8fd8"; c.fillRect(6, 0, 5, 256); c.fillRect(245, 0, 5, 256);
    } else if (theme === "village") {
      c.fillStyle = "#3d3850"; c.fillRect(0, 0, 256, 256);
      for (let y = 0; y < 256; y += 32) for (let x = 0; x < 256; x += 32) {
        const o = (y / 32) % 2 ? 16 : 0;
        c.fillStyle = `hsl(${250 + r() * 20},${14 + r() * 8}%,${30 + r() * 12}%)`;
        c.beginPath(); c.roundRect(x + o - 14, y + 2, 28, 28, 7); c.fill();
      }
      c.fillStyle = "rgba(255,220,150,0.5)"; c.fillRect(6, 0, 5, 256); c.fillRect(245, 0, 5, 256);
    } else {
      const g = c.createLinearGradient(0, 0, 256, 0);
      g.addColorStop(0, "#1b2a8a"); g.addColorStop(0.5, "#3a2fa8"); g.addColorStop(1, "#1b2a8a");
      c.fillStyle = g; c.fillRect(0, 0, 256, 256);
      for (let i = 0; i < 26; i++) { const x = r() * 256, y = r() * 256, s = 14 + r() * 30; const ng = c.createRadialGradient(x, y, 1, x, y, s); ng.addColorStop(0, "rgba(120,200,255,0.22)"); ng.addColorStop(1, "rgba(120,200,255,0)"); c.fillStyle = ng; c.fillRect(x - s, y - s, s * 2, s * 2); }
      c.fillStyle = "rgba(255,255,255,0.95)";
      for (let i = 0; i < 60; i++) { const x = r() * 256, y = r() * 256, s = 1 + r() * 2.5; c.fillRect(x - s, y - 0.5, s * 2, 1); c.fillRect(x - 0.5, y - s, 1, s * 2); }
      // chevrons dorés au centre, dans le sens de la course
      c.strokeStyle = "rgba(255,216,107,0.85)"; c.lineWidth = 7; c.lineJoin = "round";
      for (const y of [70, 198]) { c.beginPath(); c.moveTo(100, y - 18); c.lineTo(128, y + 14); c.lineTo(156, y - 18); c.stroke(); }
      c.fillStyle = "#ffd86b"; c.fillRect(0, 0, 9, 256); c.fillRect(247, 0, 9, 256);
    }
  });
}

function curbTexture(a: string, b: string) {
  return canvasTexture(32, 64, (c) => { c.fillStyle = a; c.fillRect(0, 0, 32, 32); c.fillStyle = b; c.fillRect(0, 32, 32, 32); });
}

function groundTexture(theme: TrackTheme) {
  return canvasTexture(256, 256, (c) => {
    const r = rng(3);
    c.fillStyle = "#ffffff"; c.fillRect(0, 0, 256, 256);
    for (let i = 0; i < 260; i++) {
      c.fillStyle = theme === "lake" ? `rgba(40,110,190,${0.05 + r() * 0.12})` : `rgba(120,150,220,${0.04 + r() * 0.1})`;
      c.beginPath(); c.ellipse(r() * 256, r() * 256, 10 + r() * 30, 5 + r() * 14, r() * 3, 0, 7); c.fill();
    }
    if (theme === "lake") { c.strokeStyle = "rgba(255,255,255,0.7)"; c.lineWidth = 2; for (let i = 0; i < 12; i++) { c.beginPath(); c.moveTo(r() * 256, r() * 256); c.lineTo(r() * 256, r() * 256); c.stroke(); } }
  });
}

function skyTexture(L: ThemeLook) {
  return canvasTexture(1024, 512, (c) => {
    const g = c.createLinearGradient(0, 0, 0, 512);
    g.addColorStop(0, L.skyTop); g.addColorStop(0.36, L.skyMid); g.addColorStop(0.5, L.skyBottom); g.addColorStop(1, L.skyBottom);
    c.fillStyle = g; c.fillRect(0, 0, 1024, 512);
    const r = rng(11);
    if (L.stars) {
      for (let i = 0; i < 520; i++) {
        c.fillStyle = `rgba(255,255,${200 + Math.floor(r() * 55)},${0.4 + r() * 0.6})`;
        const s = r() < 0.06 ? 2.4 : 1.1;
        c.fillRect(r() * 1024, r() * 250, s, s);
      }
      // aurore
      for (let x = 0; x < 1024; x += 4) {
        const y = 150 + Math.sin(x * 0.012) * 26 + Math.sin(x * 0.031) * 12, h = 70 + Math.sin(x * 0.02) * 30;
        const ag = c.createLinearGradient(0, y - h, 0, y + 10);
        ag.addColorStop(0, "rgba(80,255,170,0)"); ag.addColorStop(0.7, `rgba(90,255,190,${0.1 + 0.08 * Math.sin(x * 0.02) ** 2})`); ag.addColorStop(1, "rgba(120,120,255,0)");
        c.fillStyle = ag; c.fillRect(x, y - h, 5, h + 10);
      }
      // lune
      const mg = c.createRadialGradient(760, 110, 4, 760, 110, 90);
      mg.addColorStop(0, "rgba(255,255,240,1)"); mg.addColorStop(0.3, "rgba(255,255,240,0.95)"); mg.addColorStop(0.32, "rgba(200,210,255,0.3)"); mg.addColorStop(1, "rgba(200,210,255,0)");
      c.fillStyle = mg; c.fillRect(660, 10, 200, 200);
    } else {
      // nuages et soleil
      const sg = c.createRadialGradient(300, 120, 5, 300, 120, 120);
      sg.addColorStop(0, "rgba(255,255,235,1)"); sg.addColorStop(0.2, "rgba(255,250,210,0.9)"); sg.addColorStop(1, "rgba(255,240,200,0)");
      c.fillStyle = sg; c.fillRect(160, 0, 280, 260);
      c.fillStyle = "rgba(255,255,255,0.75)";
      for (let i = 0; i < 16; i++) { const x = r() * 1024, y = 90 + r() * 130; for (let k = 0; k < 5; k++) { c.beginPath(); c.ellipse(x + k * 22 - 40, y + Math.sin(k) * 6, 30 + r() * 14, 12 + r() * 6, 0, 0, 7); c.fill(); } }
    }
    // montagnes à l'horizon
    for (const [base, amp, col] of [[256, 46, "rgba(120,140,200,0.55)"], [258, 26, "rgba(90,105,170,0.7)"]] as const) {
      c.fillStyle = L.stars ? "rgba(30,30,80,0.8)" : col;
      c.beginPath(); c.moveTo(0, 262);
      for (let x = 0; x <= 1024; x += 16) c.lineTo(x, base - amp * (0.5 + 0.5 * Math.sin(x * 0.02 + amp) * Math.sin(x * 0.007 + 1)) - r() * 6);
      c.lineTo(1024, 262); c.fill();
    }
  }, false);
}

function giftTexture() {
  return canvasTexture(128, 128, (c) => {
    const g = c.createLinearGradient(0, 0, 128, 128);
    g.addColorStop(0, "#ff6170"); g.addColorStop(1, "#c4142f");
    c.fillStyle = g; c.fillRect(0, 0, 128, 128);
    c.fillStyle = "#ffd34d"; c.fillRect(52, 0, 24, 128); c.fillRect(0, 52, 128, 24);
    c.fillStyle = "#fff"; c.font = "900 54px sans-serif"; c.textAlign = "center"; c.textBaseline = "middle";
    c.fillText("?", 30, 30); c.fillText("?", 98, 98);
  }, false);
}

interface Placement { x: number; z: number; rot: number; scale: number }

function instanced(geo: THREE.BufferGeometry, mat: THREE.Material, list: Placement[], y = 0) {
  const m = new THREE.InstancedMesh(geo, mat, list.length);
  const o = new THREE.Object3D();
  list.forEach((p, i) => {
    o.position.set(p.x, y, p.z); o.rotation.set(0, p.rot, 0); o.scale.setScalar(p.scale);
    o.updateMatrix(); m.setMatrixAt(i, o.matrix);
  });
  m.instanceMatrix.needsUpdate = true;
  return m;
}

export interface TrackScene {
  group: THREE.Group;
  sky: THREE.Mesh;
  roadTex: THREE.Texture;
  animated: { mesh: THREE.Object3D; kind: "spin" | "bob" | "twinkle"; phase: number }[];
  dispose(): void;
}

export function buildTrackScene(track: Track): TrackScene {
  const def = track.def, theme = def.theme, L = LOOKS[theme], hw = def.halfWidth;
  const group = new THREE.Group();
  const disposables: { dispose(): void }[] = [];
  const animated: TrackScene["animated"] = [];
  const keepGeo = <T extends THREE.BufferGeometry>(g: T) => { disposables.push(g); return g; };
  const keepMat = <T extends THREE.Material>(m: T) => { disposables.push(m); return m; };
  const r = rng(def.id.length * 977 + 13);
  const fm = keepMat(flat());

  // ciel
  const skyTex = skyTexture(L);
  disposables.push(skyTex);
  const sky = new THREE.Mesh(keepGeo(new THREE.SphereGeometry(900, 24, 12)), keepMat(new THREE.MeshBasicMaterial({ map: skyTex, side: THREE.BackSide, fog: false, depthWrite: false })));
  sky.renderOrder = -10;
  group.add(sky);

  // sol
  if (L.ground !== null) {
    const gt = groundTexture(theme);
    gt.repeat.set(60, 60);
    disposables.push(gt);
    const ground = new THREE.Mesh(keepGeo(new THREE.PlaneGeometry(1600, 1600)), keepMat(new THREE.MeshLambertMaterial({ color: L.ground, map: gt })));
    ground.rotation.x = -Math.PI / 2;
    ground.position.set(track.samples[0].x + 30, -0.05, track.samples[0].z + 40);
    group.add(ground);
  } else {
    // mer de nuages sous la piste suspendue
    const ct = canvasTexture(256, 256, (c) => {
      c.fillStyle = "#1a0f45"; c.fillRect(0, 0, 256, 256);
      const q = rng(5);
      for (let i = 0; i < 60; i++) { const x = q() * 256, y = q() * 256, s = 20 + q() * 40; const g = c.createRadialGradient(x, y, 2, x, y, s); g.addColorStop(0, "rgba(190,150,255,0.55)"); g.addColorStop(1, "rgba(190,150,255,0)"); c.fillStyle = g; c.fillRect(x - s, y - s, s * 2, s * 2); }
    });
    ct.repeat.set(10, 10);
    disposables.push(ct);
    const clouds = new THREE.Mesh(keepGeo(new THREE.PlaneGeometry(2400, 2400)), keepMat(new THREE.MeshBasicMaterial({ map: ct, fog: true })));
    clouds.rotation.x = -Math.PI / 2;
    clouds.position.set(20, -38, 70);
    group.add(clouds);
  }

  // route, bordures, ligne de départ
  const roadTex = roadTexture(theme);
  disposables.push(roadTex);
  const roadMat = theme === "stars"
    ? keepMat(new THREE.MeshBasicMaterial({ map: roadTex, side: THREE.DoubleSide }))
    : keepMat(new THREE.MeshLambertMaterial({ map: roadTex }));
  group.add(new THREE.Mesh(keepGeo(ribbon(track, -hw, hw, 0.02, 0.02, 14)), roadMat));
  if (theme === "stars") {
    for (const s of [-1, 1]) {
      const rail = new THREE.Mesh(keepGeo(ribbon(track, s * hw, s * (hw + 0.5), 0.02, 0.5, 6)), keepMat(new THREE.MeshBasicMaterial({ color: 0xffe08a, side: THREE.DoubleSide, transparent: true, opacity: 0.8 })));
      group.add(rail);
    }
    // dessous de la piste, pour lui donner de l'épaisseur
    group.add(new THREE.Mesh(keepGeo(ribbon(track, hw, -hw, -0.6, -0.6, 14)), keepMat(new THREE.MeshBasicMaterial({ color: 0x2a1a6a }))));
  } else {
    const cols: [string, string] = theme === "north" ? ["#e5283a", "#ffffff"] : theme === "lake" ? ["#3d8fd8", "#ffffff"] : ["#2f9e63", "#e5283a"];
    const ctex = curbTexture(...cols);
    disposables.push(ctex);
    const cm = keepMat(new THREE.MeshLambertMaterial({ map: ctex }));
    for (const s of [-1, 1]) group.add(new THREE.Mesh(keepGeo(ribbon(track, s * hw, s * (hw + 1.1), 0.03, 0.12, 3)), cm));
    // talus de neige au bord du bas-côté (là où il ne mord pas sur un autre tronçon)
    const bankOff = hw + 11;
    const bm = keepMat(new THREE.MeshLambertMaterial({ color: theme === "village" ? 0xb4c2ee : 0xffffff, emissive: theme === "village" ? 0x39437a : theme === "lake" ? 0x8a7088 : 0x8a9ab8, flatShading: true, side: THREE.DoubleSide }));
    for (const s of [-1, 1]) {
      const ok = (i: number) => { const p = track.samples[i]; return distanceToTrack(track, p.x + p.nx * s * (bankOff + 1.5), p.z + p.nz * s * (bankOff + 1.5)) > bankOff - 0.5; };
      group.add(new THREE.Mesh(keepGeo(ribbon(track, s * bankOff, s * (bankOff + 1.6), 0, 2.2, 6, ok)), bm));
      group.add(new THREE.Mesh(keepGeo(ribbon(track, s * (bankOff + 1.6), s * (bankOff + 5), 2.2, 0, 6, ok)), bm));
    }
  }
  // damier de départ + arche
  const s0 = track.samples[0];
  const checker = canvasTexture(128, 32, (c) => { for (let y = 0; y < 2; y++) for (let x = 0; x < 8; x++) { c.fillStyle = (x + y) % 2 ? "#fff" : "#16161f"; c.fillRect(x * 16, y * 16, 16, 16); } }, false);
  disposables.push(checker);
  const line = new THREE.Mesh(keepGeo(new THREE.PlaneGeometry(hw * 2, 2.2)), keepMat(new THREE.MeshBasicMaterial({ map: checker })));
  line.rotation.set(-Math.PI / 2, 0, -Math.atan2(s0.tz, s0.tx) + Math.PI / 2, "YXZ");
  line.rotation.set(-Math.PI / 2, 0, 0);
  const lineHolder = new THREE.Group();
  lineHolder.add(line);
  lineHolder.position.set(s0.x, 0.05, s0.z);
  lineHolder.rotation.y = -Math.atan2(s0.tz, s0.tx) + Math.PI / 2;
  group.add(lineHolder);
  const banner = canvasTexture(512, 96, (c) => {
    c.fillStyle = "#c4142f"; c.fillRect(0, 0, 512, 96);
    for (let x = 0; x < 512; x += 24) { c.fillStyle = (x / 24) % 2 ? "#fff" : "#16161f"; c.fillRect(x, 0, 24, 12); c.fillRect(x + 12, 84, 24, 12); }
    c.fillStyle = "#fff"; c.font = "900 50px Fredoka, 'Trebuchet MS', sans-serif"; c.textAlign = "center"; c.textBaseline = "middle";
    c.fillText("★ DÉPART ★", 256, 50);
  }, false);
  disposables.push(banner);
  const arch = new THREE.Group();
  const postGeo = keepGeo(merge([part(cyl(0.35, 0.4, 7, 8), 0xffffff, [0, 3.5, 0]), part(cyl(0.37, 0.42, 0.8, 8), 0xe5283a, [0, 1.6, 0]), part(cyl(0.37, 0.4, 0.8, 8), 0xe5283a, [0, 3.6, 0]), part(cyl(0.36, 0.38, 0.8, 8), 0xe5283a, [0, 5.6, 0]), part(ball(0.6, 8), 0xffd23a, [0, 7.3, 0])]));
  for (const s of [-1, 1]) { const p = new THREE.Mesh(postGeo, fm); p.position.z = s * (hw + 1.6); arch.add(p); }
  const bannerMesh = new THREE.Mesh(keepGeo(new THREE.BoxGeometry(0.3, 1.8, hw * 2 + 3.2)), [
    keepMat(new THREE.MeshBasicMaterial({ map: banner, fog: false })), keepMat(new THREE.MeshBasicMaterial({ map: banner, fog: false })),
    keepMat(new THREE.MeshBasicMaterial({ color: 0xc4142f })), keepMat(new THREE.MeshBasicMaterial({ color: 0xc4142f })),
    keepMat(new THREE.MeshBasicMaterial({ color: 0xc4142f })), keepMat(new THREE.MeshBasicMaterial({ color: 0xc4142f })),
  ]);
  bannerMesh.position.y = 6.2;
  arch.add(bannerMesh);
  arch.position.set(s0.x, theme === "stars" ? 0 : 0, s0.z);
  arch.rotation.y = -Math.atan2(s0.tz, s0.tx);
  group.add(arch);

  // ---------- décors
  const spots = (every: number, minOff: number, range: number, chance: number): Placement[] => {
    const out: Placement[] = [];
    for (let i = 4; i < track.samples.length; i += every) for (const side of [-1, 1]) {
      if (r() > chance) continue;
      const p = track.samples[i], off = hw + minOff + r() * range;
      const x = p.x + p.nx * side * off + (r() - 0.5) * 3, z = p.z + p.nz * side * off + (r() - 0.5) * 3;
      if (distanceToTrack(track, x, z) < hw + minOff - 1) continue;
      out.push({ x, z, rot: r() * Math.PI * 2, scale: 0.8 + r() * 0.6 });
    }
    return out;
  };
  const add = (geo: THREE.BufferGeometry, list: Placement[], y = 0, mat: THREE.Material = fm) => { if (list.length) group.add(instanced(keepGeo(geo), mat, list, y)); };

  const pineGeo = () => merge([
    part(cyl(0.35, 0.45, 1.6, 6), 0x5a3a22, [0, 0.8, 0]),
    part(cone(2.4, 3.2, 7), theme === "village" ? 0x17553e : 0x1f7a52, [0, 2.8, 0]),
    part(cone(1.9, 2.8, 7), theme === "village" ? 0x1c6348 : 0x27905f, [0, 4.5, 0]),
    part(cone(1.3, 2.4, 7), theme === "village" ? 0x1f6e50 : 0x2fa56e, [0, 6.0, 0]),
    part(cone(1.32, 0.9, 7), 0xffffff, [0, 6.8, 0]),
    part(cone(2.0, 0.5, 7), 0xf2f7ff, [0, 5.2, 0]),
    part(cone(2.5, 0.5, 7), 0xf2f7ff, [0, 3.7, 0]),
  ]);
  const giftsGeo = () => merge([
    part(box(1.4, 1.4, 1.4), 0xe5283a, [0, 0.7, 0]), part(box(1.45, 0.25, 0.3), 0xffd23a, [0, 0.9, 0]), part(box(0.3, 0.25, 1.45), 0xffd23a, [0, 0.9, 0]),
    part(box(1.0, 1.0, 1.0), 0x2fbf71, [1.3, 0.5, 0.4], [0, 0.5, 0]), part(box(1.05, 0.2, 0.25), 0xffffff, [1.3, 0.7, 0.4], [0, 0.5, 0]),
    part(box(0.8, 0.8, 0.8), 0x4a9cff, [0.2, 1.8, 0.1], [0, 0.3, 0]), part(box(0.85, 0.16, 0.2), 0xff8ad8, [0.2, 1.95, 0.1], [0, 0.3, 0]),
  ]);
  const candyGeo = () => {
    const P: THREE.BufferGeometry[] = [];
    for (let i = 0; i < 8; i++) P.push(part(cyl(0.28, 0.28, 0.5, 8), i % 2 ? 0xe5283a : 0xffffff, [0, 0.25 + i * 0.5, 0]));
    for (let i = 0; i < 6; i++) { const a = (i / 5) * Math.PI; P.push(part(cyl(0.28, 0.28, 0.5, 8), i % 2 ? 0xffffff : 0xe5283a, [-0.8 + Math.cos(a) * 0.8, 4.0 + Math.sin(a) * 0.8, 0], [0, 0, a + Math.PI / 2])); }
    return merge(P);
  };

  if (theme === "north") {
    add(pineGeo(), spots(3, 14, 42, 0.5));
    add(merge([
      part(ball(1.3, 8), 0xffffff, [0, 1.1, 0]), part(ball(0.95, 8), 0xffffff, [0, 2.8, 0]), part(ball(0.68, 8), 0xffffff, [0, 4.0, 0]),
      part(cone(0.14, 0.7, 6), 0xff8a2a, [0.75, 4.0, 0], [0, 0, -Math.PI / 2]), part(cyl(0.5, 0.5, 0.7, 8), 0x1c1c26, [0, 4.9, 0]), part(cyl(0.8, 0.8, 0.1, 8), 0x1c1c26, [0, 4.55, 0]),
      part(cyl(0.72, 0.72, 0.25, 8), 0xe5283a, [0, 3.45, 0]), part(ball(0.1, 5), 0x1c1c26, [0.6, 4.2, 0.22]), part(ball(0.1, 5), 0x1c1c26, [0.6, 4.2, -0.22]),
    ]), spots(22, 13, 6, 0.6).map((p) => ({ ...p, scale: 0.9 })));
    add(candyGeo(), spots(16, 12.5, 2, 0.7));
    add(giftsGeo(), spots(26, 13, 10, 0.6));
  } else if (theme === "lake") {
    add(pineGeo(), spots(7, 22, 40, 0.5));
    // pics de glace et igloos
    add(merge([
      part(cone(1.1, 5, 5), 0xa8e4ff, [0, 2.5, 0]), part(cone(0.7, 3.2, 5), 0xd8f4ff, [1.1, 1.6, 0.4], [0.1, 0, -0.2]), part(cone(0.6, 2.4, 5), 0x7cc8f2, [-0.9, 1.2, -0.5], [-0.1, 0, 0.25]),
    ]), spots(5, 13, 14, 0.5));
    add(merge([
      part(new THREE.SphereGeometry(3, 10, 6, 0, Math.PI * 2, 0, Math.PI / 2), 0xf2f8ff, [0, 0, 0]),
      part(box(1.6, 1.5, 1.8), 0xdcebff, [2.6, 0.75, 0]), part(box(0.2, 1.1, 1.1), 0x2a3a5a, [3.42, 0.6, 0]),
    ]), spots(34, 18, 16, 0.8).map((p) => ({ ...p, scale: 1 })));
    add(giftsGeo(), spots(30, 13, 6, 0.5));
  } else if (theme === "village") {
    add(pineGeo(), spots(5, 16, 36, 0.45));
    add(candyGeo(), spots(20, 12.5, 2, 0.6));
    // maisons aux fenêtres allumées
    const houses = spots(11, 20, 16, 0.75).map((p) => ({ ...p, scale: 1 + r() * 0.3 }));
    houses.forEach((p) => { const s = track.samples[nearest(track, p.x, p.z)]; p.rot = -Math.atan2(s.z - p.z, s.x - p.x); });
    add(merge([
      part(box(6, 4.5, 7), 0x7a4a5e, [0, 2.25, 0]),
      part(new THREE.CylinderGeometry(0.01, 5.6, 3.4, 4, 1), 0x8e2f3c, [0, 6.2, 0], [0, Math.PI / 4, 0], [1, 1, 1.15]),
      part(new THREE.CylinderGeometry(0.01, 5.9, 0.7, 4, 1), 0xf4f7ff, [0, 5.0, 0], [0, Math.PI / 4, 0], [1, 1, 1.15]),
      part(box(1, 2.4, 1), 0x6e2a2a, [-1.6, 6.6, 1.6]), part(box(1.2, 0.3, 1.2), 0xf4f7ff, [-1.6, 7.9, 1.6]),
      part(box(0.2, 2.6, 1.4), 0x4a2418, [3.05, 1.3, 0]),
    ]), houses);
    const glow = keepMat(new THREE.MeshBasicMaterial({ color: 0xffd27a, fog: false }));
    add(merge([part(box(0.15, 1.3, 1.3), 0xffffff, [3.06, 2.9, 2.1]), part(box(0.15, 1.3, 1.3), 0xffffff, [3.06, 2.9, -2.1]), part(box(1.3, 1.3, 0.15), 0xffffff, [0, 2.6, 3.56]), part(box(1.3, 1.3, 0.15), 0xffffff, [0, 2.6, -3.56])]), houses, 0, glow);
    // réverbères
    const lamps = spots(9, 2.4, 0.4, 0.8).map((p) => ({ ...p, scale: 1 }));
    add(merge([part(cyl(0.12, 0.16, 5, 6), 0x1c1e30, [0, 2.5, 0]), part(box(0.7, 0.12, 0.7), 0x1c1e30, [0, 5.0, 0]), part(cone(0.5, 0.4, 4), 0xf4f7ff, [0, 5.95, 0])]), lamps);
    add(merge([part(box(0.5, 0.7, 0.5), 0xffffff, [0, 5.45, 0])]), lamps, 0, keepMat(new THREE.MeshBasicMaterial({ color: 0xffe2a0, fog: false })));
    // halos lumineux au sol
    const halo = canvasTexture(64, 64, (c) => { const g = c.createRadialGradient(32, 32, 2, 32, 32, 32); g.addColorStop(0, "rgba(255,215,140,0.55)"); g.addColorStop(1, "rgba(255,215,140,0)"); c.fillStyle = g; c.fillRect(0, 0, 64, 64); }, false);
    disposables.push(halo);
    const haloGeo = new THREE.PlaneGeometry(13, 13); haloGeo.rotateX(-Math.PI / 2);
    add(haloGeo, lamps, 0.09, keepMat(new THREE.MeshBasicMaterial({ map: halo, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending })));
    add(giftsGeo(), spots(24, 13, 4, 0.5));
  } else {
    // boules de Noël géantes et étoiles qui flottent
    const baubleMat = keepMat(new THREE.MeshLambertMaterial({ vertexColors: true, flatShading: true, emissive: 0x332255 }));
    for (const col of [0xff4d6a, 0x4de1ff, 0xffd86b, 0x9a7bff]) {
      const list = spots(17, 12, 46, 0.35).map((p) => ({ ...p, scale: 1.6 + r() * 3 }));
      const geo = keepGeo(merge([part(ball(1, 10), col, [0, 0, 0]), part(cyl(0.28, 0.28, 0.35, 8), 0xffe9a0, [0, 1.05, 0]), part(new THREE.TorusGeometry(0.2, 0.05, 4, 8), 0xffe9a0, [0, 1.35, 0])]));
      list.forEach((p) => {
        const m = new THREE.Mesh(geo, baubleMat);
        m.position.set(p.x, -6 + r() * 22, p.z); m.scale.setScalar(p.scale);
        group.add(m);
        animated.push({ mesh: m, kind: "bob", phase: r() * 6 });
      });
    }
    const starShape = new THREE.Shape();
    for (let i = 0; i < 10; i++) { const a = (i / 10) * Math.PI * 2 + Math.PI / 2, rr = i % 2 ? 0.45 : 1; if (i) starShape.lineTo(Math.cos(a) * rr, Math.sin(a) * rr); else starShape.moveTo(Math.cos(a) * rr, Math.sin(a) * rr); }
    const starGeo = keepGeo(new THREE.ExtrudeGeometry(starShape, { depth: 0.3, bevelEnabled: false }));
    const starMat = keepMat(new THREE.MeshBasicMaterial({ color: 0xffe36a, fog: false }));
    for (const p of spots(9, 9, 30, 0.45)) {
      const m = new THREE.Mesh(starGeo, starMat);
      m.position.set(p.x, 2 + r() * 16, p.z); m.scale.setScalar(0.8 + r() * 1.6);
      group.add(m);
      animated.push({ mesh: m, kind: "spin", phase: r() * 6 });
    }
  }

  return { group, sky, roadTex, animated, dispose() { disposables.forEach((d) => d.dispose()); } };
}

function nearest(track: Track, x: number, z: number) {
  let best = 0, bd = Infinity;
  track.samples.forEach((s, i) => { const d = (s.x - x) ** 2 + (s.z - z) ** 2; if (d < bd) { bd = d; best = i; } });
  return best;
}

// ------------------------------------------------------------------ objets de course

export function makeItemBoxMesh() {
  const tex = giftTexture();
  const m = new THREE.Mesh(new THREE.BoxGeometry(1.7, 1.7, 1.7), new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: 0.93 }));
  return m;
}

export function makeSnowballMesh() {
  return new THREE.Mesh(new THREE.IcosahedronGeometry(0.75, 1), new THREE.MeshLambertMaterial({ color: 0xffffff, flatShading: true, emissive: 0x6688aa }));
}

export function makeIceMesh() {
  const g = merge([
    part(cyl(1.7, 1.9, 0.12, 10), 0x9fe2ff, [0, 0.07, 0]),
    part(cone(0.35, 1.3, 5), 0xd8f6ff, [0.3, 0.7, 0.2]), part(cone(0.28, 0.9, 5), 0xa8e4ff, [-0.6, 0.5, -0.3], [0.2, 0, 0.2]), part(cone(0.25, 0.8, 5), 0xc8f0ff, [0.2, 0.45, -0.7], [-0.2, 0, -0.2]),
  ]);
  return new THREE.Mesh(g, new THREE.MeshLambertMaterial({ vertexColors: true, flatShading: true, emissive: 0x114466 }));
}
