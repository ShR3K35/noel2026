// Sons du jeu de kart, synthétisés en direct (Web Audio) : moteur, bruitages et musique.
// Musique : « Deck the Halls » (air traditionnel gallois, domaine public), arrangé ici.

type Note = [number | null, number];

const C4 = 60, D4 = 62, E4 = 64, F4 = 65, Fs4 = 66, G4 = 67, A4 = 69, B4 = 71, C5 = 72, B3 = 59;
const A: Note[] = [[G4, 1.5], [F4, 0.5], [E4, 1], [D4, 1], [C4, 1], [D4, 1], [E4, 1], [C4, 1], [D4, 0.5], [E4, 0.5], [F4, 0.5], [D4, 0.5], [E4, 1.5], [D4, 0.5], [C4, 1], [B3, 1], [C4, 2]];
const B: Note[] = [[D4, 1.5], [E4, 0.5], [F4, 1], [D4, 1], [E4, 1.5], [F4, 0.5], [G4, 1], [D4, 1], [E4, 0.5], [Fs4, 0.5], [G4, 1], [A4, 0.5], [B4, 0.5], [C5, 1], [B4, 1], [A4, 1], [G4, 2]];
const D: Note[] = [[G4, 1.5], [F4, 0.5], [E4, 1], [D4, 1], [C4, 1], [D4, 1], [E4, 1], [C4, 1], [A4, 0.5], [A4, 0.5], [A4, 0.5], [A4, 0.5], [G4, 1.5], [F4, 0.5], [E4, 1], [D4, 1], [C4, 2]];
const SONG: Note[] = [...A, ...A, ...B, ...D];
const BASS = [48, 48, 43, 48, 48, 48, 43, 48, 43, 48, 43, 43, 48, 48, 41, 48]; // une note par mesure

const mtof = (m: number) => 440 * Math.pow(2, (m - 69) / 12);

export class KartSound {
  private ctx: AudioContext | null = null;
  private sfx!: GainNode;
  private music!: GainNode;
  private noiseBuf!: AudioBuffer;
  private engine: { osc: OscillatorNode; osc2: OscillatorNode; gain: GainNode; filter: BiquadFilterNode } | null = null;
  private timer: number | null = null;
  private next = 0;
  private index = 0;
  private beat = 0;
  playing = false;

  constructor(public musicOn: boolean) {}

