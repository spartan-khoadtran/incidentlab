import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { findLeaks, forbiddenStrings, guideChunkPattern, pointerLeaks, scenarioPointers } from "./lib/spoilers.ts";

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(process.env.LAB_ROOT ?? join(SITE, ".."));
const ANSWERS = "service/tool/labctl/src/main/kotlin/com/c0x12c/incidentlab/labctl/scenario/Answers.kt";
const CATALOG = "service/tool/labctl/src/main/kotlin/com/c0x12c/incidentlab/labctl/scenario/catalog";

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

const lab = JSON.parse(readFileSync(join(SITE, "src/data/lab.json"), "utf8")) as { scenarios: { id: string }[] };
const docNames = readdirSync(join(ROOT, "docs"))
  .filter((f) => f.endsWith(".md"))
  .map((f) => f.replace(/\.md$/, ""));
const docs = docNames.map((name) => readFileSync(join(ROOT, "docs", `${name}.md`), "utf8")).join("\n");
const forbidden = forbiddenStrings(readFileSync(join(ROOT, ANSWERS), "utf8"), lab.scenarios.map((s) => s.id), docs);
if (forbidden.length < 10) {
  console.error(`check-bundle: read only ${forbidden.length} answer strings from ${ANSWERS}. Did its format change?`);
  process.exit(1);
}

const pointers = scenarioPointers(
  readdirSync(join(ROOT, CATALOG))
    .filter((f) => f.endsWith(".kt"))
    .map((f) => readFileSync(join(ROOT, CATALOG, f), "utf8")),
);
if (pointers.docs.length !== lab.scenarios.length) {
  console.error(`check-bundle: read ${pointers.docs.length} guide pointers from ${CATALOG} for ${lab.scenarios.length} scenarios. Did its format change?`);
  process.exit(1);
}
const guideChunk = guideChunkPattern(docNames);

const leaks = files(join(SITE, "dist"))
  .filter((f) => /\.(js|html|css|json|svg|txt)$/.test(f))
  .flatMap((f) => {
    const text = readFileSync(f, "utf8");
    const found = [...findLeaks(text, forbidden), ...pointerLeaks(text, guideChunk.test(f), pointers)];
    return found.map((s) => `${relative(SITE, f)}: ${s}`);
  });

if (leaks.length) {
  console.error("check-bundle: the built site contains answer strings or scenario guide pointers:\n  " + leaks.join("\n  "));
  process.exit(1);
}
console.log(`check-bundle: no answer string or scenario guide pointer in dist (${forbidden.length} strings, ${pointers.withNotes.length + pointers.plainParts.length} pointers checked)`);
