import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { findLeaks, forbiddenStrings, readAnswers } from "../scripts/lib/spoilers.ts";
import { ANSWERS_KT, ROOT, SITE, repoFile, siteFile } from "./paths.ts";

const lab = JSON.parse(siteFile("src/data/lab.json")) as { scenarios: Record<string, unknown>[] };
const ids = lab.scenarios.map((s) => String(s.id));
const docsText = readdirSync(join(ROOT, "docs"))
  .filter((f) => f.endsWith(".md"))
  .map((f) => repoFile(`docs/${f}`))
  .join("\n");
const forbidden = forbiddenStrings(repoFile(ANSWERS_KT), ids, docsText);

const CATALOG = "service/tool/labctl/src/main/kotlin/com/c0x12c/incidentlab/labctl/scenario/catalog";
const scenarioDocs = readdirSync(join(ROOT, CATALOG))
  .filter((f) => f.endsWith(".kt"))
  .flatMap((f) => [...repoFile(`${CATALOG}/${f}`).matchAll(/^\s+doc = "([^"]+)"/gm)].map((m) => m[1]));
const NOTE = /\s*\([^)]*\)$/;
/** The pointer as the CLI prints it, and each part with its note. */
const withNotes = [...new Set(scenarioDocs.flatMap((d) => [d, ...d.split("; ")]).filter((p) => NOTE.test(p) || p.includes("; ")))];
/**
 * Each part without its note. The guide prints the same `Section > Subsection` line under every
 * heading, and a bare section title is a guide nav entry, so in the bundle those two are public.
 */
const plainParts = [...new Set(scenarioDocs.flatMap((d) => d.split("; ").map((p) => p.replace(NOTE, "").trim())))];
const guideChunk = new RegExp(`/assets/(${readdirSync(join(ROOT, "docs")).filter((f) => f.endsWith(".md")).map((f) => f.replace(/\.md$/, "")).join("|")})-[\\w-]+\\.js$`);

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

describe("no spoilers", () => {
  it("reads the cause and signal keys from Answers.kt", () => {
    const { causes, signals } = readAnswers(repoFile(ANSWERS_KT));
    expect(causes.length).toBeGreaterThan(30);
    expect(signals.length).toBeGreaterThan(15);
    expect(causes.map(([k]) => k)).toContain("pool_exhausted");
    expect(forbidden).toContain("pool_exhausted");
    expect(forbidden).not.toContain("n_plus_one");
  });

  it("finds a key as a whole word only", () => {
    expect(findLeaks("cause: pool_exhausted.", ["pool_exhausted"])).toEqual(["pool_exhausted"]);
    expect(findLeaks("db_pool_exhausted_total", ["pool_exhausted"])).toEqual([]);
  });

  it("keeps lab.json to the fields the catalog shows", () => {
    for (const s of lab.scenarios) expect(Object.keys(s).sort()).toEqual(["id", "level", "title", "toolbox", "topics"]);
    expect(findLeaks(siteFile("src/data/lab.json"), forbidden)).toEqual([]);
  });

  it("reads one guide pointer per scenario from the catalog sources", () => {
    expect(scenarioDocs.length).toBe(lab.scenarios.length);
    expect(plainParts.length).toBeGreaterThan(20);
  });

  it("keeps every scenario's guide pointer out of lab.json, whole or in parts", () => {
    const text = siteFile("src/data/lab.json");
    expect([...withNotes, ...plainParts].filter((p) => text.includes(p))).toEqual([]);
  });

  it.runIf(existsSync(join(SITE, "dist")))("keeps every scenario's guide pointer out of the built bundle", () => {
    const leaks = files(join(SITE, "dist"))
      .filter((f) => /\.(js|html|css|json)$/.test(f))
      .flatMap((f) => {
        const text = readFileSync(f, "utf8");
        const subsectionParts = plainParts.filter((p) => p.includes(" > "));
        const pointers = guideChunk.test(f) ? withNotes : [...withNotes, ...subsectionParts];
        return pointers.filter((p) => text.includes(p)).map((p) => `${f}: ${p}`);
      });
    expect(leaks).toEqual([]);
  });

  it("keeps the generated data and the source free of answer strings", () => {
    const leaks = [...files(join(SITE, "src")), ...files(join(SITE, "scripts"))]
      .filter((f) => !f.endsWith("spoilers.ts"))
      .flatMap((f) => findLeaks(readFileSync(f, "utf8"), forbidden).map((s) => `${f}: ${s}`));
    expect(leaks).toEqual([]);
  });

  it.runIf(existsSync(join(SITE, "dist")))("keeps the built bundle free of answer strings", () => {
    const leaks = files(join(SITE, "dist"))
      .filter((f) => /\.(js|html|css|json)$/.test(f))
      .flatMap((f) => findLeaks(readFileSync(f, "utf8"), forbidden).map((s) => `${f}: ${s}`));
    expect(leaks).toEqual([]);
  });
});