  unlock() {
    if (!this.ctx) {
      const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return;
      const ctx = (this.ctx = new Ctor());
      const comp = ctx.createDynamicsCompressor();
      comp.connect(ctx.destination);
      this.sfx = ctx.createGain(); this.sfx.gain.value = 0.4; this.sfx.connect(comp);
      this.music = ctx.createGain(); this.music.gain.value = this.musicOn ? 0.16 : 0; this.music.connect(comp);
      this.noiseBuf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate);
      const d = this.noiseBuf.getChannelData(0);
      for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
      // moteur : deux oscillateurs légèrement désaccordés dans un filtre
      const osc = ctx.createOscillator(); osc.type = "sawtooth";
      const osc2 = ctx.createOscillator(); osc2.type = "square";
      const filter = ctx.createBiquadFilter(); filter.type = "lowpass"; filter.frequency.value = 500;
      const gain = ctx.createGain(); gain.gain.value = 0;
      osc.connect(filter); osc2.connect(filter); filter.connect(gain).connect(this.sfx);
      osc.start(); osc2.start();
      this.engine = { osc, osc2, gain, filter };
      this.timer = window.setInterval(() => this.schedule(), 50);
    }
    if (this.ctx.state === "suspended") void this.ctx.resume();
  }

  setMusic(on: boolean) {
    this.musicOn = on;
    if (this.ctx) this.music.gain.setTargetAtTime(on ? 0.16 : 0, this.ctx.currentTime, 0.1);
  }

  /** Régime moteur : vitesse normalisée 0–1,4 ; `on` coupe le moteur dans les menus. */
  setEngine(speed: number, on: boolean, boost: boolean) {
    const e = this.engine, ctx = this.ctx;
    if (!e || !ctx) return;
    const f = 52 + Math.abs(speed) * 120 + (boost ? 30 : 0);
    e.osc.frequency.setTargetAtTime(f, ctx.currentTime, 0.05);
    e.osc2.frequency.setTargetAtTime(f * 0.502, ctx.currentTime, 0.05);
    e.filter.frequency.setTargetAtTime(380 + Math.abs(speed) * 1300, ctx.currentTime, 0.05);
    e.gain.gain.setTargetAtTime(on ? 0.09 + Math.abs(speed) * 0.07 : 0, ctx.currentTime, 0.08);
  }

  suspend() { if (this.ctx?.state === "running") void this.ctx.suspend(); }
  resume() { if (this.ctx?.state === "suspended") void this.ctx.resume(); }

  dispose() {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    if (this.ctx) void this.ctx.close();
    this.ctx = null; this.engine = null;
  }

  private schedule() {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== "running") return;
    const beat = 60 / (this.playing ? 172 : 132);
    if (this.next < ctx.currentTime) this.next = ctx.currentTime + 0.05;
    while (this.next < ctx.currentTime + 0.25) {
      const [note, dur] = SONG[this.index];
      const t = this.next;
      if (note !== null) this.bell(mtof(note + 12), t, dur * beat, 0.15, this.music);
      for (let b = Math.ceil(this.beat - 1e-6); b < this.beat + dur - 1e-6; b++) {
        const bt = t + (b - this.beat) * beat;
        const root = BASS[Math.floor(b / 4) % BASS.length];
        if (b % 2 === 0) this.pluck(mtof(root), bt, beat * 1.2, 0.24);
        else { this.pluck(mtof(root + 12), bt, beat * 0.5, 0.09); this.pluck(mtof(root + 19), bt, beat * 0.5, 0.07); }
        this.tick(bt, b % 2 ? 0.05 : 0.025); this.tick(bt + beat / 2, 0.02);
      }
      this.beat += dur;
      this.next += dur * beat;
      this.index = (this.index + 1) % SONG.length;
      if (this.index === 0) this.beat = 0;
    }
  }

  private bell(freq: number, t: number, dur: number, vol: number, dest: AudioNode) {
    const ctx = this.ctx!;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.001, t + Math.max(0.22, dur * 1.2));
    g.connect(dest);
    for (const [mult, v] of [[1, 1], [2, 0.3], [3.01, 0.1]] as const) {
      const o = ctx.createOscillator(); o.frequency.value = freq * mult;
      const og = ctx.createGain(); og.gain.value = v;
      o.connect(og).connect(g); o.start(t); o.stop(t + Math.max(0.3, dur * 1.3));
    }
  }

  private pluck(freq: number, t: number, dur: number, vol: number) {
    const ctx = this.ctx!;
    const o = ctx.createOscillator(); o.type = "triangle"; o.frequency.value = freq;
    const g = ctx.createGain(); g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    o.connect(g).connect(this.music); o.start(t); o.stop(t + dur);
  }

  private tick(t: number, vol: number) {
    const ctx = this.ctx!;
    const n = ctx.createBufferSource(); n.buffer = this.noiseBuf;
    const f = ctx.createBiquadFilter(); f.type = "bandpass"; f.frequency.value = 7500; f.Q.value = 2.5;
    const g = ctx.createGain(); g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.001, t + 0.08);
    n.connect(f).connect(g).connect(this.music); n.start(t, Math.random() * 0.5); n.stop(t + 0.1);
  }

  private tone(type: OscillatorType, f0: number, f1: number, dur: number, vol: number, delay = 0) {
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime + delay;
    const o = ctx.createOscillator(); o.type = type;
    o.frequency.setValueAtTime(f0, t); o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t + dur);
    const g = ctx.createGain(); g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    o.connect(g).connect(this.sfx); o.start(t); o.stop(t + dur + 0.02);
  }

  private noise(dur: number, vol: number, freq: number, type: BiquadFilterType = "lowpass") {
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime;
    const n = ctx.createBufferSource(); n.buffer = this.noiseBuf;
    const f = ctx.createBiquadFilter(); f.type = type; f.frequency.value = freq;
    const g = ctx.createGain(); g.gain.setValueAtTime(vol, t); g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    n.connect(f).connect(g).connect(this.sfx); n.start(t, Math.random() * 0.5); n.stop(t + dur);
  }

  play(name: string) {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== "running") return;
    const now = ctx.currentTime;
    switch (name) {
      case "beep": this.tone("square", 440, 440, 0.18, 0.2); break;
      case "go": this.tone("square", 880, 880, 0.5, 0.22); break;
      case "pickup": [0, 1, 2, 3, 4, 5].forEach((i) => this.tone("square", 500 + (i % 3) * 180, 500 + (i % 3) * 180, 0.07, 0.07, i * 0.09)); break;
      case "item": this.bell(1568, now, 0.2, 0.2, this.sfx); this.bell(2093, now + 0.08, 0.3, 0.2, this.sfx); break;
      case "boost": case "driftBoost": this.noise(0.6, 0.4, 1800, "bandpass"); this.tone("sawtooth", 180, 720, 0.45, 0.16); break;
      case "throw": this.noise(0.14, 0.3, 2400, "bandpass"); this.tone("sine", 700, 300, 0.12, 0.1); break;
      case "hit": this.tone("sawtooth", 500, 90, 0.5, 0.2); this.noise(0.3, 0.4, 900); break;
      case "bump": this.tone("triangle", 160, 70, 0.12, 0.4); break;
      case "fall": this.tone("sine", 900, 120, 0.9, 0.22); break;
      case "lap": [784, 1047, 1319].forEach((f, i) => this.bell(f, now + i * 0.09, 0.3, 0.2, this.sfx)); break;
      case "finish": [523, 659, 784, 1047, 784, 1047, 1319, 1568].forEach((f, i) => this.bell(f, now + i * 0.12, 0.4, 0.22, this.sfx)); break;
      case "ui": this.bell(1175, now, 0.15, 0.12, this.sfx); break;
    }
  }
}
