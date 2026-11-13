// API des programmes : logique indépendante de Netlify, testée avec un stockage en mémoire.
// Les fonctions Netlify (netlify/functions) ne font que brancher le vrai stockage (Netlify Blobs).
import seedJson from "../../../catalog/catalog.json";
import type { Catalog } from "../../src/engine/types";
import { validateCatalog } from "../../src/engine/validate";

export interface KV {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
}

export interface Env { adminPassword: string; sessionSecret: string; now?: () => Date }

export interface HistoryEntry { version: number; publishedAt: string; notes: string; programs: number; sessionTypes: number }

const SEED = seedJson as unknown as Catalog;
const TOKEN_HOURS = 12;

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", ...headers } });

const enc = new TextEncoder();

async function hmac(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(data)));
  return Buffer.from(sig).toString("base64url");
}

/** Comparaison en temps constant, pour ne rien laisser deviner du mot de passe. */
function same(a: string, b: string): boolean {
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

export async function makeToken(env: Env): Promise<string> {
  const exp = Math.floor((env.now?.() ?? new Date()).getTime() / 1000) + TOKEN_HOURS * 3600;
  const payload = Buffer.from(JSON.stringify({ exp })).toString("base64url");
  return `${payload}.${await hmac(env.sessionSecret, payload)}`;
}

export async function checkToken(env: Env, header: string | null): Promise<boolean> {
  const token = header?.replace(/^Bearer\s+/i, "") ?? "";
  const [payload, sig] = token.split(".");
  if (!payload || !sig || !env.sessionSecret) return false;
  if (!same(sig, await hmac(env.sessionSecret, payload))) return false;
  try {
    const { exp } = JSON.parse(Buffer.from(payload, "base64url").toString());
    return typeof exp === "number" && exp * 1000 > (env.now?.() ?? new Date()).getTime();
  } catch {
    return false;
  }
}

async function read(kv: KV, key: string): Promise<Catalog | null> {
  const raw = await kv.get(key);
  return raw ? (JSON.parse(raw) as Catalog) : null;
}

async function published(kv: KV): Promise<Catalog> {
  return (await read(kv, "published")) ?? SEED;
}

async function history(kv: KV): Promise<HistoryEntry[]> {
  const raw = await kv.get("history-index");
  return raw ? (JSON.parse(raw) as HistoryEntry[]) : [];
}

/** Contrôle de forme minimal d'un catalogue reçu (le détail est dans validateCatalog). */
function wellFormed(c: unknown): c is Catalog {
  const x = c as Catalog;
  return !!x && typeof x === "object" && Array.isArray(x.programs) && Array.isArray(x.sessionTypes) && !!x.paces && !!x.specials;
}

/** GET /api/catalog : le catalogue publié, lu par l'app. */
export async function getCatalog(kv: KV, req: Request): Promise<Response> {
  const c = await published(kv);
  const etag = `"v${c.version}"`;
  const headers = { etag, "cache-control": "public, max-age=300", "access-control-allow-origin": "*" };
  if (req.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers });
  return json(200, c, headers);
}

/** /api/admin/* : réservé au coach. */
export async function admin(kv: KV, env: Env, req: Request): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/api\/admin\/?/, "");
  const method = req.method.toUpperCase();

  if (path === "login" && method === "POST") {
    if (!env.adminPassword || !env.sessionSecret) return json(503, { error: "Mot de passe administrateur non configuré sur Netlify." });
    const body = await req.json().catch(() => ({}));
    if (!same(String(body.password ?? ""), env.adminPassword)) {
      await new Promise((r) => setTimeout(r, 800));
      return json(401, { error: "Mot de passe incorrect." });
    }
    return json(200, { token: await makeToken(env), hours: TOKEN_HOURS });
  }

  if (!(await checkToken(env, req.headers.get("authorization")))) return json(401, { error: "Session expirée : reconnecte-toi." });

  if (path === "draft" && method === "GET") {
    const draft = await read(kv, "draft");
    return json(200, { draft: draft ?? (await published(kv)), published: await published(kv), hasDraft: !!draft });
  }
  if (path === "draft" && method === "PUT") {
    const body = await req.json().catch(() => null);
    if (!wellFormed(body)) return json(400, { error: "Catalogue mal formé." });
    await kv.set("draft", JSON.stringify(body));
    return json(200, { ok: true, savedAt: (env.now?.() ?? new Date()).toISOString() });
  }
  if (path === "publish" && method === "POST") {
    const body = await req.json().catch(() => ({}));
    const draft = (await read(kv, "draft")) ?? (await published(kv));
    const errors = validateCatalog(draft).filter((i) => i.level === "error");
    if (errors.length) return json(422, { error: "Le brouillon contient des erreurs bloquantes.", issues: errors });
    const current = await published(kv);
    const version = Math.max(current.version, ...(await history(kv)).map((h) => h.version), 0) + 1;
    const publishedAt = (env.now?.() ?? new Date()).toISOString();
    const notes = String(body.notes ?? "").slice(0, 500);
    const next: Catalog = { ...draft, version, publishedAt, notes };
    await kv.set(`history/${version}`, JSON.stringify(next));
    await kv.set("published", JSON.stringify(next));
    await kv.set("draft", JSON.stringify(next));
    const entry: HistoryEntry = { version, publishedAt, notes, programs: next.programs.length, sessionTypes: next.sessionTypes.length };
    await kv.set("history-index", JSON.stringify([entry, ...(await history(kv))].slice(0, 100)));
    return json(200, { ok: true, version, publishedAt });
  }
  if (path === "history" && method === "GET") return json(200, { history: await history(kv) });
  const m = /^history\/(\d+)$/.exec(path);
  if (m && method === "GET") {
    const c = await read(kv, `history/${m[1]}`);
    return c ? json(200, c) : json(404, { error: "Version introuvable." });
  }
  if (path === "restore" && method === "POST") {
    const body = await req.json().catch(() => ({}));
    const c = body.version === 0 ? SEED : await read(kv, `history/${Number(body.version)}`);
    if (!c) return json(404, { error: "Version introuvable." });
    await kv.set("draft", JSON.stringify(c));
    return json(200, { draft: c });
  }
  return json(404, { error: "Route inconnue." });
}
