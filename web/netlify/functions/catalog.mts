import type { Config } from "@netlify/functions";
import { getCatalog } from "../lib/api";
import { blobs } from "../lib/store";

export default async (req: Request) => getCatalog(blobs(), req);

export const config: Config = { path: "/api/catalog" };
