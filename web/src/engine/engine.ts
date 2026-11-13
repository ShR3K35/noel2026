// Moteur de programmes, port fidèle de Catalog.kt / Coach.kt / Physio.kt (côté app).
// Il sert au simulateur et aux garde-fous de l'interface coach.
import type {
  Block, BlockKind, BlockT, Catalog, Paces, PlannedSession, Profile, ProgramState, ProgramTemplate, ResolvedStep,
  SessionRef, SessionType,
} from "./types";

/** Arrondi de Kotlin roundToInt : au plus proche, les moitiés vers +∞ (comme Math.round). */
const roundInt = (v: number) => Math.round(v);
export const round1 = (v: number) => Math.round(v * 10) / 10;

export interface Speeds { warm: number; recovery: number; walk: number; hill: number; run: number; tempo: number; fast: number }

export function speedsFor(p: Profile, factor: number, paces: Paces): Speeds {
  const c = (v: number) => round1(Math.min(v, p.treadmillMaxKmh));
  const run = Math.min(Math.max(p.vr * paces.runOfVr, paces.runMin), paces.runMax) * factor;
  return {
    warm: c(p.vr * paces.warm),
    recovery: c(p.vr * paces.recovery),
    walk: c(p.vr * paces.walk * factor),
    hill: c(p.vr * paces.hill * factor),
    run: c(run),
    tempo: c(run * paces.tempoOfRun),
    fast: c(run * paces.fastOfRun),
  };
}

/** Dépense estimée (équations métaboliques ACSM), en kcal par minute. */
export function kcalPerMin(speedKmh: number, inclinePct: number, weightKg: number): number {
  if (speedKmh < 0.5) return 0;
  const s = (speedKmh * 1000) / 60;
  const g = inclinePct / 100;
  const vo2 = speedKmh > 7 ? 0.2 * s + 0.9 * s * g + 3.5 : 0.1 * s + 1.8 * s * g + 3.5;
  return (vo2 * weightKg) / 200;
}

export const kmh = (v: number) => v.toFixed(1).replace(".", ",");
const fmt = (v: number) => (v === roundInt(v) ? String(roundInt(v)) : kmh(v));

// ---------- Emploi du temps ----------

export function trainingDays(p: Profile): number[] {
  const d0 = [...new Set(p.days.filter((x) => x >= 1 && x <= 7))].sort((a, b) => a - b);
  const d = d0.length ? d0 : [1, 3, 6];
  if (d.length <= 4) return d;
  return [...new Set([0, 1, 2, 3].map((i) => d[Math.min(d.length - 1, roundInt((i * d.length) / 4))]))];
}

/** Jour ISO (1 = lundi) d'un jour compté depuis le 1er janvier 1970. */
export const dayOfWeek = (epochDay: number) => ((((epochDay + 3) % 7) + 7) % 7) + 1;

// ---------- Paliers ----------

function runDemand(ref: SessionRef, types: (id: string) => SessionType | undefined): number {
  const t = types(ref.type);
  if (!t) return 0;
  if (t.kind === "CONTINUOUS") return Math.trunc(ref.params.minutes ?? 0);
  if (t.kind === "INTERVALS") return Math.trunc(ref.params.a ?? 0);
  return 0;
}

export function resolve(t: ProgramTemplate, p: Profile, c: Catalog): ResolvedStep[] {
  const types = (id: string) => c.sessionTypes.find((x) => x.id === id);
  const run = speedsFor(p, 1, c.paces).run;
  let steps: ResolvedStep[] = t.steps
    .filter((s) => (s.levels ?? []).includes(p.level))
    .map((s) => ({
      note: s.note ?? "",
      sessions: s.sessions.map((r) => {
        const km = r.params.km;
        if (km != null && km > 0) {
          const { km: _km, ...rest } = r.params;
          return { ...r, params: { ...rest, minutes: Math.ceil((km / run) * 60) } };
        }
        return r;
      }),
    }));
  if (!steps.length) return steps;
  const last = steps[steps.length - 1];
  const goalDemand = runDemand(last.sessions[0], types);
  if (goalDemand > 0) steps = [...steps.slice(0, -1).filter((s) => runDemand(s.sessions[0], types) < goalDemand), last];
  if (t.startAtMastered && p.longestRunMin >= 5) {
    let mastered = -1;
    steps.forEach((s, i) => {
      const d = runDemand(s.sessions[0], types);
      if (d >= 1 && d <= p.longestRunMin) mastered = i;
    });
    if (mastered > 0) steps = steps.slice(mastered);
  }
  return steps;
}

export function weeksFor(stepCount: number, deloadEvery: number): number {
  let week = 0;
  let step = 0;
  while (step < stepCount) {
    if (!(deloadEvery > 0 && week % deloadEvery === deloadEvery - 1 && step > 0)) step++;
    week++;
  }
  return week;
}

