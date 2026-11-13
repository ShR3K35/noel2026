// Publie le contrat d'import avec le site : schéma, guide pour l'agent, exemples.
import { cpSync, mkdirSync } from "node:fs";
mkdirSync("public/schemas", { recursive: true });
cpSync("../contract/program-import-v1.schema.json", "public/schemas/program-import-v1.json");
cpSync("../contract", "public/contract", { recursive: true });
