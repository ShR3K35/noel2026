import { describe, expect, it } from "vitest";
import { DT, IDLE, MAX_SPEED, Race } from "./sim";
import { buildTrack, lateral, nearestIndex, TRACKS } from "./tracks";

describe("circuits", () => {
  for (const def of TRACKS) {
    const track = buildTrack(def);
    it(`${def.name} : boucle fermée, sans tronçons qui se chevauchent`, () => {
      const S = track.samples, n = S.length;
      expect(track.length).toBeGreaterThan(350);
      expect(Math.hypot(S[0].x - S[n - 1].x, S[0].z - S[n - 1].z)).toBeLessThan(track.step * 1.5);
      // deux points éloignés le long de la piste ne doivent pas être côte à côte
      const minGap = def.halfWidth * 2 + (def.outside === "void" ? 6 : 10);
      let closest = Infinity;
      for (let i = 0; i < n; i += 3) for (let j = i + 3; j < n; j += 3) {
        const along = Math.min(j - i, n - (j - i)) * track.step;
        if (along < minGap * 2.2) continue;
        closest = Math.min(closest, Math.hypot(S[i].x - S[j].x, S[i].z - S[j].z));
      }
      expect(closest).toBeGreaterThan(minGap);
      // virages négociables : rayon minimal supérieur à la demi-largeur
      const maxCurv = Math.max(...S.map((s) => Math.abs(s.curv)));
      expect(1 / maxCurv).toBeGreaterThan(def.halfWidth * 1.3);
    });

    it(`${def.name} : les cinq karts finissent la course`, () => {
      const race = new Race(track, { playerIsAi: true });
      let ticks = 0;
      while (race.karts.some((k) => !k.finished) && ticks < 60 * 400) { race.step(IDLE); ticks++; }
      for (const k of race.karts) {
        expect(k.finished, `${k.racer.name} n'a pas fini`).toBe(true);
        expect(k.lap).toBe(def.laps + 1);
      }
      const times = race.karts.map((k) => k.finishTime);
      const lap = Math.min(...times) / def.laps;
      // un tour dure entre le temps à fond et trois fois plus
      expect(lap).toBeGreaterThan(track.length / (MAX_SPEED * 1.4));
      expect(lap).toBeLessThan((track.length / MAX_SPEED) * 3);
      expect(new Set(race.karts.map((k) => k.rank)).size).toBe(5);
      if (def.outside === "void") expect(Math.max(...race.karts.map((k) => k.falls))).toBeLessThan(8);
      console.log(def.id, "longueur", Math.round(track.length), "tour", lap.toFixed(1), "s, temps", times.map((t) => t.toFixed(0)).join(" "), "chutes", race.karts.map((k) => k.falls).join(""));
    });
  }
});