export function lockedReason(t: ProgramTemplate, p: Profile): string | null {
  if (p.healthFlag && !t.requires.allowHealthFlag) return "Avis médical conseillé avant de courir";
  if (p.longestRunMin < t.requires.minLongestRunMin) return `Après ${t.requires.minLongestRunMin} min de course continue`;
  return null;
}

export function recommended(c: Catalog, p: Profile): ProgramTemplate | undefined {
  const visible = c.programs.filter((x) => x.enabled).sort((a, b) => a.order - b.order);
  return (
    visible.find((t) =>
      lockedReason(t, p) === null &&
      t.recommend.some((r) =>
        r.levels.includes(p.level) && p.longestRunMin >= r.minRun && p.longestRunMin <= r.maxRun &&
        (!r.healthFlag || p.healthFlag) && (r.healthFlag || !p.healthFlag),
      ),
    ) ?? visible.find((t) => lockedReason(t, p) === null)
  );
}

// ---------- Séances ----------

export function evalMinutes(expr: string, params: Record<string, number>): number {
  const e = String(expr).replace(/ /g, "");
  if (e !== "" && !Number.isNaN(Number(e))) return Number(e);
  const m = /^([A-Za-z_][A-Za-z0-9_]*)([+-])(\d+(?:\.\d+)?)$/.exec(e);
  if (m) {
    const base = params[m[1]] ?? 0;
    const k = Number(m[3]);
    return m[2] === "+" ? base + k : base - k;
  }
  return params[e] ?? 0;
}

function speedOf(pace: string, factor: number, s: Speeds, p: Profile): number {
  const base: Record<string, number> = { ...s, vr: p.vr };
  return Math.min(round1((base[pace] ?? s.walk) * factor), p.treadmillMaxKmh);
}

const inclineOf = (expr: string, p: Profile) => (expr === "profile" ? p.inclinePct : Number(expr) || 0);

export function expand(blocks: BlockT[], params: Record<string, number>, s: Speeds, p: Profile): Block[] {
  const out: Block[] = [];
  for (const b of blocks) {
    if (b.kind === "REPEAT") {
      const n = Math.max(0, roundInt(params[b.repeat ?? ""] ?? evalMinutes(b.repeat ?? "0", params)));
      for (let i = 0; i < n; i++) out.push(...expand(b.blocks ?? [], params, s, p));
    } else {
      const minutes = Math.max(1, evalMinutes(b.minutes, params));
      out.push({ kind: b.kind as BlockKind, seconds: roundInt(minutes * 60), speedKmh: speedOf(b.pace, b.paceFactor, s, p), inclinePct: inclineOf(b.incline, p) });
    }
  }
  return out;
}

function totalMinutes(blocks: BlockT[], params: Record<string, number>): number {
  return blocks.reduce((sum, b) =>
    sum + (b.kind === "REPEAT" ? (params[b.repeat ?? ""] ?? 0) * totalMinutes(b.blocks ?? [], params) : Math.max(1, evalMinutes(b.minutes, params))), 0);
}

function fill(text: string, params: Record<string, number>, s: Speeds, p: Profile): string {
  let r = text.split("{incline}").join(fmt(p.inclinePct));
  for (const pace of ["warm", "recovery", "walk", "hill", "run", "tempo", "fast"]) r = r.split(`{pace:${pace}}`).join(kmh(speedOf(pace, 1, s, p)));
  for (const [k, v] of Object.entries(params)) r = r.split(`{${k}}`).join(fmt(v));
  return r;
}

function shorten(type: SessionType, params: Record<string, number>, cap: number): Record<string, number> {
  const total = (ps: Record<string, number>) => totalMinutes(type.blocks, ps);
  if (total(params) <= cap) return params;
  const repeat = type.blocks.find((b) => b.kind === "REPEAT");
  if (repeat) {
    let ps = params;
    let n = roundInt(params[repeat.repeat ?? ""] ?? 1);
    while (n > 1 && total(ps) > cap) {
      n--;
      ps = { ...ps, [repeat.repeat ?? ""]: n };
    }
    return ps;
  }
  const key = type.params.find((x) => x.name === "minutes")?.name;
  if (!key) return params;
  let ps = params;
  let v = params[key];
  if (v == null) return params;
  while (v > 5 && total(ps) > cap) {
    v -= 1;
    ps = { ...ps, [key]: v };
  }
  return ps;
}

export function deloaded(params: Record<string, number>, factor: number): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(params)) {
    out[k] = k === "reps" ? Math.max(2, Math.ceil(v * factor)) : k === "minutes" ? Math.max(10, roundInt((v * factor) / 5) * 5) : v;
  }
  return out;
}

export function build(
  type: SessionType, params0: Record<string, number>, p: Profile, speeds: Speeds, day: number, week: number,
  isKey: boolean, deload: boolean, cap: number,
): PlannedSession {
  const withDefaults: Record<string, number> = {};
  for (const d of type.params) withDefaults[d.name] = d.default;
  Object.assign(withDefaults, params0);
  const params = cap > 0 ? shorten(type, withDefaults, cap) : withDefaults;
  return {
    day, kind: type.kind, title: fill(type.title || type.name, params, speeds, p), detail: fill(type.detail, params, speeds, p),
    blocks: expand(type.blocks, params, speeds, p), isKey, week, deload,
  };
}

