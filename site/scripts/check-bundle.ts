import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { findLeaks, forbiddenStrings } from "./lib/spoilers.ts";

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(process.env.LAB_ROOT ?? join(SITE, ".."));
const ANSWERS = "service/tool/labctl/src/main/kotlin/com/c0x12c/incidentlab/labctl/scenario/Answers.kt";

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

const lab = JSON.parse(readFileSync(join(SITE, "src/data/lab.json"), "utf8")) as { scenarios: { id: string }[] };
const docs = readdirSync(join(ROOT, "docs"))
  .filter((f) => f.endsWith(".md"))
  .map((f) => readFileSync(join(ROOT, "docs", f), "utf8"))
  .join("\n");
const forbidden = forbiddenStrings(readFileSync(join(ROOT, ANSWERS), "utf8"), lab.scenarios.map((s) => s.id), docs);
if (forbidden.length < 10) {
  console.error(`check-bundle: read only ${forbidden.length} answer strings from ${ANSWERS}. Did its format change?`);
  process.exit(1);
}

const leaks = files(join(SITE, "dist"))
  .filter((f) => /\.(js|html|css|json|svg|txt)$/.test(f))
  .flatMap((f) => findLeaks(readFileSync(f, "utf8"), forbidden).map((s) => `${relative(SITE, f)}: ${s}`));

if (leaks.length) {
  console.error("check-bundle: the built site contains answer strings:\n  " + leaks.join("\n  "));
  process.exit(1);
}
console.log(`check-bundle: no answer string in dist (${forbidden.length} checked)`);
