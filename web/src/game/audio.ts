// Sons synthétisés en direct (Web Audio) : rien à télécharger.
// Musique : « Jingle Bells » (J. Pierpont, 1857, domaine public), refrain arrangé ici.

type Note = [number | null, number]; // [note MIDI ou silence, durée en temps]

const E = 64, F = 65, G = 67, C = 60, D = 62, A = 69, Cc = 72;
const CHORUS: Note[] = [
  [E, 1], [E, 1], [E, 2], [E, 1], [E, 1], [E, 2], [E, 1], [G, 1], [C, 1.5], [D, 0.5], [E, 4],
  [F, 1], [F, 1], [F, 1.5], [F, 0.5], [F, 1], [E, 1], [E, 1], [E, 0.5], [E, 0.5],
  [E, 1], [D, 1], [D, 1], [E, 1], [D, 2], [G, 2],
  [E, 1], [E, 1], [E, 2], [E, 1], [E, 1], [E, 2], [E, 1], [G, 1], [C, 1.5], [D, 0.5], [E, 4],
  [F, 1], [F, 1], [F, 1.5], [F, 0.5], [F, 1], [E, 1], [E, 1], [E, 0.5], [E, 0.5],
  [G, 1], [G, 1], [F, 1], [D, 1], [C, 3], [null, 1],
];
// accords, une mesure (4 temps) chacun
const CHORDS = ["C", "C", "C", "C", "F", "C", "D", "G", "C", "C", "C", "C", "F", "C", "G", "C"];
const CHORD_NOTES: Record<string, number[]> = { C: [48, 55, 64], F: [41, 53, 57], G: [43, 50, 59], D: [38, 54, 57] };
void A; void Cc;

const mtof = (m: number) => 440 * Math.pow(2, (m - 69) / 12);

export class Sound {
  private ctx: AudioContext | null = null;
  private master!: GainNode;
  private sfx!: GainNode;
  private music!: GainNode;
  private noiseBuf!: AudioBuffer;
  private nextNoteTime = 0;
  private noteIndex = 0;
  private beatInSong = 0;
  private timer: number | null = null;
  musicOn = true;
  private tempo = 1;
  private transpose = 0;

  constructor(musicOn: boolean) { this.musicOn = musicOn; }

  /** À appeler dans un geste de l'utilisateur (toucher, touche) : les navigateurs l'exigent. */
  unlock() {
    if (!this.ctx) {
      const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return;
      this.ctx = new Ctor();
      this.master = this.ctx.createGain();
      this.master.gain.value = 0.9;
      const comp = this.ctx.createDynamicsCompressor();
      this.master.connect(comp).connect(this.ctx.destination);
      this.sfx = this.ctx.createGain(); this.sfx.gain.value = 0.42; this.sfx.connect(this.master);
      this.music = this.ctx.createGain(); this.music.gain.value = this.musicOn ? 0.2 : 0; this.music.connect(this.master);
      const len = this.ctx.sampleRate;
      this.noiseBuf = this.ctx.createBuffer(1, len, this.ctx.sampleRate);
      const d = this.noiseBuf.getChannelData(0);
      for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
      this.timer = window.setInterval(() => this.schedule(), 50);
    }
    if (this.ctx.state === "suspended") void this.ctx.resume();
  }

  setMusic(on: boolean) {
    this.musicOn = on;
    if (this.ctx) this.music.gain.setTargetAtTime(on ? 0.2 : 0, this.ctx.currentTime, 0.1);
  }

  /** Change l'ambiance du morceau selon le niveau. */
  setMood(level: number) {
    this.tempo = [1, 0.94, 1.08][level] ?? 1;
    this.transpose = [0, -3, 2][level] ?? 0;
  }

  suspend() { if (this.ctx && this.ctx.state === "running") void this.ctx.suspend(); }
  resume() { if (this.ctx && this.ctx.state === "suspended") void this.ctx.resume(); }

  dispose() {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    if (this.ctx) void this.ctx.close();
    this.ctx = null;
  }

  // ------------------------------------------------------------- musique

