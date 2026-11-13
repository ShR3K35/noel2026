// Clavier, manette et écran tactile réunis en un seul état (skill `input-systems` : actions, pas touches).
// Le tactile est multipoint : on recalcule à chaque mouvement quels boutons virtuels sont sous un doigt,
// ce qui permet de glisser de ← à → sans relever le doigt.

import type { Input } from "./world";

export interface Rect { x: number; y: number; w: number; h: number }
export interface TouchButton extends Rect { id: "left" | "right" | "jump" | "action"; r: number; cx: number; cy: number }

export class Controls {
  private keys = new Set<string>();
  private pointers = new Map<number, { x: number; y: number; type: string }>();
  touchButtons: TouchButton[] = [];
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
      try { el.setPointerCapture(e.pointerId); } catch { /* ignoré */ }
    };
    const move = (e: PointerEvent) => { if (this.pointers.has(e.pointerId)) this.pointers.set(e.pointerId, pos(e)); };
    const up = (e: PointerEvent) => { this.pointers.delete(e.pointerId); };
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
    const blur = () => { this.keys.clear(); this.pointers.clear(); };
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

  /** Appuis brefs depuis le dernier appel (pour les menus et boutons d'interface). */
  takeTaps() { const t = this.taps; this.taps = []; return t; }
  takeKeys() { const k = this.pressedKeys; this.pressedKeys = []; return k; }

  heldButton(id: TouchButton["id"]) {
    const b = this.touchButtons.find((t) => t.id === id);
    if (!b) return false;
    for (const p of this.pointers.values()) {
      if (p.type === "mouse") continue;
      const dx = p.x - b.cx, dy = p.y - b.cy;
      if (b.id === "left" || b.id === "right") {
        // zones larges : moitié gauche du bloc directionnel = gauche, moitié droite = droite
        if (p.x >= b.x && p.x <= b.x + b.w && p.y >= b.y && p.y <= b.y + b.h) return true;
      } else if (dx * dx + dy * dy <= (b.r * 1.35) ** 2) return true;
    }
    return false;
  }

  read(): Input {
    const k = this.keys;
    const pad = navigator.getGamepads?.().find((g) => g && g.connected) ?? null;
    const axis = pad ? pad.axes[0] ?? 0 : 0;
    const btn = (i: number) => !!pad?.buttons[i]?.pressed;
    return {
      left: k.has("ArrowLeft") || k.has("KeyA") || k.has("KeyQ") || axis < -0.35 || btn(14) || this.heldButton("left"),
      right: k.has("ArrowRight") || k.has("KeyD") || axis > 0.35 || btn(15) || this.heldButton("right"),
      jump: k.has("Space") || k.has("ArrowUp") || k.has("KeyW") || k.has("KeyZ") || btn(0) || this.heldButton("jump"),
      action: k.has("KeyX") || k.has("KeyJ") || k.has("ShiftLeft") || k.has("ShiftRight") || btn(2) || btn(1) || this.heldButton("action"),
    };
  }

  /** Bouton Start / Échap. */
  pausePressed(keys: string[]) {
    return keys.includes("Escape") || keys.includes("KeyP");
  }
}
