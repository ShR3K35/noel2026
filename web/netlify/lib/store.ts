import { getStore } from "@netlify/blobs";
import type { Env, KV } from "./api";

/** Stockage Netlify Blobs (cohérence forte : une publication est lue aussitôt). */
export function blobs(): KV {
  const store = getStore({ name: "relance-catalog", consistency: "strong" });
  return {
    get: (key) => store.get(key, { type: "text" }),
    set: async (key, value) => {
      await store.set(key, value);
    },
  };
}

export const env = (): Env => ({
  adminPassword: process.env.ADMIN_PASSWORD ?? "",
  sessionSecret: process.env.SESSION_SECRET ?? "",
});
