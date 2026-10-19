import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const SITE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const ROOT = resolve(SITE, "..");
export const ANSWERS_KT = "service/tool/labctl/src/main/kotlin/com/c0x12c/incidentlab/labctl/scenario/Answers.kt";

export function repoFile(path: string): string {
  return readFileSync(join(ROOT, path), "utf8");
}

export function siteFile(path: string): string {
  return readFileSync(join(SITE, path), "utf8");
}
