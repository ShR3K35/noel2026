// Garde-fous : erreurs (bloquent la publication) et avertissements de coach (à lire, non bloquants).
import { dayOfWeek, evalMinutes, forLevel, levelsOf, minutesOf, newState, patternFor, plan } from "./engine";
import type { BlockT, Catalog, Level, Profile, ProgramTemplate, SessionType } from "./types";

export interface Issue {
  level: "error" | "warning";
  where: { program?: string; sessionType?: string; step?: number; level?: Level };
  message: string;
}

/** Profils de référence sur lesquels les programmes sont simulés pour les avertissements. */
export const REFERENCE_PROFILES: { name: string; profile: Profile }[] = [
  { name: "N1 débutant", profile: { age: 55, weightKg: 90, level: "N1", vr: 4.6, longestRunMin: 0, healthFlag: false, days: [1, 3, 6], minutesPerSession: 30, inclinePct: 3, treadmillMaxKmh: 14 } },
  { name: "N2 marcheur", profile: { age: 42, weightKg: 75, level: "N2", vr: 5.8, longestRunMin: 0, healthFlag: false, days: [1, 3, 6], minutesPerSession: 30, inclinePct: 3, treadmillMaxKmh: 14 } },
  { name: "N3 marche / course", profile: { age: 35, weightKg: 70, level: "N3", vr: 6.0, longestRunMin: 0, healthFlag: false, days: [2, 4, 6, 7], minutesPerSession: 45, inclinePct: 3, treadmillMaxKmh: 14 } },
  { name: "N4 reprise", profile: { age: 30, weightKg: 72, level: "N4", vr: 6.6, longestRunMin: 30, healthFlag: false, days: [1, 3, 5], minutesPerSession: 45, inclinePct: 3, treadmillMaxKmh: 14 } },
];

const ID_RE = /^[a-z0-9][a-z0-9-]{0,40}$/;

function paramsUsed(blocks: BlockT[], out = new Set<string>()): Set<string> {
  for (const b of blocks) {
    if (b.kind === "REPEAT") {
      if (b.repeat && Number.isNaN(Number(b.repeat))) out.add(b.repeat);
      paramsUsed(b.blocks ?? [], out);
    } else {
      const m = /^([A-Za-z_][A-Za-z0-9_]*)/.exec(String(b.minutes).replace(/ /g, ""));
      if (m) out.add(m[1]);
    }
  }
  return out;
}

export function validateSessionType(t: SessionType): Issue[] {
  const where = { sessionType: t.id };
  const issues: Issue[] = [];
  if (!ID_RE.test(t.id)) issues.push({ level: "error", where, message: "Identifiant invalide : lettres minuscules, chiffres et tirets." });
  if (!t.name.trim()) issues.push({ level: "error", where, message: "Le type de séance n'a pas de nom." });
  if (!t.blocks.length) issues.push({ level: "error", where, message: "Aucun bloc : la séance serait vide." });
  const declared = new Set(t.params.map((p) => p.name));
  for (const name of paramsUsed(t.blocks)) {
    if (!declared.has(name)) issues.push({ level: "error", where, message: `Les blocs utilisent le paramètre « ${name} », qui n'est pas déclaré.` });
  }
  const walkBlocks = (bs: BlockT[]) => bs.forEach((b) => {
    if (b.kind === "REPEAT") {
      if (!b.repeat) issues.push({ level: "error", where, message: "Un bloc « Répéter » n'indique pas combien de fois." });
      if (!(b.blocks ?? []).length) issues.push({ level: "error", where, message: "Un bloc « Répéter » est vide." });
      walkBlocks(b.blocks ?? []);
    }
  });
  walkBlocks(t.blocks);
  const first = t.blocks[0];
  if (first && first.kind !== "WARMUP" && t.kind !== "RECOVERY") {
    issues.push({ level: "warning", where, message: "La séance ne commence pas par un échauffement." });
  }
  const last = t.blocks[t.blocks.length - 1];
  if (last && last.kind !== "COOLDOWN" && t.kind !== "RECOVERY") {
    issues.push({ level: "warning", where, message: "La séance ne se termine pas par un retour au calme." });
  }
  const defaults: Record<string, number> = {};
  t.params.forEach((p) => (defaults[p.name] = p.default));
  const tooShort = (bs: BlockT[]): boolean => bs.some((b) => (b.kind === "REPEAT" ? tooShort(b.blocks ?? []) : evalMinutes(b.minutes, defaults) < 1));
  if (tooShort(t.blocks)) issues.push({ level: "warning", where, message: "Avec les valeurs par défaut, un bloc dure moins d'une minute (il sera porté à 1 min)." });
  for (const p of t.params) {
    if (p.min > p.max) issues.push({ level: "error", where, message: `Paramètre « ${p.label || p.name} » : le minimum dépasse le maximum.` });
    if (p.default < p.min || p.default > p.max) issues.push({ level: "warning", where, message: `Paramètre « ${p.label || p.name} » : la valeur par défaut sort des bornes.` });
  }
  return issues;
}