describe("course", () => {
  const track = buildTrack(TRACKS[0]);
  it("personne ne bouge pendant le décompte, puis le joueur accélère", () => {
    const race = new Race(track);
    const p = race.player;
    const x0 = p.x;
    for (let i = 0; i < 60 * 3; i++) race.step({ ...IDLE, accel: true });
    expect(p.x).toBe(x0);
    for (let i = 0; i < 60 * 4; i++) race.step({ ...IDLE, accel: true });
    expect(p.speed).toBeGreaterThan(MAX_SPEED * 0.8);
    expect(race.events.some((e) => e.type === "go")).toBe(true);
  });

  it("le joueur part dernier et le Père Noël est le seul kart du joueur", () => {
    const race = new Race(track);
    expect(race.karts.filter((k) => k.isPlayer)).toHaveLength(1);
    expect(race.player.racer.id).toBe("santa");
    expect(race.karts.map((k) => k.racer.id).sort()).toEqual(["grincheux", "lutin", "mere", "renne", "santa"]);
    race.step(IDLE);
    expect(race.player.rank).toBe(5);
  });

  it("hors piste, on est nettement ralenti", () => {
    const race = new Race(track);
    race.time = 0;
    const p = race.player;
    const s = track.samples[40];
    p.idx = 40; p.x = s.x + s.nx * (track.def.halfWidth + 5); p.z = s.z + s.nz * (track.def.halfWidth + 5);
    p.heading = Math.atan2(s.tz, s.tx);
    for (let i = 0; i < 60 * 3; i++) {
      // on reste sur le bas-côté en suivant la piste
      const idx = nearestIndex(track, p.x, p.z, p.idx);
      const off = lateral(track, idx, p.x, p.z);
      race.step({ ...IDLE, accel: true, steer: (track.def.halfWidth + 5 - off) * 0.3 - track.samples[idx].curv * 0 });
    }
    expect(p.offroad).toBe(true);
    expect(p.speed).toBeLessThan(MAX_SPEED * 0.62);
  });

  it("un turbo dépasse la vitesse maximale, une boule de neige fait partir en tête-à-queue", () => {
    const race = new Race(track);
    race.time = 0;
    const p = race.player;
    for (let i = 0; i < 80; i++) race.step({ ...IDLE, accel: true });
    const before = p.speed;
    p.item = "boost";
    race.step({ ...IDLE, accel: true, item: true });
    expect(p.item).toBeNull();
    for (let i = 0; i < 40; i++) race.step({ ...IDLE, accel: true });
    expect(p.speed).toBeGreaterThan(Math.max(before + 6, MAX_SPEED * 1.1));
    const victim = race.karts.find((k) => !k.isPlayer)!;
    race.snowballs.push({ id: 99, x: victim.x - 1, z: victim.z, vx: 0, vz: 0, owner: p, life: 1, idx: victim.idx });
    race.step({ ...IDLE, accel: true });
    expect(victim.spinT).toBeGreaterThan(0);
  });

  it("le pas de temps est fixe", () => { expect(DT).toBeCloseTo(1 / 60); });
});

describe("compteur de tours", () => {
  // Conduite chaotique (demi-tours, marche arrière, murs, tout droit) : le tour compté par le jeu doit
  // toujours correspondre à la distance réellement parcourue le long de la piste, mesurée à part.
  for (const def of TRACKS) {
    it(`${def.name} : jamais de tour compté en trop ou en moins`, () => {
      const track = buildTrack(def);
      const n = track.samples.length;
      for (let seed = 1; seed <= 8; seed++) {
        let s = seed * 104729;
        const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
        const race = new Race(track);
        race.time = 0;
        const p = race.player;
        let g = nearestIndex(track, p.x, p.z);
        let truth = g - n;
        let mode = 0, modeT = 0, steer = 0;
        for (let t = 0; t < 60 * 90 && !p.finished; t++) {
          modeT -= DT;
          if (modeT <= 0) { modeT = 0.4 + rnd() * 3; mode = Math.floor(rnd() * 5); steer = rnd() * 2 - 1; }
          const look = track.samples[(p.idx + 10) % n];
          let diff = Math.atan2(look.z - p.z, look.x - p.x) - p.heading;
          while (diff > Math.PI) diff -= 2 * Math.PI;
          while (diff < -Math.PI) diff += 2 * Math.PI;
          const follow = Math.max(-1, Math.min(1, diff * 2.5));
          race.step(mode <= 1 ? { ...IDLE, accel: true, steer: follow, drift: rnd() < 0.3 }
            : mode === 2 ? { ...IDLE, accel: true, steer, drift: rnd() < 0.5 }
            : mode === 3 ? { ...IDLE, brake: true, steer }
            : { ...IDLE, accel: true, steer: 0 });
          const g2 = nearestIndex(track, p.x, p.z);
          let d = g2 - g; if (d > n / 2) d -= n; else if (d < -n / 2) d += n;
          truth += d; g = g2;
          if (p.fallT <= 0) expect(Math.abs(p.progress - truth), `graine ${seed}, t=${(t / 60).toFixed(1)} s`).toBeLessThan(40);
        }
        if (p.finished) expect(truth / n).toBeGreaterThan(def.laps - 0.05);
      }
    });
  }
});
