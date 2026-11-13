import { useMemo, useState } from "react";
import { forLevel, levelsOf, newState, programWeeks } from "../../engine/engine";
import { validateCatalog } from "../../engine/validate";
import { REFERENCE_PROFILES } from "../../engine/validate";
import { useRoute, useStore } from "../store";
import { applyImport, parseProgramFile, type ParseResult } from "../transfer";
import { Issues } from "../widgets";

/** Import d'un programme au format « relance.program » : fichier, glisser-déposer ou collage. */
export function Import() {
  const { draft, replace: replaceCatalog } = useStore();
  const [, go] = useRoute();
  const [text, setText] = useState("");
  const [fileName, setFileName] = useState("");
  const [result, setResult] = useState<ParseResult | null>(null);
  const [replace, setReplace] = useState(false);
  const [drag, setDrag] = useState(false);
  const [copied, setCopied] = useState(false);

  const read = (content: string, name = "") => {
    setText(content);
    setFileName(name);
    setResult(parseProgramFile(content, draft));
    setCopied(false);
  };
  const loadFile = async (f?: File | null) => {
    if (f) read(await f.text(), f.name);
  };

  const plan = result?.ok ? result.plan : null;
  const preview = useMemo(() => {
    if (!plan) return null;
    const merged = applyImport(draft, plan, replace);
    const p = merged.catalog.programs.find((x) => x.id === merged.programId)!;
    const issues = validateCatalog(merged.catalog).filter((i) => i.where.program === p.id || plan.newTypes.some((t) => t.id === i.where.sessionType));
    const levels = levelsOf(p).map((lv) => {
      const v = forLevel(p, lv)!;
      const weeks = [1, 2, 3, 4].map((n) => {
        const prof = { ...REFERENCE_PROFILES[1].profile, level: lv, days: [1, 3, 5, 7].slice(0, n) };
        try {
          const st = newState(p, prof, merged.catalog, lv);
          return programWeeks(st.template, st.steps.length, n);
        } catch {
          return NaN;
        }
      });
      return { lv, steps: v.steps.length, weeks };
    });
    return { ...merged, program: p, issues, levels };
  }, [plan, replace, draft]);

  return (
    <>
      <div>
        <a href="#/programmes" className="small">‹ Programmes</a>
        <h1>Importer un programme</h1>
        <p className="muted" style={{ margin: "4px 0 0" }}>
          Fichier JSON au format <code>relance.program</code> (<a href="/contract/README.md" target="_blank" rel="noreferrer">contrat</a>,{" "}
          <a href="/schemas/program-import-v1.json" target="_blank" rel="noreferrer">schéma</a>,{" "}
          <a href="/contract/AGENT.md" target="_blank" rel="noreferrer">guide pour un agent</a>). Le programme arrive masqué dans le brouillon :
          rien ne part vers les téléphones avant ta publication.
        </p>
      </div>
      <div className="cols">
        <div className="wide">
          <section
            className="card"
            style={drag ? { borderColor: "var(--accent)", background: "var(--accent-soft)" } : undefined}
            onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
            onDragLeave={() => setDrag(false)}
            onDrop={(e) => { e.preventDefault(); setDrag(false); loadFile(e.dataTransfer.files[0]); }}
          >
            <h2>Fichier</h2>
            <div className="row">
              <label className="btn" style={{ cursor: "pointer" }}>
                Choisir un fichier…
                <input type="file" accept="application/json,.json" style={{ display: "none" }} onChange={(e) => loadFile(e.target.files?.[0])} />
              </label>
              <span className="small muted">{fileName ? `Fichier : ${fileName}` : "ou glisse-le ici, ou colle son contenu ci-dessous."}</span>
            </div>
            <label className="field">Contenu JSON
              <textarea value={text} style={{ minHeight: 160, fontFamily: "ui-monospace, Menlo, monospace", fontSize: 13 }}
                onChange={(e) => setText(e.target.value)} placeholder='{ "format": "relance.program", "formatVersion": 1, … }' />
            </label>
            <div className="row">
              <button className="primary" disabled={!text.trim()} onClick={() => read(text, fileName)}>Vérifier</button>
            </div>
          </section>

          {result && !result.ok && (
            <section className="card">
              <h2>Le fichier ne respecte pas le contrat</h2>
              <p className="small muted" style={{ margin: 0 }}>Rien n'a été importé. Tu peux renvoyer ces erreurs telles quelles à l'agent qui a produit le fichier.</p>
              <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                {result.errors.map((e, i) => <div key={i} className="issue error"><code>{e}</code></div>)}
              </div>
              <div><button onClick={async () => {
                await navigator.clipboard.writeText(`Le fichier ne respecte pas le contrat relance.program v1 :\n${result.errors.map((e) => `- ${e}`).join("\n")}\nCorrige-le et renvoie le fichier complet.`);
                setCopied(true);
              }}>{copied ? "Copié" : "Copier les erreurs pour l'agent"}</button></div>
            </section>
          )}

          {plan && preview && (
            <section className="card">
              <div className="row between">
                <h2>{plan.program.name}</h2>
                <span className="tag">{plan.program.id}</span>
              </div>
              {plan.program.description && <p style={{ margin: 0 }}>{plan.program.description}</p>}
              {plan.file.meta?.rationale && (
                <div className="issue" style={{ background: "var(--soft)" }}>
                  <strong>Note de l'auteur{plan.file.meta.source ? ` (${plan.file.meta.source})` : ""} · </strong>{plan.file.meta.rationale}
                </div>
              )}
              <div className="scroll">
                <table>
                  <thead><tr><th>Niveau</th><th>Paliers</th><th>1 j/sem</th><th>2 j/sem</th><th>3 j/sem</th><th>4 j/sem</th></tr></thead>
                  <tbody>
                    {preview.levels.map((l) => (
                      <tr key={l.lv}><td>{l.lv}</td><td>{l.steps}</td>{l.weeks.map((w, i) => <td key={i}>{Number.isNaN(w) ? "—" : `${w} sem.`}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="small muted" style={{ margin: 0 }}>
                Types de séance : {plan.newTypes.length} nouveau(x){plan.newTypes.length ? ` (${plan.newTypes.map((t) => t.name).join(", ")})` : ""}
                {plan.reusedTypes.length ? ` · ${plan.reusedTypes.length} déjà présent(s), réutilisé(s)` : ""}.
              </p>
              {plan.renamedTypes.map((r) => (
                <div key={r.from} className="issue warning">Le type « {r.from} » existe déjà avec un autre contenu : il est importé sous l'identifiant « {r.to} ».</div>
              ))}
              {plan.idConflict && (
                <fieldset className="card" style={{ background: "var(--warn-bg)" }}>
                  <legend className="label">Un programme « {plan.program.id} » existe déjà</legend>
                  <label className="check"><input type="radio" name="conflict" checked={!replace} onChange={() => setReplace(false)} />Importer comme copie</label>
                  <label className="check"><input type="radio" name="conflict" checked={replace} onChange={() => setReplace(true)} />Remplacer le programme existant (dans le brouillon)</label>
                </fieldset>
              )}
              <h3>Garde-fous</h3>
              <Issues issues={preview.issues} empty="Aucun point d'attention." />
              <div className="row">
                <button className="primary" disabled={preview.issues.some((i) => i.level === "error")} onClick={() => {
                  replaceCatalog(preview.catalog);
                  go(`programmes/${preview.programId}`);
                }}>Importer dans le brouillon</button>
                <span className="small muted">Le programme sera masqué : vérifie-le dans le simulateur, puis rends-le visible et publie.</span>
              </div>
            </section>
          )}
        </div>
        <aside className="narrow">
          <div className="card small">
            <h3>Faire générer un programme</h3>
            <p style={{ margin: 0 }}>
              Donne à ton agent (Claude, ChatGPT…) le <a href="/contract/AGENT.md" target="_blank" rel="noreferrer">guide</a> comme instructions,
              le <a href="/schemas/program-import-v1.json" target="_blank" rel="noreferrer">schéma JSON</a> et un exemple
              (<a href="/contract/examples/courir-30-min-tapis.json" target="_blank" rel="noreferrer">Courir 30 min · tapis</a>, ou un programme exporté d'ici).
              Le catalogue publié (<a href="/api/catalog" target="_blank" rel="noreferrer">/api/catalog</a>) liste les types de séance existants.
            </p>
            <p className="muted" style={{ margin: 0 }}>Si l'import échoue, « Copier les erreurs pour l'agent » prépare un message de correction.</p>
          </div>
        </aside>
      </div>
    </>
  );
}
