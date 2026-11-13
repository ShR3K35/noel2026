import { useMemo, useState } from "react";
import { kcalPerMin, lockedReason, minutesOf, newState, plan, recommended, speedsFor, weeksFor } from "../../engine/engine";
import type { Level, PlannedSession, Profile } from "../../engine/types";
import { LEVEL_LABELS, LEVELS } from "../../engine/types";
import { REFERENCE_PROFILES } from "../../engine/validate";
import { useRoute, useStore } from "../store";
import { Field, Num, SessionDetail } from "../widgets";

const DAYS = ["L", "M", "M", "J", "V", "S", "D"];
const toEpochDay = (d: Date) => Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000);
const dateOf = (day: number) => new Date(day * 86400000).toLocaleDateString("fr-FR", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

export function Simulator({ programId }: { programId?: string }) {
  const { draft } = useStore();
  const [, go] = useRoute();
  const [profile, setProfile] = useState<Profile>(REFERENCE_PROFILES[1].profile);
  const [short, setShort] = useState(false);
  const [selected, setSelected] = useState<number | null>(0);
  const [openWeeks, setOpenWeeks] = useState<number[]>([0, 1]);
  const programs = [...draft.programs].sort((a, b) => a.order - b.order);
  const t = programs.find((p) => p.id === programId) ?? programs[0];
  const start = toEpochDay(new Date());
  const set = <K extends keyof Profile>(k: K, v: Profile[K]) => setProfile((p) => ({ ...p, [k]: v }));

  const sim = useMemo(() => {
    if (!t) return null;
    try {
      const st = newState(t, profile, draft);
      return { ok: true as const, st, sessions: plan(st, profile, start, short ? 15 : 0) };
    } catch (e) {
      return { ok: false as const, error: (e as Error).message };
    }
  }, [t, profile, draft, start, short]);

  if (!t) return <div className="card empty">Aucun programme.</div>;
  const locked = lockedReason(t, profile);
  const advised = recommended(draft, profile);
  const speeds = speedsFor(profile, 1, draft.paces);
  const weeks = sim?.ok ? groupWeeks(sim.sessions) : [];
  const maxWeek = Math.max(1, ...weeks.map((w) => w.minutes));
  const sel = sim?.ok && selected != null ? sim.sessions[selected] : null;

  return (
    <>
      <div>
        <h1>Simulateur</h1>
        <p className="muted" style={{ margin: "4px 0 0" }}>Le planning exactement comme le téléphone le calculera pour ce profil, si chaque séance est faite à sa date.</p>
      </div>
      <div className="cols">
        <div className="wide">
          <section className="card">
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Programme">
                <select value={t.id} onChange={(e) => { go(`simulateur/${e.target.value}`); setSelected(0); }}>
                  {programs.map((p) => <option key={p.id} value={p.id}>{p.name}{p.enabled ? "" : " (masqué)"}</option>)}
                </select>
              </Field>
              <Field label="Profil type">
                <select onChange={(e) => setProfile(REFERENCE_PROFILES[Number(e.target.value)].profile)} defaultValue={1}>
                  {REFERENCE_PROFILES.map((p, i) => <option key={p.name} value={i}>{p.name}</option>)}
                </select>
              </Field>
              <a href={`#/programmes/${t.id}`}>Modifier ce programme</a>
            </div>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Niveau">
                <select value={profile.level} onChange={(e) => set("level", e.target.value as Level)}>
                  {LEVELS.map((l) => <option key={l} value={l}>{LEVEL_LABELS[l]}</option>)}
                </select>
              </Field>
              <Field label="Vitesse de référence"><Num className="" label="VR" step={0.1} value={profile.vr} onChange={(v) => set("vr", v)} /></Field>
              <Field label="Course continue (min)"><Num className="" label="Course continue" value={profile.longestRunMin} onChange={(v) => set("longestRunMin", v)} /></Field>
              <Field label="Âge"><Num className="" label="Âge" value={profile.age} onChange={(v) => set("age", v)} /></Field>
              <Field label="Poids (kg)"><Num className="" label="Poids" value={profile.weightKg} onChange={(v) => set("weightKg", v)} /></Field>
            </div>
            <div className="row" style={{ alignItems: "flex-end" }}>
              <Field label="Jours d'entraînement">
                <div className="chips">
                  {DAYS.map((d, i) => {
                    const on = profile.days.includes(i + 1);
                    return <button key={i} type="button" className={`chip ${on ? "on" : ""}`} aria-pressed={on} aria-label={["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"][i]}
                      onClick={() => set("days", on ? profile.days.filter((x) => x !== i + 1) : [...profile.days, i + 1].sort())}>{d}</button>;
                  })}
                </div>
              </Field>
              <Field label="Minutes par séance"><Num className="" label="Minutes par séance" value={profile.minutesPerSession} onChange={(v) => set("minutesPerSession", v)} /></Field>
              <Field label="Pente du tapis (%)"><Num className="" label="Pente" value={profile.inclinePct} onChange={(v) => set("inclinePct", v)} /></Field>
              <Field label="Vitesse max (km/h)"><Num className="" label="Vitesse max" value={profile.treadmillMaxKmh} onChange={(v) => set("treadmillMaxKmh", v)} /></Field>
              <label className="check"><input type="checkbox" checked={profile.healthFlag} onChange={(e) => set("healthFlag", e.target.checked)} />Avis médical</label>
              <label className="check"><input type="checkbox" checked={short} onChange={(e) => setShort(e.target.checked)} />Versions « 15 min »</label>
            </div>
            <p className="small muted" style={{ margin: 0 }}>
              Allures de ce profil : marche {speeds.walk} · côte {speeds.hill} · course {speeds.run} · soutenue {speeds.tempo} · rapide {speeds.fast} km/h.
              {advised && ` Programme conseillé par l'app : ${advised.name}.`}
            </p>
            {locked && <div className="issue warning">Dans l'app, ce programme serait verrouillé pour ce profil : {locked}.</div>}
          </section>

          {sim && !sim.ok && <div className="issue error">{sim.error}</div>}
          {sim?.ok && (
            <section className="card">
              <div className="row between">
                <h2>{sim.sessions.length} séances · {weeksFor(sim.st.steps.length, t.deload.every)} semaines</h2>
                <span className="small muted">
                  {sim.sessions.length ? `Du ${dateOf(sim.sessions[0].day)} au ${dateOf(sim.sessions[sim.sessions.length - 1].day)}` : ""}
                  {" · "}{Math.round(sim.sessions.reduce((a, s) => a + minutesOf(s), 0) / 60)} h au total
                  {" · ~"}{Math.round(sim.sessions.reduce((a, s) => a + s.blocks.reduce((k, b) => k + (kcalPerMin(b.speedKmh, b.inclinePct, profile.weightKg) * b.seconds) / 60, 0), 0))} kcal
                </span>
              </div>
              <svg viewBox="0 0 600 110" width="100%" role="img" aria-label="Minutes par semaine">
                {weeks.map((w, i) => {
                  const bw = 600 / weeks.length;
                  const h = (w.minutes / maxWeek) * 90;
                  const hr = (w.run / maxWeek) * 90;
                  return (
                    <g key={w.week}>
                      <rect x={i * bw + 1} y={95 - h} width={Math.max(1, bw - 2)} height={h} rx={2} fill="var(--walk)" />
                      <rect x={i * bw + 1} y={95 - hr} width={Math.max(1, bw - 2)} height={hr} rx={2} fill={w.deload ? "var(--fast)" : "var(--run)"} />
                      {weeks.length <= 30 && <text x={i * bw + bw / 2} y={108} fontSize={9} textAnchor="middle" fill="var(--muted)">{w.week + 1}</text>}
                    </g>
                  );
                })}
              </svg>
              <p className="small muted" style={{ margin: 0 }}>Barres : minutes par semaine ; partie foncée : minutes de course (orangée en semaine allégée).</p>
              {weeks.map((w) => {
                const open = openWeeks.includes(w.week);
                return (
                  <div key={w.week} className="week">
                    <button className="session-line" aria-expanded={open} onClick={() => setOpenWeeks(open ? openWeeks.filter((x) => x !== w.week) : [...openWeeks, w.week])}>
                      <strong>Semaine {w.week + 1}</strong>
                      {w.deload && <span className="tag">allégée</span>}
                      <span className="muted small">{w.sessions.length} séance(s) · {w.minutes} min · dont {Math.round(w.run)} min de course</span>
                      {t.weekNotes[String(w.week + 1)] && <span className="small">« {t.weekNotes[String(w.week + 1)]} »</span>}
                      <span style={{ marginLeft: "auto" }}>{open ? "▾" : "▸"}</span>
                    </button>
                    {open && w.sessions.map(({ s, index }) => (
                      <button key={index} className={`session-line ${selected === index ? "on" : ""}`} onClick={() => setSelected(index)}>
                        <span className="tag">{dateOf(s.day)}</span>
                        <strong>{s.title}</strong>
                        <span className="small muted">{s.detail} · {minutesOf(s)} min</span>
                        {s.isKey && <span className="tag on">clé</span>}
                      </button>
                    ))}
                  </div>
                );
              })}
            </section>
          )}
        </div>
        <aside className="narrow">
          <div className="card">
            {sel ? <><span className="label">{dateOf(sel.day)} · semaine {sel.week + 1}</span><SessionDetail s={sel} /></> : <p className="muted small">Choisis une séance pour voir son détail.</p>}
          </div>
        </aside>
      </div>
    </>
  );
}

function groupWeeks(sessions: PlannedSession[]) {
  const map = new Map<number, { week: number; minutes: number; run: number; deload: boolean; sessions: { s: PlannedSession; index: number }[] }>();
  sessions.forEach((s, index) => {
    const w = map.get(s.week) ?? { week: s.week, minutes: 0, run: 0, deload: s.deload, sessions: [] };
    w.minutes += minutesOf(s);
    w.run += s.blocks.filter((b) => b.kind === "RUN" || b.kind === "FAST").reduce((a, b) => a + b.seconds, 0) / 60;
    w.sessions.push({ s, index });
    map.set(s.week, w);
  });
  return [...map.values()].sort((a, b) => a.week - b.week);
}

