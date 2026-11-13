// Format du catalogue de programmes. Miroir de app/src/main/java/fr/relance/app/core/Catalog.kt :
// tout changement ici doit être reporté côté app (les jeux de tests partagés le vérifient).

export type Level = "N1" | "N2" | "N3" | "N4";
export const LEVELS: Level[] = ["N1", "N2", "N3", "N4"];
export const LEVEL_LABELS: Record<Level, string> = {
  N1: "N1 · Redémarrage",
  N2: "N2 · Marcheur",
  N3: "N3 · Marche / course",
  N4: "N4 · Reprise course",
};

export type SessionKind =
  | "WALK" | "HILL" | "INTERVALS" | "CONTINUOUS" | "RECOVERY" | "TEST" | "PERF_INTERVALS" | "PERF_TEMPO";
export const SESSION_KINDS: SessionKind[] = ["WALK", "HILL", "INTERVALS", "CONTINUOUS", "RECOVERY", "TEST", "PERF_INTERVALS", "PERF_TEMPO"];
export const SESSION_KIND_LABELS: Record<SessionKind, string> = {
  WALK: "Marche", HILL: "Marche en côte", INTERVALS: "Marche / course", CONTINUOUS: "Course continue",
  RECOVERY: "Récupération", TEST: "Test de forme", PERF_INTERVALS: "Fractionné", PERF_TEMPO: "Allure soutenue",
};

export type BlockKind = "WARMUP" | "WALK" | "HILL" | "RUN" | "FAST" | "TEST" | "COOLDOWN";
export const BLOCK_KINDS: BlockKind[] = ["WARMUP", "WALK", "HILL", "RUN", "FAST", "TEST", "COOLDOWN"];
export const BLOCK_LABELS: Record<BlockKind | "REPEAT", string> = {
  WARMUP: "Échauffement", WALK: "Marche", HILL: "Marche en côte", RUN: "Trot", FAST: "Allure soutenue",
  TEST: "Test", COOLDOWN: "Retour au calme", REPEAT: "Répéter",
};

export type PaceName = "warm" | "recovery" | "walk" | "hill" | "run" | "tempo" | "fast" | "vr" | "kmh";
export const PACES: PaceName[] = ["warm", "recovery", "walk", "hill", "run", "tempo", "fast", "vr", "kmh"];
export const PACE_LABELS: Record<PaceName, string> = {
  warm: "Marche lente (échauffement)", recovery: "Récupération", walk: "Marche active", hill: "Marche en côte",
  run: "Course facile", tempo: "Allure soutenue", fast: "Rapide", vr: "Vitesse de référence (test)", kmh: "Vitesse fixe (km/h)",
};

export interface Paces {
  warm: number; recovery: number; walk: number; hill: number;
  runOfVr: number; runMin: number; runMax: number; tempoOfRun: number; fastOfRun: number;
}

export interface Specials { test: string; recovery: string; flatWalk: string }

export interface ParamDef { name: string; label: string; default: number; min: number; max: number; unit: string }

export interface BlockT {
  kind: BlockKind | "REPEAT";
  minutes: string;
  pace: PaceName;
  paceFactor?: number;
  incline?: string;
  /** Vitesse fixe en km/h quand pace vaut « kmh ». */
  kmh?: number;
  repeat?: string;
  blocks?: BlockT[];
}

export interface SessionType {
  id: string; name: string; kind: SessionKind; title: string; detail: string; purpose: string;
  needsIncline?: boolean; params: ParamDef[]; blocks: BlockT[];
}

export interface SessionRef { type: string; params: Record<string, number> }
export interface StepT { sessions: SessionRef[]; levels?: Level[]; note?: string; easy?: SessionRef }

/** Variante d'un programme pour un niveau ; les réglages absents reprennent ceux du programme. */
export interface VariantT {
  steps: StepT[]; description?: string; keysPerStep?: number; startAtMastered?: boolean;
  easy?: EasyT; deload?: DeloadT; weekPatterns?: Record<string, string>; weekNotes?: Record<string, string>;
}
export interface Goal { kind: "none" | "duration" | "distance"; minutes: number; km: number }
export interface Requires { minLongestRunMin: number; allowHealthFlag: boolean }
export interface RecommendRule { levels: Level[]; minRun: number; maxRun: number; healthFlag?: boolean }
export interface EasyT { types: string[]; fallback: string; startMinutes: number; stepMinutes: number; maxMinutes: number }
export interface DeloadT { every: number; testAtEnd: boolean; factor?: number }

export interface ProgramTemplate {
  id: string; name: string; validation: string; description: string; order: number; enabled: boolean;
  goal: Goal; requires: Requires; recommend: RecommendRule[];
  steps: StepT[]; startAtMastered: boolean; easy: EasyT; deload: DeloadT;
  /** Séances clés à réussir pour valider un palier (2 par défaut). */
  keysPerStep?: number;
  /** Deux séances faciles de suite font sauter un palier (vrai par défaut). */
  allowSkip?: boolean;
  weekPatterns: Record<string, string>; weekNotes: Record<string, string>;
  /** Variantes par niveau ; vide : ancien format (paliers filtrés par niveau). */
  variants?: Partial<Record<Level, VariantT>>;
}

export interface Catalog {
  schema: number; version: number; publishedAt: string; notes: string;
  paces: Paces; specials: Specials; sessionTypes: SessionType[]; programs: ProgramTemplate[];
}

/** Profil d'un sportif : seul le téléphone le connaît ; ici il sert au simulateur. */
export interface Profile {
  age: number; weightKg: number; level: Level; vr: number; longestRunMin: number; healthFlag: boolean;
  days: number[]; minutesPerSession: number; inclinePct: number; treadmillMaxKmh: number;
}

export interface Block { kind: BlockKind; seconds: number; speedKmh: number; inclinePct: number }

export interface PlannedSession {
  day: number; kind: SessionKind; title: string; detail: string; blocks: Block[];
  isKey: boolean; week: number; deload: boolean;
}

export interface ResolvedStep { sessions: SessionRef[]; note: string; easy?: SessionRef }

/** Position dans un programme (sous-ensemble de Program côté app, sans l'adaptation aux retours de séance). */
export interface ProgramState {
  template: ProgramTemplate; catalog: Catalog; steps: ResolvedStep[];
  stepIndex: number; week: number; sessionInWeek: number; keysInStep: number; speedFactor: number;
}
