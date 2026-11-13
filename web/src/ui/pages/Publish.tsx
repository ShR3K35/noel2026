import { useEffect, useState } from "react";
import type { Catalog } from "../../engine/types";
import { api, ApiError, type HistoryEntry } from "../api";
import { useStore } from "../store";
import { Issues } from "../widgets";

function diff(draft: Catalog, published: Catalog): string[] {
  const out: string[] = [];
  const byId = <T extends { id: string }>(xs: T[]) => new Map(xs.map((x) => [x.id, x]));
  for (const [label, d, p] of [["Programme", byId(draft.programs), byId(published.programs)], ["Type de séance", byId(draft.sessionTypes), byId(published.sessionTypes)]] as const) {
    for (const [id, x] of d) {
      const before = (p as Map<string, unknown>).get(id);
      const name = (x as { name: string }).name;
      if (!before) out.push(`${label} ajouté : ${name}`);
      else if (JSON.stringify(before) !== JSON.stringify(x)) out.push(`${label} modifié : ${name}`);
    }
    for (const [id, x] of p as Map<string, { name: string }>) if (!d.has(id)) out.push(`${label} supprimé : ${x.name}`);
  }
  if (JSON.stringify(draft.paces) !== JSON.stringify(published.paces)) out.push("Allures modifiées");
  return out;
}

const when = (iso: string) => (iso ? new Date(iso).toLocaleString("fr-FR", { dateStyle: "medium", timeStyle: "short" }) : "—");

export function Publish() {
  const { draft, published, issues, save, dirty, reload, replace } = useStore();
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const changes = diff(draft, published);
  const errors = issues.filter((i) => i.level === "error");
  const warnings = issues.filter((i) => i.level === "warning");
  const loadHistory = () => api.history().then((r) => setHistory(r.history)).catch(() => setHistory([]));
  useEffect(() => { loadHistory(); }, []);

  return (
    <>
      <div>
        <h1>Publication</h1>
        <p className="muted" style={{ margin: "4px 0 0" }}>
          Les téléphones récupèrent le catalogue publié une fois par jour, ou tout de suite avec « Mettre à jour les programmes ».
          Un sportif déjà engagé garde sa version jusqu'à la fin de son programme.
        </p>
      </div>
      <div className="cols">
        <div className="wide">
          <section className="card">
            <h2>Changements depuis la version {published.version}</h2>
            {changes.length ? <ul style={{ margin: 0 }}>{changes.map((c) => <li key={c}>{c}</li>)}</ul> : <p className="muted small">Le brouillon est identique au catalogue publié.</p>}
            <label className="field">Note de version (pour l'historique)
              <textarea value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Ex. : semaine allégée toutes les 3 semaines pour le 10 km" />
            </label>
            {errors.length > 0 && <div className="issue error">Publication impossible : {errors.length} erreur(s) à corriger.</div>}
            {message && <div className="issue warning" role="status">{message}</div>}
            <div className="row">
              <button className="primary" disabled={busy || errors.length > 0 || !changes.length} onClick={async () => {
                setBusy(true);
                setMessage("");
                try {
                  if (dirty) await save();
                  const r = await api.publish(notes);
                  setMessage(`Version ${r.version} publiée.`);
                  setNotes("");
                  await reload();
                  loadHistory();
                } catch (e) {
                  setMessage((e as ApiError).message);
                } finally {
                  setBusy(false);
                }
              }}>{busy ? "Publication…" : "Publier"}</button>
              <button disabled={!changes.length} onClick={() => {
                if (confirm("Abandonner les modifications du brouillon et repartir du catalogue publié ?")) replace(published);
              }}>Abandonner le brouillon</button>
            </div>
          </section>
          <section className="card">
            <h2>Points à vérifier</h2>
            <Issues empty="Rien à signaler." issues={[...errors, ...warnings].map((i) => {
              const who = i.where.program ? draft.programs.find((p) => p.id === i.where.program)?.name
                : i.where.sessionType ? draft.sessionTypes.find((t) => t.id === i.where.sessionType)?.name : undefined;
              return who ? { ...i, message: `${who} — ${i.message}` } : i;
            })} />
          </section>
        </div>
        <aside className="narrow">
          <div className="card">
            <h3>Historique</h3>
            {!history.length && <p className="small muted">Aucune publication pour l'instant : l'app utilise le catalogue initial (version 1).</p>}
            {history.map((h) => (
              <div key={h.version} className="week">
                <div className="row between"><strong>Version {h.version}</strong><span className="small muted">{when(h.publishedAt)}</span></div>
                {h.notes && <span className="small">{h.notes}</span>}
                <span className="small muted">{h.programs} programmes · {h.sessionTypes} types de séance</span>
                <div><button className="icon" onClick={async () => {
                  if (!confirm(`Remplacer le brouillon par la version ${h.version} ? Il faudra ensuite la publier.`)) return;
                  const r = await api.restore(h.version);
                  replace(r.draft);
                }}>Reprendre dans le brouillon</button></div>
              </div>
            ))}
            <div className="week"><button className="icon" onClick={async () => {
              if (!confirm("Remplacer le brouillon par le catalogue initial livré avec l'app ?")) return;
              const r = await api.restore(0);
              replace(r.draft);
            }}>Repartir du catalogue initial</button></div>
          </div>
        </aside>
      </div>
    </>
  );
}
