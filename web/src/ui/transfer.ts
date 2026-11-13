// Import / export d'un programme au format « relance.program » (contract/program-import-v1.schema.json).
import Ajv2020 from "ajv/dist/2020";
import localizeFr from "ajv-i18n/localize/fr";
import schema from "../../../contract/program-import-v1.schema.json";
import { levelsOf } from "../engine/engine";
import type { BlockT, Catalog, ProgramTemplate, SessionRef, SessionType, StepT, VariantT } from "../engine/types";
import { LEVELS } from "../engine/types";

export const FORMAT = "relance.program";
export const MAX_BYTES = 1_000_000;

export interface ProgramFile {
  $schema?: string;
  format: typeof FORMAT;
  formatVersion: 1;
  meta?: { source?: string; generatedAt?: string; rationale?: string };
  program: ProgramTemplate;
  sessionTypes?: SessionType[];
}

const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));

const ajv = new Ajv2020({ allErrors: true, strict: false });
const validateFile = ajv.compile(schema);

// ---------- Normalisation (valeurs par défaut du moteur) ----------

function normalizeBlock(b: BlockT): BlockT {
  const out: BlockT = { kind: b.kind, minutes: b.minutes ?? "0", pace: b.pace ?? "walk", paceFactor: b.paceFactor ?? 1, incline: b.incline ?? "0" };
  if (b.kmh != null) out.kmh = b.kmh;
  if (b.kind === "REPEAT") {
    out.repeat = b.repeat ?? "";
    out.blocks = (b.blocks ?? []).map(normalizeBlock);
  }
  return out;
}

export function normalizeType(t: SessionType): SessionType {
  return {
    id: t.id, name: t.name, kind: t.kind, title: t.title ?? "", detail: t.detail ?? "", purpose: t.purpose ?? "",
    needsIncline: !!t.needsIncline,
    params: (t.params ?? []).map((p) => ({ name: p.name, label: p.label ?? "", default: p.default, min: p.min, max: p.max, unit: p.unit ?? "min" })),
    blocks: (t.blocks ?? []).map(normalizeBlock),
  };
}

const normRef = (r: SessionRef): SessionRef => ({ type: r.type, params: { ...(r.params ?? {}) } });
const normStep = (s: StepT): StepT => ({ sessions: s.sessions.map(normRef), note: s.note ?? "", ...(s.easy ? { easy: normRef(s.easy) } : {}) });

function normalizeProgram(p: ProgramTemplate, order: number): ProgramTemplate {
  const variants: Record<string, VariantT> = {};
  for (const [lv, v] of Object.entries(p.variants ?? {})) if (v) variants[lv] = { ...clone(v), steps: v.steps.map(normStep) };
  return {
    id: p.id, name: p.name, validation: p.validation ?? "", description: p.description ?? "",
    order: p.order ?? order, enabled: false,
    goal: { kind: p.goal?.kind ?? "none", minutes: p.goal?.minutes ?? 0, km: p.goal?.km ?? 0 },
    requires: { minLongestRunMin: p.requires?.minLongestRunMin ?? 0, allowHealthFlag: !!p.requires?.allowHealthFlag },
    recommend: (p.recommend ?? []).map((r) => ({ levels: r.levels, minRun: r.minRun ?? 0, maxRun: r.maxRun ?? 999, ...(r.healthFlag ? { healthFlag: true } : {}) })),
    steps: [],
    startAtMastered: p.startAtMastered ?? true,
    keysPerStep: p.keysPerStep ?? 2,
    easy: p.easy ? clone(p.easy) : { types: ["hill", "walk"], fallback: "walk", startMinutes: 15, stepMinutes: 5, maxMinutes: 30 },
    deload: p.deload ? { factor: 0.7, ...clone(p.deload) } : { every: 4, testAtEnd: true, factor: 0.7 },
    weekPatterns: p.weekPatterns ? clone(p.weekPatterns) : { "1": "K", "2": "KK", "3": "KEK", "4": "KEKR" },
    weekNotes: p.weekNotes ? clone(p.weekNotes) : {},
    variants,
  };
}

// ---------- Export ----------

/** Toutes les références de types de séance d'un programme. */
function refsOf(p: ProgramTemplate): string[] {
  const ids = new Set<string>([...p.easy.types, p.easy.fallback]);
  const steps = [...p.steps, ...Object.values(p.variants ?? {}).flatMap((v) => v?.steps ?? [])];
  for (const s of steps) {
    s.sessions.forEach((r) => ids.add(r.type));
    if (s.easy) ids.add(s.easy.type);
  }
  for (const v of Object.values(p.variants ?? {})) if (v?.easy) [...v.easy.types, v.easy.fallback].forEach((x) => ids.add(x));
  return [...ids];
}

export function exportProgram(t: ProgramTemplate, c: Catalog): ProgramFile {
  const p = clone(t);
  if (!(p.variants && Object.keys(p.variants).length)) {
    p.variants = {};
    for (const lv of LEVELS) {
      const steps = p.steps.filter((s) => (s.levels ?? LEVELS).includes(lv));
      if (steps.length) p.variants[lv] = { steps };
    }
  }
  const variants: Record<string, VariantT> = {};
  for (const [lv, v] of Object.entries(p.variants)) if (v) variants[lv] = { ...v, steps: v.steps.map(({ levels: _l, ...rest }) => rest) };
  const { steps: _s, variants: _v, ...rest } = p;
  const program = { ...rest, variants } as ProgramTemplate;
  const ids = refsOf(p);
  return {
    $schema: "https://relance-coach.netlify.app/schemas/program-import-v1.json",
    format: FORMAT, formatVersion: 1,
    meta: { source: "Relance · Coach (export)", generatedAt: new Date().toISOString() },
    program,
    sessionTypes: c.sessionTypes.filter((x) => ids.includes(x.id)).map(normalizeType),
  };
}

