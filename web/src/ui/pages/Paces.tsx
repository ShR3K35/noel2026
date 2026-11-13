import { speedsFor } from "../../engine/engine";
import type { Paces as PacesT } from "../../engine/types";
import { REFERENCE_PROFILES } from "../../engine/validate";
import { useStore } from "../store";
import { Field, Num } from "../widgets";

const ROWS: [keyof PacesT, string, string][] = [
  ["warm", "Marche lente (échauffement)", "× vitesse de référence"],
  ["recovery", "Récupération", "× vitesse de référence"],
  ["walk", "Marche active", "× vitesse de référence"],
  ["hill", "Marche en côte", "× vitesse de référence"],
  ["runOfVr", "Course facile", "× vitesse de référence"],
  ["runMin", "Course facile, minimum", "km/h"],
  ["runMax", "Course facile, maximum", "km/h"],
  ["tempoOfRun", "Allure soutenue", "× course facile"],
  ["fastOfRun", "Rapide (fractionné)", "× course facile"],
];

export function Paces() {
  const { draft, update } = useStore();
  const p = draft.paces;
  return (
    <>
      <div>
        <h1>Allures</h1>
        <p className="muted" style={{ margin: "4px 0 0" }}>
          Toutes les vitesses découlent de la vitesse de référence (VR) mesurée au test de marche : l'allure la plus vive où le sportif peut encore parler.
          Ces coefficients valent pour tous les programmes.
        </p>
      </div>
      <div className="cols">
        <section className="card wide">
          <h2>Coefficients</h2>
          {ROWS.map(([k, label, unit]) => (
            <div key={k} className="row">
              <Field label={label}><span className="row"><Num className="" label={label} step={0.01} value={p[k]} onChange={(v) => update((c) => { c.paces[k] = v; return c; })} /> <span className="small muted">{unit}</span></span></Field>
            </div>
          ))}
        </section>
        <section className="card narrow">
          <h2>Ce que ça donne</h2>
          <div className="scroll">
            <table>
              <thead><tr><th>Profil</th><th>VR</th><th>Marche</th><th>Côte</th><th>Course</th><th>Soutenue</th><th>Rapide</th></tr></thead>
              <tbody>
                {REFERENCE_PROFILES.map(({ name, profile }) => {
                  const s = speedsFor(profile, 1, p);
                  return <tr key={name}><td>{name}</td><td>{profile.vr}</td><td>{s.walk}</td><td>{s.hill}</td><td>{s.run}</td><td>{s.tempo}</td><td>{s.fast}</td></tr>;
                })}
              </tbody>
            </table>
          </div>
          <p className="small muted" style={{ margin: 0 }}>En km/h, plafonné à la vitesse maximale du tapis du sportif (14 km/h pour un Domyos Run100).</p>
        </section>
      </div>
    </>
  );
}