export const minutesOf = (s: PlannedSession) => Math.floor((s.blocks.reduce((a, b) => a + b.seconds, 0) + 59) / 60);

// ---------- Progression (sans retours de séance) ----------

export function newState(t: ProgramTemplate, p: Profile, c: Catalog): ProgramState {
  return { template: t, catalog: c, steps: resolve(t, p, c), stepIndex: 0, week: 0, sessionInWeek: 0, speedFactor: 1 };
}

const isDeload = (st: ProgramState) => {
  const every = st.template.deload.every;
  return every > 0 && st.week % every === every - 1 && st.stepIndex > 0;
};

function typeOf(st: ProgramState, id: string): SessionType {
  const t = st.catalog.sessionTypes.find((x) => x.id === id);
  if (!t) throw new Error(`Type de séance introuvable : ${id}`);
  return t;
}

export function sessionFor(st: ProgramState, p: Profile, day: number, cap = 0): PlannedSession {
  const t = st.template;
  const c = st.catalog;
  const n = trainingDays(p).length;
  const s = speedsFor(p, st.speedFactor, c.paces);
  const deload = isDeload(st);
  const slot = st.sessionInWeek;
  const special = (id: string, params: Record<string, number>, capp: number) => build(typeOf(st, id), params, p, s, day, st.week, false, deload, capp);
  if (deload && t.deload.testAtEnd && n >= 2 && slot === n - 1) return special(c.specials.test, {}, 0);

  const keys = Object.keys(t.weekPatterns);
  const maxKey = keys.reduce((a, b) => ((Number(b) || 0) > (Number(a) || 0) ? b : a), keys[0]);
  const pattern = t.weekPatterns[String(n)] ?? t.weekPatterns[maxKey] ?? "K";
  const role = pattern[Math.min(slot, pattern.length - 1)] ?? "K";
  if (role === "R") return special(c.specials.recovery, {}, cap);
  if (role === "E") {
    const e = t.easy;
    const limit = Math.min(Math.max(15, p.minutesPerSession), e.maxMinutes);
    const minutes = Math.max(10, Math.min(cap > 0 ? cap : limit, e.startMinutes + e.stepMinutes * st.week));
    const wanted = e.types.length ? e.types[st.week % e.types.length] : e.fallback;
    let type = c.sessionTypes.find((x) => x.id === wanted) ?? typeOf(st, e.fallback);
    if (type.needsIncline && p.inclinePct <= 0) type = c.sessionTypes.find((x) => x.id === e.fallback) ?? type;
    return build(type, { minutes }, p, s, day, st.week, false, deload, cap);
  }
  const idx = Math.min(Math.max(st.stepIndex - (deload ? 1 : 0), 0), st.steps.length - 1);
  const step = st.steps[idx];
  const keyIndex = [...pattern.slice(0, slot)].filter((ch) => ch === "K").length;
  const ref = step.sessions[keyIndex % step.sessions.length];
  const type = typeOf(st, ref.type);
  let params = ref.params;
  if (deload) {
    const base: Record<string, number> = {};
    for (const d of type.params) base[d.name] = d.default;
    params = deloaded({ ...base, ...ref.params }, t.deload.factor ?? 0.7);
  }
  return build(type, params, p, s, day, st.week, true, deload, cap);
}

export function advance(st: ProgramState, p: Profile): ProgramState {
  let q = { ...st, sessionInWeek: st.sessionInWeek + 1 };
  if (q.sessionInWeek >= trainingDays(p).length) {
    const next = isDeload(st) ? st.stepIndex : st.stepIndex + 1;
    q = { ...q, sessionInWeek: 0, week: st.week + 1, stepIndex: next };
  }
  return q;
}

/** Planning complet à partir d'un jour donné, si chaque séance est faite à sa date. */
export function plan(st0: ProgramState, p: Profile, startDay: number): PlannedSession[] {
  const days = trainingDays(p);
  let cursor = startDay;
  let st = st0;
  const out: PlannedSession[] = [];
  while (st.stepIndex < st.steps.length && out.length < 400) {
    while (!days.includes(dayOfWeek(cursor))) cursor++;
    out.push(sessionFor(st, p, cursor));
    st = advance(st, p);
    cursor++;
  }
  return out;
}

export function longestSessionMinutes(t: ProgramTemplate, p: Profile, c: Catalog): number {
  const s = speedsFor(p, 1, c.paces);
  let max = 0;
  for (const step of resolve(t, p, c)) for (const ref of step.sessions) {
    const type = c.sessionTypes.find((x) => x.id === ref.type);
    if (type) max = Math.max(max, minutesOf(build(type, ref.params, p, s, 0, 0, true, false, 0)));
  }
  return max;
}
