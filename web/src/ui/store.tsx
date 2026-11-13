import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { Catalog } from "../engine/types";
import { validateCatalog, type Issue } from "../engine/validate";
import { api } from "./api";

interface Ctx {
  draft: Catalog;
  published: Catalog;
  update: (fn: (c: Catalog) => Catalog) => void;
  replace: (c: Catalog) => void;
  dirty: boolean;
  saving: boolean;
  saveError: string;
  savedAt: string;
  save: () => Promise<void>;
  reload: () => Promise<void>;
  issues: Issue[];
}

const StoreContext = createContext<Ctx | null>(null);

export const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));

export function StoreProvider({ initial, published: pub0, children }: { initial: Catalog; published: Catalog; children: ReactNode }) {
  const [draft, setDraft] = useState(initial);
  const [published, setPublished] = useState(pub0);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [savedAt, setSavedAt] = useState("");
  const latest = useRef(draft);
  latest.current = draft;

  const save = useCallback(async () => {
    setSaving(true);
    try {
      const r = await api.save(latest.current);
      setSavedAt(r.savedAt);
      setDirty(false);
      setSaveError("");
    } catch (e) {
      setSaveError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }, []);

  // Enregistrement automatique du brouillon, deux secondes après la dernière modification.
  useEffect(() => {
    if (!dirty) return;
    const t = setTimeout(save, 2000);
    return () => clearTimeout(t);
  }, [draft, dirty, save]);

  const reload = useCallback(async () => {
    const r = await api.draft();
    setDraft(r.draft);
    setPublished(r.published);
    setDirty(false);
  }, []);

  const update = useCallback((fn: (c: Catalog) => Catalog) => {
    setDraft((c) => fn(clone(c)));
    setDirty(true);
  }, []);
  const replace = useCallback((c: Catalog) => {
    setDraft(c);
    setDirty(true);
  }, []);

  // Les garde-fous se recalculent à chaque modification (avec un léger délai pour rester fluide).
  const [issues, setIssues] = useState<Issue[]>(() => validateCatalog(initial));
  useEffect(() => {
    const t = setTimeout(() => setIssues(validateCatalog(draft)), 250);
    return () => clearTimeout(t);
  }, [draft]);

  const value = useMemo(
    () => ({ draft, published, update, replace, dirty, saving, saveError, savedAt, save, reload, issues }),
    [draft, published, update, replace, dirty, saving, saveError, savedAt, save, reload, issues],
  );
  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useStore(): Ctx {
  const c = useContext(StoreContext);
  if (!c) throw new Error("StoreProvider manquant");
  return c;
}

/** Navigation par l'adresse (#/programmes/run-30…). */
export function useRoute(): [string[], (path: string) => void] {
  const read = () => window.location.hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const on = () => setRoute(read());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  return [route, (path: string) => { window.location.hash = `#/${path}`; }];
}