  private schedule() {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== "running") return;
    const beat = (60 / 150) / this.tempo;
    if (this.nextNoteTime < ctx.currentTime) this.nextNoteTime = ctx.currentTime + 0.05;
    while (this.nextNoteTime < ctx.currentTime + 0.25) {
      const [note, dur] = CHORUS[this.noteIndex];
      const t = this.nextNoteTime;
      if (note !== null) this.bellTone(mtof(note + 12 + this.transpose), t, dur * beat, 0.16, this.music);
      // accompagnement : sur chaque temps entier
      const startBeat = this.beatInSong;
      for (let b = Math.ceil(startBeat - 1e-6); b < startBeat + dur - 1e-6; b++) {
        const bt = t + (b - startBeat) * beat;
        const chord = CHORD_NOTES[CHORDS[Math.floor(b / 4) % CHORDS.length]];
        const beatInBar = b % 4;
        if (beatInBar === 0 || beatInBar === 2) this.pluck(mtof(chord[0] + this.transpose), bt, beat * 1.6, 0.22);
        else for (const n of chord.slice(1)) this.pad(mtof(n + this.transpose), bt, beat * 0.6, 0.045);
        // grelots du traîneau
        this.jingle(bt, beatInBar % 2 === 1 ? 0.05 : 0.025);
        this.jingle(bt + beat / 2, 0.02);
      }
      this.beatInSong += dur;
      this.nextNoteTime += dur * beat;
      this.noteIndex = (this.noteIndex + 1) % CHORUS.length;
      if (this.noteIndex === 0) this.beatInSong = 0;
    }
  }

  private bellTone(freq: number, t: number, dur: number, vol: number, dest: AudioNode) {
    const ctx = this.ctx!;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vol, t + 0.01);
    g.gain.exponentialRampToValueAtTime(0.001, t + Math.max(0.25, dur * 1.3));
    g.connect(dest);
    for (const [mult, v] of [[1, 1], [2, 0.35], [3.01, 0.12], [4.2, 0.06]] as const) {
      const o = ctx.createOscillator();
      o.type = "sine";
      o.frequency.value = freq * mult;
      const og = ctx.createGain(); og.gain.value = v;
      o.connect(og).connect(g);
      o.start(t); o.stop(t + Math.max(0.3, dur * 1.4));
    }
  }

  private pluck(freq: number, t: number, dur: number, vol: number) {
    const ctx = this.ctx!;
    const o = ctx.createOscillator(); o.type = "triangle"; o.frequency.value = freq;
    const g = ctx.createGain();
    g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    o.connect(g).connect(this.music);
    o.start(t); o.stop(t + dur);
  }

  private pad(freq: number, t: number, dur: number, vol: number) {
    const ctx = this.ctx!;
    const o = ctx.createOscillator(); o.type = "sawtooth"; o.frequency.value = freq;
    const f = ctx.createBiquadFilter(); f.type = "lowpass"; f.frequency.value = 1400;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(vol, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    o.connect(f).connect(g).connect(this.music);
    o.start(t); o.stop(t + dur);
  }

  private jingle(t: number, vol: number) {
    const ctx = this.ctx!;
    const n = ctx.createBufferSource(); n.buffer = this.noiseBuf;
    const f = ctx.createBiquadFilter(); f.type = "bandpass"; f.frequency.value = 7500; f.Q.value = 2.5;
    const g = ctx.createGain();
    g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + 0.09);
    n.connect(f).connect(g).connect(this.music);
    n.start(t, Math.random() * 0.5); n.stop(t + 0.1);
  }

  // ------------------------------------------------------------- bruitages

  private tone(type: OscillatorType, f0: number, f1: number, dur: number, vol: number, delay = 0) {
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime + delay;
    const o = ctx.createOscillator(); o.type = type;
    o.frequency.setValueAtTime(f0, t);
    o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t + dur);
    const g = ctx.createGain();
    g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    o.connect(g).connect(this.sfx);
    o.start(t); o.stop(t + dur + 0.02);
  }

  private noise(dur: number, vol: number, freq: number, type: BiquadFilterType = "lowpass", delay = 0) {
    const ctx = this.ctx;
    if (!ctx) return;
    const t = ctx.currentTime + delay;
    const n = ctx.createBufferSource(); n.buffer = this.noiseBuf;
    const f = ctx.createBiquadFilter(); f.type = type; f.frequency.value = freq;
    const g = ctx.createGain();
    g.gain.setValueAtTime(vol, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + dur);
    n.connect(f).connect(g).connect(this.sfx);
    n.start(t, Math.random() * 0.5); n.stop(t + dur);
  }

  play(name: string) {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== "running") return;
    switch (name) {
      case "jump": this.tone("square", 260, 620, 0.14, 0.07); this.tone("sine", 520, 1240, 0.12, 0.08); break;
      case "bell": {
        const t = ctx.currentTime;
        this.bellTone(1568, t, 0.18, 0.22, this.sfx);
        this.bellTone(2093, t + 0.07, 0.3, 0.2, this.sfx);
        break;
      }
      case "stomp": this.tone("triangle", 320, 70, 0.18, 0.5); this.noise(0.12, 0.35, 900); break;
      case "bump": this.tone("triangle", 180, 90, 0.1, 0.5); this.noise(0.05, 0.2, 500); break;
      case "spawnItem": [0, 1, 2, 3, 4].forEach((i) => this.tone("square", 392 * Math.pow(1.19, i), 392 * Math.pow(1.19, i), 0.06, 0.05, i * 0.05)); break;
      case "power": [523, 659, 784, 1047, 1319].forEach((f, i) => this.bellTone(f, ctx.currentTime + i * 0.07, 0.25, 0.18, this.sfx)); break;
      case "heart": [659, 784, 988].forEach((f, i) => this.bellTone(f, ctx.currentTime + i * 0.08, 0.3, 0.18, this.sfx)); break;
      case "hurt": this.tone("sawtooth", 440, 110, 0.35, 0.18); break;
      case "die": [523, 494, 466, 440, 392, 330].forEach((f, i) => this.tone("triangle", f, f * 0.97, 0.16, 0.25, i * 0.12)); break;
      case "throw": this.noise(0.12, 0.25, 2200, "bandpass"); this.tone("sine", 700, 300, 0.1, 0.08); break;
      case "hit": this.noise(0.15, 0.4, 1200); this.tone("square", 300, 80, 0.12, 0.08); break;
      case "checkpoint": [784, 988, 1175, 1568].forEach((f, i) => this.bellTone(f, ctx.currentTime + i * 0.06, 0.4, 0.14, this.sfx)); break;
      case "land": this.noise(0.08, 0.18, 400); break;
      case "step": this.noise(0.04, 0.05, 1600, "bandpass"); break;
      case "clear": [523, 659, 784, 1047, 784, 1047, 1319].forEach((f, i) => this.bellTone(f, ctx.currentTime + i * 0.13, 0.4, 0.2, this.sfx)); break;
      case "ui": this.bellTone(1175, ctx.currentTime, 0.15, 0.12, this.sfx); break;
    }
  }
}
