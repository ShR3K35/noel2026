// Clavier, manette et écran tactile réunis en un seul état (skill `input-systems` : actions, pas touches).
// Le tactile est multipoint : on recalcule à chaque mouvement quels boutons virtuels sont sous un doigt,
// ce qui permet de glisser de ← à → sans relever le doigt.

import type { Input } from "./world";

export interface Rect { x: number; y: number; w: number; h: number }
export interface TouchButton extends Rect { id: "jump" | "action"; r: number; cx: number; cy: number }

/** Joystick tactile : il apparaît là où le pouce se pose dans sa zone (moitié gauche de l'écran). */
export interface Joystick {
  zone: Rect;
  homeX: number; homeY: number; radius: number;
  pointer: number | null;
  baseX: number; baseY: number; knobX: number; knobY: number;
}

export const JOY_DEADZONE = 0.18;

export class Controls {
  private keys = new Set<string>();
  private pointers = new Map<number, { x: number; y: number; type: string }>();
  touchButtons: TouchButton[] = [];
  joy: Joystick = { zone: { x: 0, y: 0, w: 0, h: 0 }, homeX: 0, homeY: 0, radius: 50, pointer: null, baseX: 0, baseY: 0, knobX: 0, knobY: 0 };
  /** Le joystick ne s'active qu'en jeu (pas dans les menus). */
  joyEnabled = false;
  /** Vrai dès qu'un doigt a touché l'écran : on affiche alors les boutons tactiles. */
  touchSeen = false;
  private taps: { x: number; y: number }[] = [];
  private pressedKeys: string[] = [];
  private cleanup: (() => void)[] = [];

  constructor(private el: HTMLElement, onGesture: () => void) {
    const opts = { passive: false } as AddEventListenerOptions;
    const pos = (e: PointerEvent) => {
      const r = el.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top, type: e.pointerType };
    };
    const down = (e: PointerEvent) => {
      e.preventDefault();
      onGesture();
      if (e.pointerType === "touch") this.touchSeen = true;
      const p = pos(e);
      this.pointers.set(e.pointerId, p);
      this.taps.push(p);
      const j = this.joy, z = j.zone;
      if (this.joyEnabled && p.type !== "mouse" && j.pointer === null && p.x >= z.x && p.x <= z.x + z.w && p.y >= z.y && p.y <= z.y + z.h) {
        j.pointer = e.pointerId;
        // la base se pose sous le pouce, sans sortir de l'écran
        j.baseX = Math.max(j.radius + 8, Math.min(z.x + z.w - j.radius, p.x));
        j.baseY = Math.max(z.y + j.radius, Math.min(z.y + z.h - j.radius - 8, p.y));
        this.moveKnob(p.x, p.y);
      }
      try { el.setPointerCapture(e.pointerId); } catch { /* ignoré */ }
    };
    const move = (e: PointerEvent) => {
      if (!this.pointers.has(e.pointerId)) return;
      const p = pos(e);
      this.pointers.set(e.pointerId, p);
      if (e.pointerId === this.joy.pointer) this.moveKnob(p.x, p.y);
    };
    const up = (e: PointerEvent) => {
      this.pointers.delete(e.pointerId);
      if (e.pointerId === this.joy.pointer) this.releaseJoy();
    };
    const key = (e: KeyboardEvent, isDown: boolean) => {
      const k = e.code;
      if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Space"].includes(k)) e.preventDefault();
      if (isDown) {
        onGesture();
        if (!this.keys.has(k)) this.pressedKeys.push(k);
        this.keys.add(k);
      } else this.keys.delete(k);
    };
    const kd = (e: KeyboardEvent) => key(e, true);
    const ku = (e: KeyboardEvent) => key(e, false);
    const blur = () => { this.keys.clear(); this.pointers.clear(); this.releaseJoy(); };
    const noMenu = (e: Event) => e.preventDefault();
    el.addEventListener("pointerdown", down, opts);
    el.addEventListener("pointermove", move, opts);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    el.addEventListener("contextmenu", noMenu);
    window.addEventListener("keydown", kd);
    window.addEventListener("keyup", ku);
    window.addEventListener("blur", blur);
    this.cleanup.push(() => {
      el.removeEventListener("pointerdown", down);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
      el.removeEventListener("contextmenu", noMenu);
      window.removeEventListener("keydown", kd);
      window.removeEventListener("keyup", ku);
      window.removeEventListener("blur", blur);
    });
  }

  dispose() { this.cleanup.forEach((f) => f()); }

  private moveKnob(x: number, y: number) {
    const j = this.joy;
    let dx = x - j.baseX, dy = y - j.baseY;
    const d = Math.hypot(dx, dy);
    if (d > j.radius) {
      // le pouce dépasse : la base le suit, comme sur les jeux mobiles
      const over = d - j.radius;
      j.baseX += (dx / d) * over; j.baseY += (dy / d) * over;
      dx = x - j.baseX; dy = y - j.baseY;
    }
    j.knobX = j.baseX + dx; j.knobY = j.baseY + dy;
  }

  releaseJoy() {
    const j = this.joy;
    j.pointer = null;
    j.baseX = j.knobX = j.homeX;
    j.baseY = j.knobY = j.homeY;
  }

  /** Axe horizontal du joystick, -1 à 1, zone morte retirée puis remise à l'échelle (skill input-systems). */
  joyAxis() {
    const j = this.joy;
    if (j.pointer === null) return 0;
    return deadzone((j.knobX - j.baseX) / j.radius);
  }

  /** Appuis brefs depuis le dernier appel (pour les menus et boutons d'interface). */
  takeTaps() { const t = this.taps; this.taps = []; return t; }
  takeKeys() { const k = this.pressedKeys; this.pressedKeys = []; return k; }

  heldButton(id: TouchButton["id"]) {
    const b = this.touchButtons.find((t) => t.id === id);
    if (!b) return false;
    for (const [id, p] of this.pointers) {
      if (p.type === "mouse" || id === this.joy.pointer) continue;
      const dx = p.x - b.cx, dy = p.y - b.cy;
      if (dx * dx + dy * dy <= (b.r * 1.35) ** 2) return true;
    }
    return false;
  }

  read(): Input {
    const k = this.keys;
    const pad = navigator.getGamepads?.().find((g) => g && g.connected) ?? null;
    const stick = pad ? deadzone(pad.axes[0] ?? 0) : 0;
    const btn = (i: number) => !!pad?.buttons[i]?.pressed;
    const joy = this.joyAxis();
    return {
      left: k.has("ArrowLeft") || k.has("KeyA") || k.has("KeyQ") || btn(14),
      right: k.has("ArrowRight") || k.has("KeyD") || btn(15),
      axis: joy !== 0 ? joy : stick,
      jump: k.has("Space") || k.has("ArrowUp") || k.has("KeyW") || k.has("KeyZ") || btn(0) || this.heldButton("jump"),
      action: k.has("KeyX") || k.has("KeyJ") || k.has("ShiftLeft") || k.has("ShiftRight") || btn(2) || btn(1) || this.heldButton("action"),
    };
  }

  /** Bouton Start / Échap. */
  pausePressed(keys: string[]) {
    return keys.includes("Escape") || keys.includes("KeyP");
  }
}

export function deadzone(v: number) {
  const a = Math.abs(v);
  if (a <= JOY_DEADZONE) return 0;
  return Math.sign(v) * Math.min(1, (a - JOY_DEADZONE) / (1 - JOY_DEADZONE));
}
