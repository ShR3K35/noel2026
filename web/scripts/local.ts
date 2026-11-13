// Interface coach en local, avec l'API branchée sur un stockage en mémoire.
// Lancement : npm run local  (mot de passe : celui de ADMIN_PASSWORD, « coach » par défaut).
import { createServer } from "vite";
import { admin, getCatalog, type KV } from "../netlify/lib/api";

const mem = new Map<string, string>();
const kv: KV = { get: async (k) => mem.get(k) ?? null, set: async (k, v) => void mem.set(k, v) };
const env = { adminPassword: process.env.ADMIN_PASSWORD ?? "coach", sessionSecret: "local-dev-secret" };

const server = await createServer({
  configFile: "vite.config.ts",
  server: { port: Number(process.env.PORT ?? 5173), host: "127.0.0.1" },
  plugins: [{
    name: "relance-api",
    configureServer(s) {
      s.middlewares.use(async (req, res, next) => {
        if (!req.url?.startsWith("/api/")) return next();
        const chunks: Buffer[] = [];
        for await (const c of req) chunks.push(c as Buffer);
        const body = chunks.length ? Buffer.concat(chunks) : undefined;
        const request = new Request(`http://localhost${req.url}`, { method: req.method, headers: req.headers as Record<string, string>, body: body?.length ? body : undefined });
        const r = req.url.startsWith("/api/catalog") ? await getCatalog(kv, request) : await admin(kv, env, request);
        res.statusCode = r.status;
        r.headers.forEach((v, k) => res.setHeader(k, v));
        res.end(Buffer.from(await r.arrayBuffer()));
      });
    },
  }],
});
await server.listen();
server.printUrls();