export function validateProgram(t0: ProgramTemplate, c: Catalog): Issue[] {
  const issues: Issue[] = [];
  const where = { program: t0.id };
  const types = new Map(c.sessionTypes.map((x) => [x.id, x]));
  if (!ID_RE.test(t0.id)) issues.push({ level: "error", where, message: "Identifiant invalide : lettres minuscules, chiffres et tirets." });
  if (!t0.name.trim()) issues.push({ level: "error", where, message: "Le programme n'a pas de nom." });
  const levels = levelsOf(t0);
  if (!levels.length) issues.push({ level: "error", where, message: "Aucune variante de niveau : le programme serait vide." });
  if (t0.goal.kind === "distance" && !(t0.goal.km > 0)) issues.push({ level: "error", where, message: "Objectif en distance sans distance." });
  const once = new Set<string>();
  const push = (i: Issue) => {
    const key = `${i.level}|${i.where.level ?? ""}|${i.where.step ?? ""}|${i.message}`;
    if (!once.has(key)) { once.add(key); issues.push(i); }
  };

  for (const lv of levels) {
    const t = forLevel(t0, lv)!;
    const w0 = { program: t0.id, level: lv };
    const at = (i: number) => `${lv} · palier ${i + 1}`;
    if (!t.steps.length) push({ level: "error", where: w0, message: `${lv} : aucun palier.` });
    t.steps.forEach((s, i) => {
      const w = { ...w0, step: i };
      if (!s.sessions.length) push({ level: "error", where: w, message: `${at(i)} : aucune séance.` });
      for (const ref of [...s.sessions, ...(s.easy ? [s.easy] : [])]) {
        const type = types.get(ref.type);
        if (!type) {
          push({ level: "error", where: w, message: `${at(i)} : type de séance inconnu « ${ref.type} ».` });
          continue;
        }
        for (const p of type.params) {
          const v = ref.params?.[p.name];
          if (v != null && (v < p.min || v > p.max)) push({ level: "warning", where: w, message: `${at(i)} : ${p.label || p.name} = ${v}, hors des bornes prévues (${p.min}–${p.max}).` });
        }
      }
    });
    for (const id of [...t.easy.types, t.easy.fallback]) {
      if (!types.has(id)) push({ level: "error", where: w0, message: `${lv} · séances d'endurance : type inconnu « ${id} ».` });
    }
    for (const [n, pattern] of Object.entries(t.weekPatterns)) {
      if (!/^[KER]+$/.test(pattern)) push({ level: "error", where: w0, message: `${lv} · semaine à ${n} séance(s) : utiliser seulement K (clé), E (endurance) et R (récupération).` });
      else if (pattern.length !== Number(n)) push({ level: "warning", where: w0, message: `${lv} · semaine à ${n} séance(s) : la séquence « ${pattern} » compte ${pattern.length} séance(s).` });
      if (!pattern.includes("K")) push({ level: "warning", where: w0, message: `${lv} · semaine à ${n} séance(s) : aucune séance clé, le programme n'avancera pas.` });
      const k = [...pattern].filter((ch) => ch === "K").length;
      const kps = Math.max(1, t.keysPerStep ?? 2);
      if (k > kps) push({ level: "warning", where: w0, message: `${lv} · semaine à ${n} séance(s) : ${k} séances clés pour ${kps} par palier, un palier dure moins d'une semaine. Progression rapide pour une reprise.` });
    }
    // Paliers tenus au moins deux semaines (à 3 séances) : ce sont des semaines répétées, la stabilité tient lieu d'allègement.
    const weeksPerStep = Math.max(1, t.keysPerStep ?? 2) / Math.max(1, [...patternFor(t, 3)].filter((ch) => ch === "K").length);
    if (t.deload.every <= 0 && weeksPerStep < 2) push({ level: "warning", where: w0, message: `${lv} : pas de semaine allégée. Prévois une semaine plus légère toutes les 3 à 5 semaines, ou des semaines répétées.` });
    else if (t.deload.every > 6) push({ level: "warning", where: w0, message: `${lv} : une semaine allégée toutes les ${t.deload.every} semaines, c'est rare pour une reprise (3 à 5 conseillé).` });
    // Règle de coach : un seul curseur à la fois d'un palier au suivant.
    for (let i = 1; i < t.steps.length; i++) {
      const a = t.steps[i - 1].sessions[0];
      const b = t.steps[i].sessions[0];
      if (!a || !b || a.type !== b.type) continue;
      const ups = Object.keys(b.params ?? {}).filter((k) => k !== "b" && (b.params[k] ?? 0) > (a.params?.[k] ?? 0));
      if (ups.length > 1) push({ level: "warning", where: { ...w0, step: i }, message: `${at(i)} : ${ups.join(" et ")} augmentent en même temps. Un seul curseur à la fois, c'est plus sûr.` });
    }
  }
  if (issues.some((x) => x.level === "error")) return issues;
  return [...issues, ...simulateWarnings(t0, c)];
}

