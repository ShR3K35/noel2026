import type { Catalog } from "../engine/types";
import type { Issue } from "../engine/validate";

export interface HistoryEntry { version: number; publishedAt: string; notes: string; programs: number; sessionTypes: number }

const TOKEN_KEY = "relance-coach-token";

export const auth = {
  get: (): string | null => {
    try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
  },
  set: (t: string | null) => {
    try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY); } catch { /* navigation privée */ }
  },
};

export class ApiError extends Error {
  constructor(message: string, public status: number, public issues: Issue[] = []) { super(message); }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = auth.get();
  const r = await fetch(`/api/admin/${path}`, {
    method,
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) {
    if (r.status === 401) auth.set(null);
    throw new ApiError(data.error ?? `Erreur ${r.status}`, r.status, data.issues ?? []);
  }
  return data as T;
}

export const api = {
  login: (password: string) => call<{ token: string }>("POST", "login", { password }),
  draft: () => call<{ draft: Catalog; published: Catalog; hasDraft: boolean }>("GET", "draft"),
  save: (c: Catalog) => call<{ savedAt: string }>("PUT", "draft", c),
  publish: (notes: string) => call<{ version: number; publishedAt: string }>("POST", "publish", { notes }),
  history: () => call<{ history: HistoryEntry[] }>("GET", "history"),
  restore: (version: number) => call<{ draft: Catalog }>("POST", "restore", { version }),
};
