import { describe, expect, it } from "vitest";
import catalogJson from "../../../catalog/catalog.json";
import type { Catalog } from "./types";
import { validateCatalog, validateProgram } from "./validate";

const catalog = catalogJson as unknown as Catalog;
const clone = () => JSON.parse(JSON.stringify(catalog)) as Catalog;

describe("garde-fous", () => {
  it("le catalogue initial n'a aucune erreur bloquante", () => {
    const issues = validateCatalog(catalog);
    expect(issues.filter((i) => i.level === "error")).toEqual([]);
    console.log(issues.map((i) => `${i.where.program ?? i.where.sessionType ?? ""}: ${i.message}`).join("\n"));
  });

  it("repère un type de séance inconnu et un paramètre non déclaré", () => {
    const c = clone();
    c.programs[0].steps[0].sessions[0].type = "nexiste-pas";
    c.sessionTypes[0].blocks[1].minutes = "duree-6";
    const errors = validateCatalog(c).filter((i) => i.level === "error").map((i) => i.message);
    expect(errors.some((m) => m.includes("nexiste-pas"))).toBe(true);
    expect(errors.some((m) => m.includes("« duree »"))).toBe(true);
  });

  it("signale deux curseurs qui bougent ensemble et un saut de volume", () => {
    const c = clone();
    const p = c.programs.find((x) => x.id === "run-30")!;
    const i = p.steps.findIndex((s) => s.sessions[0].type === "intervals");
    p.steps[i + 1].sessions[0].params = { a: 3, b: 2, reps: 9 };
    const warnings = validateProgram(p, c).map((x) => x.message);
    expect(warnings.some((m) => m.includes("Un seul curseur"))).toBe(true);
    expect(warnings.some((m) => m.includes("+10 %") || m.includes("+25 %"))).toBe(true);
  });
});