/** Avertissements tirés d'une simulation sur les profils de référence. */
export function simulateWarnings(t: ProgramTemplate, c: Catalog): Issue[] {
  const out = new Map<string, Issue>();
  const add = (message: string, step?: number) => out.set(message, { level: "warning", where: { program: t.id, step }, message });
  for (const { name, profile } of REFERENCE_PROFILES) {
    if (!levelsOf(t).includes(profile.level)) continue;
    if (profile.healthFlag && !t.requires.allowHealthFlag) continue;
    if (profile.longestRunMin < t.requires.minLongestRunMin) continue;
    let sessions;
    try {
      sessions = plan(newState(t, profile, c), profile, 20406);
    } catch (e) {
      add(`Simulation impossible (${name}) : ${(e as Error).message}`);
      continue;
    }
    if (!sessions.length) {
      add(`Aucune séance pour le profil ${name}.`);
      continue;
    }
    // Volume hebdomadaire. La règle des +10 % vaut pour la course ; la marche peut progresser plus vite.
    const weekly = new Map<number, { total: number; run: number }>();
    sessions.forEach((s) => {
      const w = weekly.get(s.week) ?? { total: 0, run: 0 };
      w.total += minutesOf(s);
      w.run += s.blocks.filter((b) => b.kind === "RUN" || b.kind === "FAST").reduce((a, b) => a + b.seconds, 0) / 60;
      weekly.set(s.week, w);
    });
    const weeks = [...weekly.keys()].sort((a, b) => a - b);
    // On compare chaque semaine à la dernière semaine normale : une semaine allégée ne sert pas de référence.
    // La hausse permise s'étale sur les semaines tenues au même niveau : +10 % par semaine en moyenne.
    let ref: number | null = null;
    // Semaines normales tenues au niveau actuel (une semaine allégée ne compte pas).
    let held = 0;
    for (const wk of weeks) {
      const cur = weekly.get(wk)!;
      const deload = sessions.find((s) => s.week === wk)?.deload;
      if (deload) continue;
      if (ref != null) {
        const prev = weekly.get(ref)!;
        const w = `entre les semaines ${ref + 1} et ${wk + 1}`;
        // En dessous de 30 min de course par semaine, on progresse par paliers : la règle des +10 % ne s'applique qu'au-delà.
        const h = Math.max(1, held);
        if (prev.run >= 30 && cur.run > prev.run * (1 + 0.1 * h) && cur.run - prev.run > 10) {
          add(`${name} : la course passe de ${Math.round(prev.run)} à ${Math.round(cur.run)} min par semaine ${w} (plus de +10 %${h > 1 ? ` par semaine en moyenne sur ${h} semaines` : ""}).`);
        } else if (cur.total > prev.total * 1.25 && cur.total - prev.total > 15) {
          add(`${name} : le volume total passe de ${prev.total} à ${cur.total} min ${w} (plus de +25 %).`);
        }
      }
      held = ref != null && cur.run > weekly.get(ref)!.run + 0.5 ? 1 : held + 1;
      ref = wk;
    }
    const longest = Math.max(...sessions.map(minutesOf));
    if (longest > profile.minutesPerSession + 15) add(`${name} : une séance dure ${longest} min, bien plus que les ${profile.minutesPerSession} min disponibles.`);
    // Deux séances avec course à moins de 48 h.
    const hard = sessions.filter((s) => s.blocks.some((b) => b.kind === "RUN" || b.kind === "FAST"));
    for (let i = 1; i < hard.length; i++) {
      if (hard[i].day - hard[i - 1].day < 2) {
        add(`${name} : deux séances avec course à moins de 48 h (${["", "lun", "mar", "mer", "jeu", "ven", "sam", "dim"][dayOfWeek(hard[i - 1].day)]} et lendemain).`);
        break;
      }
    }
  }
  return [...out.values()];
}

export function validateCatalog(c: Catalog): Issue[] {
  const issues: Issue[] = [];
  const dup = (ids: string[]) => ids.filter((id, i) => ids.indexOf(id) !== i);
  for (const id of dup(c.sessionTypes.map((t) => t.id))) issues.push({ level: "error", where: { sessionType: id }, message: `Deux types de séance ont l'identifiant « ${id} ».` });
  for (const id of dup(c.programs.map((p) => p.id))) issues.push({ level: "error", where: { program: id }, message: `Deux programmes ont l'identifiant « ${id} ».` });
  const types = new Set(c.sessionTypes.map((t) => t.id));
  for (const [role, id] of Object.entries(c.specials)) {
    if (!types.has(id)) issues.push({ level: "error", where: {}, message: `Séance spéciale « ${role} » : type inconnu « ${id} ».` });
  }
  if (!c.programs.some((p) => p.enabled)) issues.push({ level: "error", where: {}, message: "Aucun programme visible dans l'app." });
  c.sessionTypes.forEach((t) => issues.push(...validateSessionType(t)));
  c.programs.forEach((p) => issues.push(...validateProgram(p, c)));
  return issues;
}
