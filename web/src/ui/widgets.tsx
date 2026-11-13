import type { ReactNode } from "react";
import { kmh, minutesOf } from "../engine/engine";
import type { Block, Level, PlannedSession } from "../engine/types";
import { BLOCK_LABELS, LEVELS } from "../engine/types";
import type { Issue } from "../engine/validate";

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      {label}
      {children}
      {hint && <span className="small muted" style={{ fontWeight: 400 }}>{hint}</span>}
    </label>
  );
}

export function Num({ value, onChange, min, max, step = 1, label, className = "num" }: {
  value: number; onChange: (v: number) => void; min?: number; max?: number; step?: number; label: string; className?: string;
}) {
  return (
    <input
      className={className} type="number" aria-label={label} value={Number.isFinite(value) ? value : ""} min={min} max={max} step={step}
      onChange={(e) => onChange(e.target.value === "" ? 0 : Number(e.target.value))}
    />
  );
}

export function LevelChips({ value, onChange }: { value: Level[]; onChange: (v: Level[]) => void }) {
  return (
    <div className="chips" role="group" aria-label="Niveaux">
      {LEVELS.map((l) => {
        const on = value.includes(l);
        return (
          <button key={l} type="button" className={`chip ${on ? "on" : ""}`} aria-pressed={on}
            onClick={() => onChange(on ? value.filter((x) => x !== l) : [...value, l].sort())}>
            {l}
          </button>
        );
      })}
    </div>
  );
}

export function Issues({ issues, empty = "Aucun point d'attention." }: { issues: Issue[]; empty?: string }) {
  if (!issues.length) return <p className="small muted">{empty}</p>;
  const sorted = [...issues].sort((a, b) => (a.level === b.level ? 0 : a.level === "error" ? -1 : 1));
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {sorted.map((i, k) => (
        <div key={k} className={`issue ${i.level}`}>
          <strong>{i.level === "error" ? "Erreur" : "Attention"} · </strong>{i.message}
        </div>
      ))}
    </div>
  );
}

const color = (b: Block) =>
  b.kind === "RUN" ? "var(--run)" : b.kind === "FAST" ? "var(--fast)" : b.kind === "TEST" ? "var(--fast)" : b.kind === "HILL" ? "#7c8a8e" : "var(--walk)";

/** Profil de vitesse d'une séance : largeur = durée, hauteur = vitesse. */
export function SessionChart({ blocks, height = 90 }: { blocks: Block[]; height?: number }) {
  const total = blocks.reduce((a, b) => a + b.seconds, 0) || 1;
  const max = Math.max(8, ...blocks.map((b) => b.speedKmh));
  let x = 0;
  const W = 600;
  const desc = blocks.map((b) => `${BLOCK_LABELS[b.kind]} ${Math.round(b.seconds / 60)} min à ${kmh(b.speedKmh)} km/h`).join(", ");
  return (
    <svg viewBox={`0 0 ${W} ${height + 18}`} width="100%" role="img" aria-label={`Profil de la séance : ${desc}`} style={{ display: "block" }}>
      {blocks.map((b, i) => {
        const w = (b.seconds / total) * W;
        const h = Math.max(4, (b.speedKmh / max) * height);
        const r = <rect key={i} x={x + 0.5} y={height - h} width={Math.max(1, w - 1)} height={h} rx={2} fill={color(b)} />;
        x += w;
        return r;
      })}
      <line x1={0} x2={W} y1={height} y2={height} stroke="var(--line)" />
      <text x={0} y={height + 14} fontSize={11} fill="var(--muted)">0</text>
      <text x={W} y={height + 14} fontSize={11} fill="var(--muted)" textAnchor="end">{Math.round(total / 60)} min</text>
    </svg>
  );
}

export function ChartLegend() {
  return (
    <div className="legend">
      <span><i style={{ background: "var(--walk)" }} />Marche</span>
      <span><i style={{ background: "#7c8a8e" }} />Côte</span>
      <span><i style={{ background: "var(--run)" }} />Course</span>
      <span><i style={{ background: "var(--fast)" }} />Rapide / test</span>
    </div>
  );
}

export function SessionDetail({ s }: { s: PlannedSession }) {
  let t = 0;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div className="row between">
        <h3>{s.title}</h3>
        <span className="tag">{minutesOf(s)} min</span>
      </div>
      <p className="small muted" style={{ margin: 0 }}>{s.detail}</p>
      <SessionChart blocks={s.blocks} />
      <ChartLegend />
      <div className="scroll">
        <table>
          <thead><tr><th>Début</th><th>Bloc</th><th>Durée</th><th>Vitesse</th><th>Pente</th></tr></thead>
          <tbody>
            {s.blocks.map((b, i) => {
              const start = t;
              t += b.seconds;
              return (
                <tr key={i}>
                  <td>{Math.floor(start / 60)}:{String(start % 60).padStart(2, "0")}</td>
                  <td>{BLOCK_LABELS[b.kind]}</td>
                  <td>{Math.round((b.seconds / 60) * 10) / 10} min</td>
                  <td>{b.kind === "TEST" ? "au ressenti" : `${kmh(b.speedKmh)} km/h`}</td>
                  <td>{b.inclinePct ? `${b.inclinePct} %` : "—"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
