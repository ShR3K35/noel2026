import { useMemo, useState } from "react";
import { minutesOf, newState, plan, programWeeks, trainingDays } from "../../engine/engine";
import type { Catalog, Level, ProgramTemplate, SessionRef, StepT } from "../../engine/types";
import { LEVELS } from "../../engine/types";
import { REFERENCE_PROFILES } from "../../engine/validate";
import { clone, useRoute, useStore } from "../store";
import { Field, Issues, LevelChips, Num } from "../widgets";

const ROLE_HELP = "K = séance clé (le palier de la semaine) · E = endurance · R = récupération";

function ParamInputs({ refx, catalog, onChange, distanceGoal }: { refx: SessionRef; catalog: Catalog; onChange: (r: SessionRef) => void; distanceGoal: boolean }) {
  const type = catalog.sessionTypes.find((t) => t.id === refx.type);
  if (!type) return <span className="small" style={{ color: "var(--error)" }}>Type inconnu</span>;
  const inKm = refx.params.km != null;
  return (
    <div className="row">
      {type.params.map((p) => {
        if (p.name === "minutes" && inKm) return null;
        return (
          <label key={p.name} className="row small" style={{ gap: 4 }}>
            {p.label || p.name}
            <Num label={p.label || p.name} value={refx.params[p.name] ?? p.default} min={p.min} max={p.max}
              onChange={(v) => onChange({ ...refx, params: { ...refx.params, [p.name]: v } })} />
            <span className="muted">{p.unit}</span>
          </label>
        );
      })}
      {type.kind === "CONTINUOUS" && (distanceGoal || inKm) && (
        <label className="check small">
          <input type="checkbox" checked={inKm} onChange={(e) => {
            const { km, minutes, ...rest } = refx.params;
            onChange({ ...refx, params: e.target.checked ? { ...rest, km: 5 } : { ...rest, minutes: minutes ?? 30 } });
          }} />
          en distance
        </label>
      )}
      {inKm && (
        <label className="row small" style={{ gap: 4 }}>
          <Num label="Distance" value={refx.params.km} step={0.5} onChange={(v) => onChange({ ...refx, params: { ...refx.params, km: v } })} />
          <span className="muted">km (durée calculée selon l'allure du sportif)</span>
        </label>
      )}
    </div>
  );
}

function StepEditor({ step, index, count, t, catalog, onChange, onMove, onRemove, onDuplicate, issues }: {
  step: StepT; index: number; count: number; t: ProgramTemplate; catalog: Catalog; onChange: (s: StepT) => void;
  onMove: (d: -1 | 1) => void; onRemove: () => void; onDuplicate: () => void; issues: string[];
}) {
  const setRef = (i: number, r: SessionRef) => onChange({ ...step, sessions: step.sessions.map((x, k) => (k === i ? r : x)) });
  return (
    <div className="step">
      <div className="row between">
        <div className="row">
          <span className="num">{index + 1}</span>
          <LevelChips value={step.levels} onChange={(levels) => onChange({ ...step, levels })} />
        </div>
        <div className="row">
          <button className="icon" aria-label={`Monter le palier ${index + 1}`} disabled={index === 0} onClick={() => onMove(-1)}>↑</button>
          <button className="icon" aria-label={`Descendre le palier ${index + 1}`} disabled={index === count - 1} onClick={() => onMove(1)}>↓</button>
          <button className="icon" onClick={onDuplicate}>Dupliquer</button>
          <button className="icon danger" onClick={onRemove}>Supprimer</button>
        </div>
      </div>
      {step.sessions.map((r, i) => (
        <div key={i} className="row">
          {step.sessions.length > 1 && <span className="tag">Clé {i + 1}</span>}
          <select aria-label="Type de séance" value={r.type} onChange={(e) => {
            const type = catalog.sessionTypes.find((x) => x.id === e.target.value)!;
            setRef(i, { type: type.id, params: Object.fromEntries(type.params.map((p) => [p.name, p.default])) });
          }}>
            {catalog.sessionTypes.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
          <ParamInputs refx={r} catalog={catalog} distanceGoal={t.goal.kind === "distance"} onChange={(nr) => setRef(i, nr)} />
          {step.sessions.length > 1 && (
            <button className="icon danger" aria-label="Retirer cette séance clé" onClick={() => onChange({ ...step, sessions: step.sessions.filter((_, k) => k !== i) })}>×</button>
          )}
        </div>
      ))}
      <div className="row">
        <button className="ghost icon" onClick={() => onChange({ ...step, sessions: [...step.sessions, clone(step.sessions[step.sessions.length - 1])] })}>
          + séance clé alternée
        </button>
        <input aria-label="Note du palier" placeholder="Note pour le coach (facultatif)" value={step.note} style={{ flex: 1, minWidth: 180 }}
          onChange={(e) => onChange({ ...step, note: e.target.value })} />
      </div>
      {issues.map((m, k) => <div key={k} className="issue warning">{m}</div>)}
    </div>
  );
}

/** Ajoute une série de paliers d'un coup : par exemple de la course continue de 15 à 30 min, de 5 en 5. */
function Generator({ catalog, onAdd }: { catalog: Catalog; onAdd: (steps: StepT[]) => void }) {
  const [typeId, setTypeId] = useState("continuous");
  const type = catalog.sessionTypes.find((t) => t.id === typeId);
  const [param, setParam] = useState("minutes");
  const [from, setFrom] = useState(15);
  const [to, setTo] = useState(30);
  const [by, setBy] = useState(5);
  const [levels, setLevels] = useState<Level[]>([...LEVELS]);
  const values: number[] = [];
  if (by > 0) for (let v = from; v <= to + 1e-9 && values.length < 40; v += by) values.push(Math.round(v * 100) / 100);
  return (
    <div className="card">
      <h3>Générer des paliers</h3>
      <div className="row">
        <select aria-label="Type" value={typeId} onChange={(e) => {
          setTypeId(e.target.value);
          const t = catalog.sessionTypes.find((x) => x.id === e.target.value);
          setParam(t?.params[0]?.name ?? "minutes");
        }}>
          {catalog.sessionTypes.filter((t) => t.params.length).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
        <select aria-label="Paramètre qui progresse" value={param} onChange={(e) => setParam(e.target.value)}>
          {type?.params.map((p) => <option key={p.name} value={p.name}>{p.label || p.name}</option>)}
        </select>
        <label className="row small" style={{ gap: 4 }}>de <Num label="De" value={from} onChange={setFrom} /></label>
        <label className="row small" style={{ gap: 4 }}>à <Num label="À" value={to} onChange={setTo} /></label>
        <label className="row small" style={{ gap: 4 }}>par <Num label="Pas" value={by} onChange={setBy} /></label>
      </div>
      <LevelChips value={levels} onChange={setLevels} />
      <p className="small muted" style={{ margin: 0 }}>{values.length ? `${values.length} paliers : ${values.join(", ")}` : "Aucun palier avec ces valeurs."}</p>
      <div><button disabled={!type || !values.length} onClick={() => {
        const base = Object.fromEntries(type!.params.map((p) => [p.name, p.default]));
        onAdd(values.map((v) => ({ sessions: [{ type: typeId, params: { ...base, [param]: v } }], levels: [...levels], note: "" })));
      }}>Ajouter à la fin</button></div>
    </div>
  );
}

function Preview({ t, catalog }: { t: ProgramTemplate; catalog: Catalog }) {
  const [k, setK] = useState(1);
  const { profile, name } = REFERENCE_PROFILES[k];
  const result = useMemo(() => {
    try {
      const st = newState(t, profile, catalog);
      const sessions = plan(st, profile, 20406);
      const weeks = new Map<number, number>();
      sessions.forEach((s) => weeks.set(s.week, (weeks.get(s.week) ?? 0) + minutesOf(s)));
      return { ok: true as const, st, sessions, weeks: [...weeks.entries()] };
    } catch (e) {
      return { ok: false as const, error: (e as Error).message };
    }
  }, [t, catalog, profile]);
  return (
    <div className="card">
      <h3>Aperçu</h3>
      <select aria-label="Profil de référence" value={k} onChange={(e) => setK(Number(e.target.value))}>
        {REFERENCE_PROFILES.map((p, i) => <option key={p.name} value={i}>{p.name}</option>)}
      </select>
      {!result.ok ? <div className="issue error">{result.error}</div> : !result.sessions.length ? (
        <p className="small muted">Aucune séance pour ce profil ({name}).</p>
      ) : (
        <>
          <p className="small" style={{ margin: 0 }}>
            {result.st.steps.length} paliers retenus · {programWeeks(t, result.st.steps.length, trainingDays(profile).length)} semaines · {result.sessions.length} séances
            · {profile.days.length} séances/semaine
          </p>
          <svg viewBox={`0 0 300 80`} width="100%" role="img" aria-label="Minutes d'entraînement par semaine">
            {(() => {
              const max = Math.max(...result.weeks.map(([, m]) => m), 1);
              const w = 300 / result.weeks.length;
              return result.weeks.map(([wk, m], i) => (
                <rect key={wk} x={i * w + 1} y={78 - (m / max) * 74} width={Math.max(1, w - 2)} height={(m / max) * 74} rx={2}
                  fill={result.sessions.find((s) => s.week === wk)?.deload ? "var(--walk)" : "var(--run)"} />
              ));
            })()}
          </svg>
          <p className="small muted" style={{ margin: 0 }}>Minutes par semaine (clair : semaine allégée).</p>
          <a href={`#/simulateur/${t.id}`}>Ouvrir dans le simulateur</a>
        </>
      )}
    </div>
  );
}

export function ProgramEditor({ id }: { id: string }) {
  const { draft, update, issues } = useStore();
  const [, go] = useRoute();
  const t = draft.programs.find((p) => p.id === id);
  if (!t) return <div className="card empty">Programme introuvable. <a href="#/programmes">Retour aux programmes</a></div>;
  const set = (fn: (p: ProgramTemplate) => void) => update((c) => {
    const p = c.programs.find((x) => x.id === id)!;
    fn(p);
    return c;
  });
  const own = issues.filter((i) => i.where.program === id);
  const stepIssues = (i: number) => own.filter((x) => x.where.step === i).map((x) => x.message);
  const moveStep = (i: number, d: -1 | 1) => set((p) => {
    const j = i + d;
    [p.steps[i], p.steps[j]] = [p.steps[j], p.steps[i]];
  });

  return (
    <>
      <div className="row between">
        <div>
          <a href="#/programmes" className="small">‹ Programmes</a>
          <h1>{t.name}</h1>
          <p className="small muted" style={{ margin: 0 }}>Identifiant : {t.id}</p>
        </div>
        <div className="row">
          <label className="check"><input type="checkbox" checked={t.enabled} onChange={(e) => set((p) => { p.enabled = e.target.checked; })} />Visible dans l'app</label>
          <button onClick={() => go(`simulateur/${t.id}`)}>Simuler</button>
          <button className="danger" onClick={() => {
            if (!confirm(`Supprimer « ${t.name} » du brouillon ? Les sportifs qui l'ont commencé le gardent jusqu'au bout.`)) return;
            update((c) => ({ ...c, programs: c.programs.filter((p) => p.id !== id) }));
            go("programmes");
          }}>Supprimer</button>
        </div>
      </div>

      <div className="cols">
        <div className="wide">
          <section className="card" aria-labelledby="general">
            <h2 id="general">Général</h2>
            <div className="row" style={{ alignItems: "flex-start" }}>
              <Field label="Nom"><input value={t.name} onChange={(e) => set((p) => { p.name = e.target.value; })} /></Field>
              <Field label="Objectif validé" hint="Affiché au sportif quand il réussit."><input value={t.validation} onChange={(e) => set((p) => { p.validation = e.target.value; })} /></Field>
            </div>
            <Field label="Description"><textarea value={t.description} onChange={(e) => set((p) => { p.description = e.target.value; })} /></Field>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Type d'objectif">
                <select value={t.goal.kind} onChange={(e) => set((p) => { p.goal.kind = e.target.value as ProgramTemplate["goal"]["kind"]; })}>
                  <option value="duration">Durée de course</option>
                  <option value="distance">Distance</option>
                  <option value="none">Aucun (entretien, performance)</option>
                </select>
              </Field>
              {t.goal.kind === "duration" && <Field label="Minutes"><Num label="Minutes" value={t.goal.minutes} onChange={(v) => set((p) => { p.goal.minutes = v; })} /></Field>}
              {t.goal.kind === "distance" && <Field label="Kilomètres"><Num label="Kilomètres" step={0.5} value={t.goal.km} onChange={(v) => set((p) => { p.goal.km = v; })} /></Field>}
              <Field label="Course continue requise" hint="En minutes ; 0 = aucun prérequis."><Num label="Prérequis" value={t.requires.minLongestRunMin} onChange={(v) => set((p) => { p.requires.minLongestRunMin = v; })} /></Field>
              <label className="check"><input type="checkbox" checked={t.requires.allowHealthFlag} onChange={(e) => set((p) => { p.requires.allowHealthFlag = e.target.checked; })} />Ouvert en cas d'avis médical</label>
            </div>
            <div className="row">
              <label className="check"><input type="checkbox" checked={t.startAtMastered} onChange={(e) => set((p) => { p.startAtMastered = e.target.checked; })} />Démarrer au dernier palier déjà maîtrisé</label>
              <span className="small muted">Un sportif qui court déjà 20 min saute les paliers plus faciles.</span>
            </div>
            <h3>Conseillé à</h3>
            {t.recommend.map((r, i) => (
              <div key={i} className="row">
                <LevelChips value={r.levels} onChange={(levels) => set((p) => { p.recommend[i].levels = levels; })} />
                <label className="row small" style={{ gap: 4 }}>course de <Num label="Minimum" value={r.minRun} onChange={(v) => set((p) => { p.recommend[i].minRun = v; })} /></label>
                <label className="row small" style={{ gap: 4 }}>à <Num label="Maximum" value={r.maxRun} onChange={(v) => set((p) => { p.recommend[i].maxRun = v; })} /> min</label>
                <label className="check small"><input type="checkbox" checked={!!r.healthFlag} onChange={(e) => set((p) => { p.recommend[i].healthFlag = e.target.checked; })} />avis médical</label>
                <button className="icon danger" aria-label="Retirer la règle" onClick={() => set((p) => { p.recommend.splice(i, 1); })}>×</button>
              </div>
            ))}
            <div><button className="ghost" onClick={() => set((p) => { p.recommend.push({ levels: [...LEVELS], minRun: 0, maxRun: 999 }); })}>+ règle de recommandation</button></div>
          </section>

          <section className="card" aria-labelledby="progression">
            <div className="row between">
              <h2 id="progression">Progression · {t.steps.length} paliers</h2>
              <span className="small muted">Un palier = la séance clé d'une semaine. Une semaine allégée toutes les {t.deload.every} semaines.</span>
            </div>
            <p className="small muted" style={{ margin: 0 }}>
              Les niveaux cochés indiquent pour qui le palier existe : un N3 saute les paliers réservés aux N1–N2. Les allures sont calculées
              par le téléphone à partir de la vitesse de référence du sportif.
            </p>
            {t.steps.map((s, i) => (
              <StepEditor key={i} step={s} index={i} count={t.steps.length} t={t} catalog={draft} issues={stepIssues(i)}
                onChange={(ns) => set((p) => { p.steps[i] = ns; })} onMove={(d) => moveStep(i, d)}
                onRemove={() => set((p) => { p.steps.splice(i, 1); })} onDuplicate={() => set((p) => { p.steps.splice(i + 1, 0, clone(s)); })} />
            ))}
            <div className="row">
              <button onClick={() => set((p) => { p.steps.push(clone(p.steps[p.steps.length - 1] ?? { sessions: [{ type: "walk", params: { minutes: 20 } }], levels: [...LEVELS], note: "" })); })}>+ palier</button>
            </div>
            <Generator catalog={draft} onAdd={(steps) => set((p) => { p.steps.push(...steps); })} />
          </section>

          <section className="card" aria-labelledby="semaine">
            <h2 id="semaine">Semaine type</h2>
            <p className="small muted" style={{ margin: 0 }}>{ROLE_HELP}. Le sportif choisit ses jours ; la séquence dépend de leur nombre.</p>
            <div className="row">
              {["1", "2", "3", "4"].map((n) => (
                <Field key={n} label={`${n} séance${n === "1" ? "" : "s"}`}>
                  <input value={t.weekPatterns[n] ?? ""} style={{ width: 90, textTransform: "uppercase", letterSpacing: 3 }} maxLength={4}
                    onChange={(e) => set((p) => { p.weekPatterns[n] = e.target.value.toUpperCase().replace(/[^KER]/g, ""); })} />
                </Field>
              ))}
            </div>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Séances clés par palier" hint="Un palier est validé après ce nombre de séances clés réussies.">
                <Num label="Séances clés par palier" value={t.keysPerStep ?? 2} min={1} max={6} onChange={(v) => set((p) => { p.keysPerStep = Math.max(1, Math.round(v)); })} />
              </Field>
              <div className="small" style={{ flex: 1, minWidth: 240 }}>
                <span className="label">Durée selon les jours</span>
                <div className="row" style={{ marginTop: 4 }}>
                  {[1, 2, 3, 4].map((n) => {
                    const prof = { ...REFERENCE_PROFILES[1].profile, days: [1, 3, 5, 7].slice(0, n) };
                    let w = "—";
                    try { w = String(programWeeks(t, newState(t, prof, draft).steps.length, n)); } catch { /* incomplet */ }
                    return <span key={n} className="tag">{n} j/sem : {w} sem.</span>;
                  })}
                </div>
                <span className="muted">Pour un N2. Moins de séances clés par semaine, programme plus long.</span>
              </div>
            </div>
            <h3>Séances d'endurance (E)</h3>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Types, à tour de rôle">
                <div className="chips">
                  {draft.sessionTypes.map((x) => {
                    const on = t.easy.types.includes(x.id);
                    return <button key={x.id} type="button" className={`chip ${on ? "on" : ""}`} aria-pressed={on}
                      onClick={() => set((p) => { p.easy.types = on ? p.easy.types.filter((y) => y !== x.id) : [...p.easy.types, x.id]; })}>{x.name}</button>;
                  })}
                </div>
              </Field>
            </div>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Sinon (sans pente)">
                <select value={t.easy.fallback} onChange={(e) => set((p) => { p.easy.fallback = e.target.value; })}>
                  {draft.sessionTypes.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                </select>
              </Field>
              <Field label="Durée semaine 1"><Num label="Départ" value={t.easy.startMinutes} onChange={(v) => set((p) => { p.easy.startMinutes = v; })} /></Field>
              <Field label="+ par semaine"><Num label="Progression" value={t.easy.stepMinutes} onChange={(v) => set((p) => { p.easy.stepMinutes = v; })} /></Field>
              <Field label="Maximum"><Num label="Maximum" value={t.easy.maxMinutes} onChange={(v) => set((p) => { p.easy.maxMinutes = v; })} /></Field>
            </div>
            <h3>Semaine allégée</h3>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Toutes les (semaines)" hint="0 = jamais"><Num label="Fréquence" value={t.deload.every} onChange={(v) => set((p) => { p.deload.every = v; })} /></Field>
              <Field label="Volume gardé"><Num label="Facteur" step={0.05} value={t.deload.factor ?? 0.7} onChange={(v) => set((p) => { p.deload.factor = v; })} /></Field>
              <label className="check"><input type="checkbox" checked={t.deload.testAtEnd} onChange={(e) => set((p) => { p.deload.testAtEnd = e.target.checked; })} />Test de forme en fin de semaine</label>
            </div>
            <h3>Mot du coach par semaine</h3>
            {Object.entries(t.weekNotes).sort((a, b) => Number(a[0]) - Number(b[0])).map(([w, text]) => (
              <div key={w} className="row">
                <span className="tag">Semaine {w}</span>
                <input value={text} style={{ flex: 1, minWidth: 200 }} aria-label={`Mot du coach semaine ${w}`} onChange={(e) => set((p) => { p.weekNotes[w] = e.target.value; })} />
                <button className="icon danger" aria-label="Retirer" onClick={() => set((p) => { delete p.weekNotes[w]; })}>×</button>
              </div>
            ))}
            <div><button className="ghost" onClick={() => set((p) => {
              const next = String(Math.max(0, ...Object.keys(p.weekNotes).map(Number)) + 1);
              p.weekNotes[next] = "";
            })}>+ mot du coach</button></div>
          </section>
        </div>

        <aside className="narrow" aria-label="Contrôles">
          <div className="card">
            <h3>Garde-fous</h3>
            <Issues issues={own} />
          </div>
          <Preview t={t} catalog={draft} />
          <div className="card small muted">
            Rappels de coach : un seul curseur à la fois (durée, vitesse ou pente) ; pas plus de +10 % de course par semaine au-delà de 30 min ;
            une semaine allégée toutes les 3 à 5 semaines ; jamais deux séances de course à moins de 48 h.
          </div>
        </aside>
      </div>
    </>
  );
}
