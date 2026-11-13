import { describe, expect, it } from "vitest";
import catalogJson from "./fixtures-catalog.json";
import fixtures from "./fixtures.json";
import { newState, plan, programWeeks } from "./engine";
import type { Catalog, Profile } from "./types";

const catalog = catalogJson as unknown as Catalog;

interface Fixture {
  name: string; program: string; level: Profile["level"]; startDay: number; profile: Profile; weeks: number; steps: number;
  plan: { day: number; kind: string; title: string; detail: string; isKey: boolean; week: number; deload: boolean; blocks: [string, number, number, number][] }[];
}

// Les plannings de référence sont écrits par le moteur de l'app (EngineFixturesTest.kt).
describe("le simulateur calcule exactement comme l'app", () => {
  for (const f of fixtures as Fixture[]) {
    it(f.name, () => {
      const t = catalog.programs.find((p) => p.id === f.program)!;
      const st = newState(t, f.profile, catalog, f.level);
      expect(st.steps.length).toBe(f.steps);
      expect(programWeeks(st.template, st.steps.length, new Set(f.profile.days).size > 4 ? 4 : new Set(f.profile.days).size)).toBe(f.weeks);
      const got = plan(st, { ...f.profile, level: f.level }, f.startDay).map((s) => ({
        day: s.day, kind: s.kind, title: s.title, detail: s.detail, isKey: s.isKey, week: s.week, deload: s.deload,
        blocks: s.blocks.map((b) => [b.kind, b.seconds, b.speedKmh, b.inclinePct]),
      }));
      expect(got).toEqual(f.plan);
    });
  }
});
