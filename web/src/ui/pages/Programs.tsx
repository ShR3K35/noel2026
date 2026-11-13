import { levelsOf, newState, programWeeks, trainingDays } from "../../engine/engine";
import type { ProgramTemplate } from "../../engine/types";
import { REFERENCE_PROFILES } from "../../engine/validate";
import { clone, useRoute, useStore } from "../store";

export function blankProgram(id: string): ProgramTemplate {
  return {
    id, name: "Nouveau programme", validation: "", description: "", order: 99, enabled: false,
    goal: { kind: "duration", minutes: 30, km: 0 }, requires: { minLongestRunMin: 0, allowHealthFlag: false }, recommend: [],
    steps: [],
    variants: { N2: { steps: [{ sessions: [{ type: "walk", params: { minutes: 20 } }], note: "" }] } },
    startAtMastered: true,
    easy: { types: ["hill", "walk"], fallback: "walk", startMinutes: 15, stepMinutes: 5, maxMinutes: 30 },
    deload: { every: 4, testAtEnd: true, factor: 0.7 }, keysPerStep: 2,
    weekPatterns: { "1": "K", "2": "KK", "3": "KEK", "4": "KEKR" }, weekNotes: {},
  };
}

export function uniqueId(base: string, taken: string[]): string {
  const slug = base.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 30) || "programme";
  let id = slug;
  for (let i = 2; taken.includes(id); i++) id = `${slug}-${i}`;
  return id;
}

export function Programs() {
  const { draft, update, issues } = useStore();
  const [, go] = useRoute();
  const sorted = [...draft.programs].sort((a, b) => a.order - b.order);
  const ref = REFERENCE_PROFILES[1].profile;

  const move = (id: string, dir: -1 | 1) => update((c) => {
    const list = [...c.programs].sort((a, b) => a.order - b.order);
    const i = list.findIndex((p) => p.id === id);
    const j = i + dir;
    if (j < 0 || j >= list.length) return c;
    [list[i], list[j]] = [list[j], list[i]];
    list.forEach((p, k) => (p.order = k + 1));
    return { ...c, programs: list };
  });

  const create = (from?: ProgramTemplate) => {
    const id = uniqueId(from ? `${from.id}-copie` : "nouveau-programme", draft.programs.map((p) => p.id));
    update((c) => {
      const p = from ? { ...clone(from), id, name: `${from.name} (copie)`, enabled: false } : blankProgram(id);
      p.order = Math.max(0, ...c.programs.map((x) => x.order)) + 1;
      return { ...c, programs: [...c.programs, p] };
    });
    go(`programmes/${id}`);
  };

  return (
    <>
      <div className="row between">
        <div>
          <h1>Programmes</h1>
          <p className="muted" style={{ margin: "4px 0 0" }}>L'ordre est celui du catalogue dans l'app. Un programme masqué reste en brouillon pour les sportifs.</p>
        </div>
        <div className="row">
          <button onClick={() => go("import")}>Importer un fichier…</button>
          <button className="primary" onClick={() => create()}>Nouveau programme</button>
        </div>
      </div>
      <div className="grid">
        {sorted.map((p, i) => {
          const own = issues.filter((x) => x.where.program === p.id);
          const errors = own.filter((x) => x.level === "error").length;
          let weeks = "—";
          try {
            const st = newState(p, ref, draft);
            weeks = String(programWeeks(p, st.steps.length, trainingDays(ref).length));
          } catch { /* programme incomplet */ }
          return (
            <div key={p.id} className="card">
              <div className="row between">
                <h2>{p.name}</h2>
                <span className={`tag ${p.enabled ? "on" : ""}`}>{p.enabled ? "Visible" : "Masqué"}</span>
              </div>
              <p className="small muted" style={{ margin: 0 }}>{p.description || p.validation || "Sans description"}</p>
              <div className="row small">
                <span className="tag">{levelsOf(p).join(" ") || "aucun niveau"}</span>
                <span className="tag">{weeks} sem. pour un N2</span>
                {errors > 0 && <span className="tag" style={{ color: "var(--error)" }}>{errors} erreur(s)</span>}
                {own.length - errors > 0 && <span className="tag" style={{ color: "var(--warn)" }}>{own.length - errors} point(s) d'attention</span>}
              </div>
              <div className="row">
                <button className="primary" onClick={() => go(`programmes/${p.id}`)}>Modifier</button>
                <button onClick={() => go(`simulateur/${p.id}`)}>Simuler</button>
                <button onClick={() => create(p)}>Dupliquer</button>
                <button className="icon" aria-label={`Monter ${p.name}`} disabled={i === 0} onClick={() => move(p.id, -1)}>↑</button>
                <button className="icon" aria-label={`Descendre ${p.name}`} disabled={i === sorted.length - 1} onClick={() => move(p.id, 1)}>↓</button>
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}
