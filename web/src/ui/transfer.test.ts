import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import catalogJson from "../../../catalog/catalog.json";
import { levelsOf, newState, plan, programWeeks } from "../engine/engine";
import type { Catalog } from "../engine/types";
import { validateCatalog } from "../engine/validate";
import { applyImport, exportProgram, parseProgramFile } from "./transfer";

const catalog = catalogJson as unknown as Catalog;
const example = readFileSync(new URL("../../../contract/examples/courir-30-min-tapis.json", import.meta.url), "utf8");

describe("contrat d'import relance.program v1", () => {
  it("importe le programme « Courir 30 min · tapis, 25 semaines »", () => {
    const r = parseProgramFile(example, catalog);
    if (!r.ok) throw new Error(r.errors.join("\n"));
    expect(r.plan.newTypes.map((t) => t.id)).toEqual(["tapis-intervalles", "tapis-course-coupee", "tapis-course-continue", "tapis-marche-active"]);
    expect(r.plan.idConflict).toBe(false);
    const { catalog: merged, programId } = applyImport(catalog, r.plan, false);
    const p = merged.programs.find((x) => x.id === programId)!;
    expect(p.enabled).toBe(false);
    expect(levelsOf(p)).toEqual(["N2"]);
    expect(validateCatalog(merged).filter((i) => i.level === "error")).toEqual([]);
    const prof = { age: 40, weightKg: 70, level: "N2" as const, vr: 5.4, longestRunMin: 0, healthFlag: false, days: [2, 4, 6], minutesPerSession: 45, inclinePct: 0, treadmillMaxKmh: 14 };
    const st = newState(p, prof, merged);
    expect(programWeeks(st.template, st.steps.length, 3)).toBe(25);
    const sessions = plan(st, prof, 20402);
    expect(sessions[0].detail).toBe("6 × (1 min de course / 3 min de marche)");
    expect(sessions[3].detail).toBe("6 × (1 min 15 s de course / 2 min 45 s de marche)");
    expect(sessions[sessions.length - 3].detail).toBe("30 min de course continue");
  });

  it("export puis import redonnent le même programme", () => {
    const t = catalog.programs.find((p) => p.id === "run-30")!;
    const text = JSON.stringify(exportProgram(t, catalog));
    const r = parseProgramFile(text, catalog);
    if (!r.ok) throw new Error(r.errors.join("\n"));
    expect(r.plan.newTypes).toEqual([]);
    expect(r.plan.idConflict).toBe(true);
    const { catalog: merged } = applyImport(catalog, r.plan, true);
    const after = merged.programs.find((p) => p.id === "run-30")!;
    expect({ ...after, enabled: true }).toEqual({ ...t, enabled: true });
  });

  it("refuse un champ inconnu, un mauvais type et un type de séance inexistant, avec des messages précis", () => {
    const doc = JSON.parse(example);
    doc.program.difficulte = "facile";
    doc.program.variants.N2.steps[0].sessions[0].params.a = "une minute";
    doc.program.variants.N5 = { steps: [] };
    const r = parseProgramFile(JSON.stringify(doc), catalog);
    expect(r.ok).toBe(false);
    if (r.ok) return;
    expect(r.errors.some((e) => e.includes("/program") && e.includes("difficulte"))).toBe(true);
    expect(r.errors.some((e) => e.includes("/program/variants/N2/steps/0/sessions/0/params/a"))).toBe(true);
    expect(r.errors.some((e) => e.includes("N5"))).toBe(true);
    const doc2 = JSON.parse(example);
    doc2.program.variants.N2.steps[0].sessions[0].type = "seance-fantome";
    const r2 = parseProgramFile(JSON.stringify(doc2), catalog);
    expect(r2.ok).toBe(false);
    if (!r2.ok) expect(r2.errors[0]).toContain("seance-fantome");
    expect(parseProgramFile("{ pas du json", catalog).ok).toBe(false);
  });

  it("renomme un type de séance qui existe déjà avec un autre contenu, et importe en copie", () => {
    const doc = JSON.parse(example);
    doc.program.id = "run-30";
    doc.sessionTypes.push({ ...catalog.sessionTypes.find((t) => t.id === "walk")!, name: "Marche active modifiée" });
    doc.program.variants.N2.steps[0].sessions[0] = { type: "walk", params: { minutes: 20 } };
    const r = parseProgramFile(JSON.stringify(doc), catalog);
    if (!r.ok) throw new Error(r.errors.join("\n"));
    expect(r.plan.renamedTypes).toEqual([{ from: "walk", to: "walk-import" }]);
    expect(r.plan.program.variants!.N2!.steps[0].sessions[0].type).toBe("walk-import");
    const { catalog: merged, programId } = applyImport(catalog, r.plan, false);
    expect(programId).toBe("run-30-import");
    expect(merged.programs.find((p) => p.id === "run-30")!.name).toBe("Courir 30 min");
  });
});

describe("saut de palier", () => {
  it("garde allowSkip à l'import et à l'export", () => {
    const raw = readFileSync(new URL("../../../contract/examples/courir-30-min-daniels.json", import.meta.url), "utf8");
    const r = parseProgramFile(raw, catalog);
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    const out = applyImport(catalog, r.plan, false);
    const p = out.catalog.programs.find((x) => x.id === out.programId)!;
    expect(p.allowSkip).toBe(false);
    expect(exportProgram(p, out.catalog).program.allowSkip).toBe(false);
    // Paliers tenus deux semaines, sorties faciles plafonnées : aucun avertissement de coach.
    expect(validateCatalog(out.catalog).filter((i) => i.where.program === out.programId)).toEqual([]);
  });
});
