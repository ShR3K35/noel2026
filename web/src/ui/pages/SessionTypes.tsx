import { useState } from "react";
import { build, speedsFor } from "../../engine/engine";
import type { BlockT, ParamDef, SessionType } from "../../engine/types";
import { BLOCK_KINDS, BLOCK_LABELS, PACE_LABELS, PACES, SESSION_KIND_LABELS, SESSION_KINDS } from "../../engine/types";
import { REFERENCE_PROFILES } from "../../engine/validate";
import { clone, useRoute, useStore } from "../store";
import { Field, Issues, Num, SessionDetail } from "../widgets";
import { uniqueId } from "./Programs";

export function SessionTypes() {
  const { draft, update, issues } = useStore();
  const [, go] = useRoute();
  const usedBy = (id: string) => draft.programs.filter((p) =>
    p.steps.some((s) => s.sessions.some((r) => r.type === id)) || p.easy.types.includes(id) || p.easy.fallback === id).map((p) => p.name);
  const specials = Object.values(draft.specials);
  const create = (from?: SessionType) => {
    const id = uniqueId(from ? `${from.id}-copie` : "nouvelle-seance", draft.sessionTypes.map((t) => t.id));
    update((c) => ({
      ...c,
      sessionTypes: [...c.sessionTypes, from ? { ...clone(from), id, name: `${from.name} (copie)` } : {
        id, name: "Nouvelle séance", kind: "WALK", title: "Nouvelle séance", detail: "{minutes} min", purpose: "",
        params: [{ name: "minutes", label: "Durée totale", default: 25, min: 10, max: 90, unit: "min" }],
        blocks: [
          { kind: "WARMUP", minutes: "3", pace: "warm", paceFactor: 1, incline: "0" },
          { kind: "WALK", minutes: "minutes-6", pace: "walk", paceFactor: 1, incline: "0" },
          { kind: "COOLDOWN", minutes: "3", pace: "warm", paceFactor: 1, incline: "0" },
        ],
      }],
    }));
    go(`seances/${id}`);
  };
  return (
    <>
      <div className="row between">
        <div>
          <h1>Types de séance</h1>
          <p className="muted" style={{ margin: "4px 0 0" }}>Les briques des programmes. Les vitesses sont relatives au sportif : le téléphone les calcule avec son profil.</p>
        </div>
        <button className="primary" onClick={() => create()}>Nouveau type</button>
      </div>
      <div className="grid">
        {draft.sessionTypes.map((t) => {
          const users = usedBy(t.id);
          const own = issues.filter((i) => i.where.sessionType === t.id);
          return (
            <div key={t.id} className="card">
              <div className="row between"><h2>{t.name}</h2><span className="tag">{SESSION_KIND_LABELS[t.kind]}</span></div>
              <p className="small muted" style={{ margin: 0 }}>{t.purpose || t.detail}</p>
              <div className="row small">
                {specials.includes(t.id) && <span className="tag on">Utilisé par l'app</span>}
                <span className="tag">{users.length ? `${users.length} programme(s)` : "Inutilisé"}</span>
                {own.length > 0 && <span className="tag" style={{ color: "var(--warn)" }}>{own.length} point(s)</span>}
              </div>
              <div className="row">
                <button className="primary" onClick={() => go(`seances/${t.id}`)}>Modifier</button>
                <button onClick={() => create(t)}>Dupliquer</button>
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}

const newBlock = (): BlockT => ({ kind: "WALK", minutes: "5", pace: "walk", paceFactor: 1, incline: "0" });

function BlocksEditor({ blocks, params, onChange, depth = 0 }: { blocks: BlockT[]; params: ParamDef[]; onChange: (b: BlockT[]) => void; depth?: number }) {
  const set = (i: number, b: BlockT) => onChange(blocks.map((x, k) => (k === i ? b : x)));
  const move = (i: number, d: number) => {
    const j = i + d;
    if (j < 0 || j >= blocks.length) return;
    const n = [...blocks];
    [n[i], n[j]] = [n[j], n[i]];
    onChange(n);
  };
  const paramNames = params.map((p) => p.name);
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {blocks.map((b, i) => b.kind === "REPEAT" ? (
        <div key={i} className="block repeat">
          <div className="row between">
            <label className="row small" style={{ gap: 6, fontWeight: 700 }}>Répéter
              <select aria-label="Nombre de répétitions" value={b.repeat} onChange={(e) => set(i, { ...b, repeat: e.target.value })}>
                {paramNames.map((n) => <option key={n} value={n}>{params.find((p) => p.name === n)?.label || n}</option>)}
              </select>
              fois
            </label>
            <div className="row">
              <button className="icon" aria-label="Monter" onClick={() => move(i, -1)}>↑</button>
              <button className="icon" aria-label="Descendre" onClick={() => move(i, 1)}>↓</button>
              <button className="icon danger" onClick={() => onChange(blocks.filter((_, k) => k !== i))}>Supprimer</button>
            </div>
          </div>
          <BlocksEditor blocks={b.blocks ?? []} params={params} depth={depth + 1} onChange={(bs) => set(i, { ...b, blocks: bs })} />
        </div>
      ) : (
        <div key={i} className="block">
          <select aria-label="Type de bloc" value={b.kind} onChange={(e) => set(i, { ...b, kind: e.target.value as BlockT["kind"] })}>
            {BLOCK_KINDS.map((k) => <option key={k} value={k}>{BLOCK_LABELS[k]}</option>)}
          </select>
          <label className="row small" style={{ gap: 4 }}>durée
            <input aria-label="Durée en minutes" list="param-names" value={b.minutes} style={{ width: 120 }} onChange={(e) => set(i, { ...b, minutes: e.target.value })} />
            min
          </label>
          <select aria-label="Allure" value={b.pace} onChange={(e) => set(i, { ...b, pace: e.target.value as BlockT["pace"] })}>
            {PACES.map((p) => <option key={p} value={p}>{PACE_LABELS[p]}</option>)}
          </select>
          <label className="row small" style={{ gap: 4 }}>×<Num label="Facteur d'allure" step={0.05} value={b.paceFactor} onChange={(v) => set(i, { ...b, paceFactor: v })} /></label>
          <label className="row small" style={{ gap: 4 }}>pente
            <select aria-label="Pente" value={b.incline === "profile" ? "profile" : "fixed"} onChange={(e) => set(i, { ...b, incline: e.target.value === "profile" ? "profile" : "0" })}>
              <option value="fixed">fixe</option>
              <option value="profile">celle du tapis</option>
            </select>
            {b.incline !== "profile" && <Num label="Pente en %" value={Number(b.incline) || 0} onChange={(v) => set(i, { ...b, incline: String(v) })} />}
          </label>
          <div className="row" style={{ marginLeft: "auto" }}>
            <button className="icon" aria-label="Monter" onClick={() => move(i, -1)}>↑</button>
            <button className="icon" aria-label="Descendre" onClick={() => move(i, 1)}>↓</button>
            <button className="icon danger" aria-label="Supprimer le bloc" onClick={() => onChange(blocks.filter((_, k) => k !== i))}>×</button>
          </div>
        </div>
      ))}
      <div className="row">
        <button className="ghost icon" onClick={() => onChange([...blocks, newBlock()])}>+ bloc</button>
        {depth === 0 && paramNames.length > 0 && (
          <button className="ghost icon" onClick={() => onChange([...blocks, { kind: "REPEAT", repeat: paramNames[0], minutes: "0", pace: "warm", paceFactor: 1, incline: "0", blocks: [newBlock()] }])}>+ répétition</button>
        )}
      </div>
    </div>
  );
}

export function SessionTypeEditor({ id }: { id: string }) {
  const { draft, update, issues } = useStore();
  const [, go] = useRoute();
  const [k, setK] = useState(1);
  const t = draft.sessionTypes.find((x) => x.id === id);
  if (!t) return <div className="card empty">Type de séance introuvable. <a href="#/seances">Retour</a></div>;
  const set = (fn: (x: SessionType) => void) => update((c) => {
    fn(c.sessionTypes.find((x) => x.id === id)!);
    return c;
  });
  const used = draft.programs.filter((p) => p.steps.some((s) => s.sessions.some((r) => r.type === id)) || p.easy.types.includes(id) || p.easy.fallback === id);
  const special = Object.entries(draft.specials).find(([, v]) => v === id)?.[0];
  const { profile, name } = REFERENCE_PROFILES[k];
  let preview;
  try {
    const params = Object.fromEntries(t.params.map((p) => [p.name, p.default]));
    preview = build(t, params, profile, speedsFor(profile, 1, draft.paces), 0, 0, true, false, 0);
  } catch (e) {
    preview = null;
  }
  return (
    <>
      <div className="row between">
        <div>
          <a href="#/seances" className="small">‹ Types de séance</a>
          <h1>{t.name}</h1>
          <p className="small muted" style={{ margin: 0 }}>Identifiant : {t.id}{special ? ` · utilisé par l'app pour : ${special}` : ""}</p>
        </div>
        <button className="danger" disabled={used.length > 0 || !!special} title={used.length ? "Utilisé par des programmes" : undefined} onClick={() => {
          if (!confirm(`Supprimer « ${t.name} » ?`)) return;
          update((c) => ({ ...c, sessionTypes: c.sessionTypes.filter((x) => x.id !== id) }));
          go("seances");
        }}>Supprimer</button>
      </div>
      <div className="cols">
        <div className="wide">
          <section className="card">
            <h2>Description</h2>
            <div className="row" style={{ alignItems: "flex-start" }}>
              <Field label="Nom"><input value={t.name} onChange={(e) => set((x) => { x.name = e.target.value; })} /></Field>
              <Field label="Catégorie" hint="Sert aux statistiques et à l'export (marche ou course).">
                <select value={t.kind} onChange={(e) => set((x) => { x.kind = e.target.value as SessionType["kind"]; })}>
                  {SESSION_KINDS.map((s) => <option key={s} value={s}>{SESSION_KIND_LABELS[s]}</option>)}
                </select>
              </Field>
              <label className="check" style={{ marginTop: 22 }}><input type="checkbox" checked={!!t.needsIncline} onChange={(e) => set((x) => { x.needsIncline = e.target.checked; })} />Demande une pente</label>
            </div>
            <Field label="Titre affiché" hint="{incline} = pente du tapis du sportif."><input value={t.title} onChange={(e) => set((x) => { x.title = e.target.value; })} /></Field>
            <Field label="Détail affiché" hint="Paramètres entre accolades : {minutes}, {reps}… Allures : {pace:walk}, {pace:run}, {pace:fast}…">
              <input value={t.detail} onChange={(e) => set((x) => { x.detail = e.target.value; })} />
            </Field>
            <Field label="Pourquoi cette séance (affiché au sportif)"><textarea value={t.purpose} onChange={(e) => set((x) => { x.purpose = e.target.value; })} /></Field>
          </section>
          <section className="card">
            <h2>Paramètres</h2>
            <p className="small muted" style={{ margin: 0 }}>Ce que chaque palier d'un programme peut régler. « minutes » est le paramètre raccourci dans la version courte.</p>
            <div className="scroll">
              <table>
                <thead><tr><th>Nom</th><th>Libellé</th><th>Défaut</th><th>Min</th><th>Max</th><th>Unité</th><th /></tr></thead>
                <tbody>
                  {t.params.map((p, i) => (
                    <tr key={i}>
                      <td><input aria-label="Nom technique" value={p.name} style={{ width: 90 }} onChange={(e) => set((x) => { x.params[i].name = e.target.value.replace(/[^A-Za-z0-9_]/g, ""); })} /></td>
                      <td><input aria-label="Libellé" value={p.label} onChange={(e) => set((x) => { x.params[i].label = e.target.value; })} /></td>
                      <td><Num label="Défaut" value={p.default} onChange={(v) => set((x) => { x.params[i].default = v; })} /></td>
                      <td><Num label="Minimum" value={p.min} onChange={(v) => set((x) => { x.params[i].min = v; })} /></td>
                      <td><Num label="Maximum" value={p.max} onChange={(v) => set((x) => { x.params[i].max = v; })} /></td>
                      <td><input aria-label="Unité" value={p.unit} style={{ width: 60 }} onChange={(e) => set((x) => { x.params[i].unit = e.target.value; })} /></td>
                      <td><button className="icon danger" aria-label="Retirer" onClick={() => set((x) => { x.params.splice(i, 1); })}>×</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div><button className="ghost" onClick={() => set((x) => { x.params.push({ name: `p${x.params.length + 1}`, label: "", default: 5, min: 1, max: 60, unit: "min" }); })}>+ paramètre</button></div>
          </section>
          <section className="card">
            <h2>Blocs</h2>
            <p className="small muted" style={{ margin: 0 }}>Durée : un nombre, un paramètre (« a ») ou un paramètre moins une constante (« minutes-6 »).</p>
            <datalist id="param-names">{t.params.map((p) => <option key={p.name} value={p.name} />)}</datalist>
            <BlocksEditor blocks={t.blocks} params={t.params} onChange={(b) => set((x) => { x.blocks = b; })} />
          </section>
        </div>
        <aside className="narrow">
          <div className="card">
            <h3>Aperçu avec les valeurs par défaut</h3>
            <select aria-label="Profil" value={k} onChange={(e) => setK(Number(e.target.value))}>
              {REFERENCE_PROFILES.map((p, i) => <option key={p.name} value={i}>{p.name} · VR {p.profile.vr} km/h</option>)}
            </select>
            {preview ? <SessionDetail s={preview} /> : <div className="issue error">Aperçu impossible pour {name}.</div>}
          </div>
          <div className="card">
            <h3>Garde-fous</h3>
            <Issues issues={issues.filter((i) => i.where.sessionType === id)} />
            <p className="small muted" style={{ margin: 0 }}>Utilisé par : {used.length ? used.map((p) => p.name).join(", ") : "aucun programme"}.</p>
          </div>
        </aside>
      </div>
    </>
  );
}
