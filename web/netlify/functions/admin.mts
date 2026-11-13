import type { Config } from "@netlify/functions";
import { admin } from "../lib/api";
import { blobs, env } from "../lib/store";

export default async (req: Request) => admin(blobs(), env(), req);

export const config: Config = { path: "/api/admin/*" };
