import { describe, expect, it } from "vitest";
import { admin, getCatalog, type KV } from "./api";

const memory = (): KV => {
  const m = new Map<string, string>();
  return { get: async (k) => m.get(k) ?? null, set: async (k, v) => void m.set(k, v) };
};
const env = { adminPassword: "secret-coach", sessionSecret: "cle-de-signature-test" };
const call = (kv: KV, method: string, path: string, body?: unknown, token?: string) =>
  admin(kv, env, new Request(`https://x.test/api/admin/${path}`, {
    method, body: body ? JSON.stringify(body) : undefined,
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
  }));

describe("API des programmes", () => {
  it("sert le catalogue initial, avec ETag", async () => {
    const kv = memory();
    const r = await getCatalog(kv, new Request("https://x.test/api/catalog"));
    expect(r.status).toBe(200);
    const c = await r.json();
    expect(c.programs.length).toBe(8);
    const etag = r.headers.get("etag")!;
    const again = await getCatalog(kv, new Request("https://x.test/api/catalog", { headers: { "if-none-match": etag } }));
    expect(again.status).toBe(304);
  });

  it("refuse l'accès sans connexion et avec un mauvais mot de passe", async () => {
    const kv = memory();
    expect((await call(kv, "GET", "draft")).status).toBe(401);
    expect((await call(kv, "POST", "login", { password: "faux" })).status).toBe(401);
    expect((await call(kv, "GET", "draft", undefined, "abc.def")).status).toBe(401);
  });

  it("brouillon, publication, historique et restauration", async () => {
    const kv = memory();
    const { token } = await (await call(kv, "POST", "login", { password: "secret-coach" })).json();
    const { draft } = await (await call(kv, "GET", "draft", undefined, token)).json();
    draft.programs[0].name = "Courir 10 minutes";
    expect((await call(kv, "PUT", "draft", draft, token)).status).toBe(200);
    // Pas encore publié : l'app voit toujours l'ancien nom.
    let pub = await (await getCatalog(kv, new Request("https://x.test/api/catalog"))).json();
    expect(pub.programs[0].name).toBe("Courir 10 min");
    const res = await (await call(kv, "POST", "publish", { notes: "Nouveau nom" }, token)).json();
    expect(res.version).toBe(2);
    pub = await (await getCatalog(kv, new Request("https://x.test/api/catalog"))).json();
    expect(pub.programs[0].name).toBe("Courir 10 minutes");
    expect(pub.version).toBe(2);
    const { history } = await (await call(kv, "GET", "history", undefined, token)).json();
    expect(history[0]).toMatchObject({ version: 2, notes: "Nouveau nom" });
    const restored = await (await call(kv, "POST", "restore", { version: 0 }, token)).json();
    expect(restored.draft.programs[0].name).toBe("Courir 10 min");
  });

  it("bloque la publication d'un brouillon avec des erreurs", async () => {
    const kv = memory();
    const { token } = await (await call(kv, "POST", "login", { password: "secret-coach" })).json();
    const { draft } = await (await call(kv, "GET", "draft", undefined, token)).json();
    draft.programs[0].steps[0].sessions[0].type = "inconnu";
    await call(kv, "PUT", "draft", draft, token);
    const r = await call(kv, "POST", "publish", {}, token);
    expect(r.status).toBe(422);
    expect((await r.json()).issues.length).toBeGreaterThan(0);
  });
});
