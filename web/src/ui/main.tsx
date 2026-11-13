import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import type { Catalog } from "../engine/types";
import { api, auth } from "./api";
import { Import } from "./pages/Import";
import { Paces } from "./pages/Paces";
import { ProgramEditor } from "./pages/ProgramEditor";
import { Programs } from "./pages/Programs";
import { Publish } from "./pages/Publish";
import { SessionTypeEditor, SessionTypes } from "./pages/SessionTypes";
import { Simulator } from "./pages/Simulator";
import { StoreProvider, useRoute, useStore } from "./store";
import "./styles.css";

function Login({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <div className="login">
      <form className="card" onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError("");
        try {
          const r = await api.login(password);
          auth.set(r.token);
          onDone();
        } catch (err) {
          setError((err as Error).message);
        } finally {
          setBusy(false);
        }
      }}>
        <div className="row"><img src="/favicon.svg" width={36} height={36} alt="" /><h1>Relance · Coach</h1></div>
        <p className="muted" style={{ margin: 0 }}>Construis et publie les programmes de l'app.</p>
        <label className="field">Mot de passe
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus />
        </label>
        {error && <div className="issue error">{error}</div>}
        <button className="primary" disabled={busy || !password}>{busy ? "Connexion…" : "Se connecter"}</button>
      </form>
    </div>
  );
}

const NAV = [
  ["programmes", "Programmes"],
  ["seances", "Types de séance"],
  ["simulateur", "Simulateur"],
  ["allures", "Allures"],
  ["publication", "Publication"],
] as const;

function Shell({ onLogout }: { onLogout: () => void }) {
  const [route, go] = useRoute();
  const { dirty, saving, saveError, savedAt, save, issues, draft, published } = useStore();
  const errors = issues.filter((i) => i.level === "error").length;
  const changed = JSON.stringify({ ...draft, version: 0, publishedAt: "", notes: "" }) !== JSON.stringify({ ...published, version: 0, publishedAt: "", notes: "" });
  const page = route[0] ?? "programmes";
  return (
    <div className="layout">
      <nav className="side" aria-label="Sections">
        <div className="brand"><img src="/favicon.svg" width={28} height={28} alt="" />Relance · Coach</div>
        {NAV.map(([id, label]) => (
          <a key={id} href={`#/${id}`} className={`nav ${page === id ? "on" : ""}`} aria-current={page === id ? "page" : undefined}>
            {label}{id === "publication" && changed ? " •" : ""}
          </a>
        ))}
        <div className="grow" />
        <button className="ghost" onClick={onLogout}>Se déconnecter</button>
      </nav>
      <main className="main">
        <div className="topbar">
          <div className="status" role="status">
            <span className={`dot ${dirty || saveError ? "warn" : ""}`} />
            {saveError ? `Brouillon non enregistré : ${saveError}` : saving ? "Enregistrement…" : dirty ? "Modifications en cours" : savedAt ? "Brouillon enregistré" : "Brouillon à jour"}
            {" · "}publié : version {published.version}{changed ? " · modifications non publiées" : ""}
            {errors > 0 && <span className="tag" style={{ color: "var(--error)" }}>{errors} erreur(s)</span>}
          </div>
          <div className="row">
            <button onClick={save} disabled={!dirty || saving}>Enregistrer</button>
            <button className="primary" onClick={() => go("publication")}>Publier…</button>
          </div>
        </div>
        {page === "programmes" && !route[1] && <Programs />}
        {page === "import" && <Import />}
        {page === "programmes" && route[1] && <ProgramEditor id={route[1]} />}
        {page === "seances" && !route[1] && <SessionTypes />}
        {page === "seances" && route[1] && <SessionTypeEditor id={route[1]} />}
        {page === "simulateur" && <Simulator programId={route[1]} />}
        {page === "allures" && <Paces />}
        {page === "publication" && <Publish />}
      </main>
    </div>
  );
}

function App() {
  const [loaded, setLoaded] = useState<{ draft: Catalog; published: Catalog } | null>(null);
  const [needLogin, setNeedLogin] = useState(!auth.get());
  const [error, setError] = useState("");
  useEffect(() => {
    if (needLogin) return;
    api.draft().then(setLoaded).catch((e) => {
      if (e.status === 401) setNeedLogin(true);
      else setError(e.message);
    });
  }, [needLogin]);
  if (needLogin) return <Login onDone={() => setNeedLogin(false)} />;
  if (error) return <div className="login"><div className="card"><div className="issue error">{error}</div><button onClick={() => location.reload()}>Réessayer</button></div></div>;
  if (!loaded) return <div className="login"><p className="muted">Chargement des programmes…</p></div>;
  return (
    <StoreProvider initial={loaded.draft} published={loaded.published}>
      <Shell onLogout={() => { auth.set(null); setLoaded(null); setNeedLogin(true); }} />
    </StoreProvider>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