export function downloadProgram(t: ProgramTemplate, c: Catalog): void {
  const blob = new Blob([JSON.stringify(exportProgram(t, c), null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `${t.id}.relance.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

// ---------- Import ----------

export interface ImportPlan {
  file: ProgramFile;
  program: ProgramTemplate;
  newTypes: SessionType[];
  reusedTypes: string[];
  renamedTypes: { from: string; to: string }[];
  idConflict: boolean;
}

export type ParseResult = { ok: true; plan: ImportPlan } | { ok: false; errors: string[] };

function uniqueId(base: string, taken: Set<string>): string {
  let id = base.slice(0, 38);
  for (let i = 2; taken.has(id); i++) id = `${base.slice(0, 36)}-${i}`;
  return id;
}

/** Lit un fichier, le vérifie contre le contrat et prépare sa fusion dans le catalogue. */
export function parseProgramFile(text: string, catalog: Catalog): ParseResult {
  if (text.length > MAX_BYTES) return { ok: false, errors: [`Fichier trop gros (${Math.round(text.length / 1000)} ko, 1 000 ko au plus).`] };
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch (e) {
    return { ok: false, errors: [`JSON invalide : ${(e as Error).message}`] };
  }
  if (!validateFile(data)) {
    localizeFr(validateFile.errors);
    const errors = (validateFile.errors ?? []).map((e) => {
      const extra = e.keyword === "additionalProperties" ? ` (« ${(e.params as { additionalProperty: string }).additionalProperty} »)`
        : e.keyword === "enum" ? ` (${(e.params as { allowedValues: unknown[] }).allowedValues.join(", ")})` : "";
      return `${e.instancePath || "(racine)"} : ${e.message}${extra}`;
    });
    return { ok: false, errors: [...new Set(errors)].slice(0, 50) };
  }
  const file = data as unknown as ProgramFile;
  const program = normalizeProgram(file.program, Math.max(0, ...catalog.programs.map((p) => p.order)) + 1);

  // Types de séance : réutilisés s'ils sont identiques, renommés s'ils diffèrent d'un type existant.
  const taken = new Set(catalog.sessionTypes.map((t) => t.id));
  const newTypes: SessionType[] = [];
  const reusedTypes: string[] = [];
  const renamedTypes: { from: string; to: string }[] = [];
  const rename = new Map<string, string>();
  for (const raw of file.sessionTypes ?? []) {
    const t = normalizeType(raw);
    const existing = catalog.sessionTypes.find((x) => x.id === t.id);
    if (existing && JSON.stringify(normalizeType(existing)) === JSON.stringify(t)) {
      reusedTypes.push(t.id);
    } else if (existing) {
      const to = uniqueId(`${t.id}-import`, taken);
      taken.add(to);
      rename.set(t.id, to);
      renamedTypes.push({ from: t.id, to });
      newTypes.push({ ...t, id: to });
    } else {
      taken.add(t.id);
      newTypes.push(t);
    }
  }
  if (rename.size) {
    const fix = (r?: SessionRef) => { if (r && rename.has(r.type)) r.type = rename.get(r.type)!; };
    const fixEasy = (e?: { types: string[]; fallback: string }) => {
      if (!e) return;
      e.types = e.types.map((x) => rename.get(x) ?? x);
      e.fallback = rename.get(e.fallback) ?? e.fallback;
    };
    fixEasy(program.easy);
    for (const v of Object.values(program.variants ?? {})) {
      if (!v) continue;
      fixEasy(v.easy);
      v.steps.forEach((s) => { s.sessions.forEach(fix); fix(s.easy); });
    }
  }
  const known = new Set([...catalog.sessionTypes.map((t) => t.id), ...newTypes.map((t) => t.id)]);
  const unknown = refsOf(program).filter((id) => !known.has(id));
  if (unknown.length) {
    return { ok: false, errors: unknown.map((id) => `Type de séance inconnu « ${id} » : ni dans le catalogue, ni dans « sessionTypes ».`) };
  }
  if (!levelsOf(program).length) return { ok: false, errors: ["Le programme n'a aucune variante de niveau."] };
  return { ok: true, plan: { file, program, newTypes, reusedTypes, renamedTypes, idConflict: catalog.programs.some((p) => p.id === program.id) } };
}

/** Catalogue après import. `replace` : remplace le programme de même identifiant ; sinon import en copie. */
export function applyImport(catalog: Catalog, plan: ImportPlan, replace: boolean): { catalog: Catalog; programId: string } {
  const c = clone(catalog);
  const p = clone(plan.program);
  if (plan.idConflict && replace) {
    const i = c.programs.findIndex((x) => x.id === p.id);
    p.order = c.programs[i].order;
    c.programs[i] = p;
  } else {
    if (plan.idConflict) {
      p.id = uniqueId(`${p.id}-import`, new Set(c.programs.map((x) => x.id)));
      p.name = `${p.name} (import)`;
    }
    c.programs.push(p);
  }
  c.sessionTypes.push(...clone(plan.newTypes));
  return { catalog: c, programId: p.id };
}
