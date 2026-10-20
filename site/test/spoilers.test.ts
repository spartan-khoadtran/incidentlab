import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { findLeaks, forbiddenStrings, guideChunkPattern, pointerLeaks, readAnswers, scenarioPointers } from "../scripts/lib/spoilers.ts";
import { ANSWERS_KT, ROOT, SITE, repoFile, siteFile } from "./paths.ts";

const lab = JSON.parse(siteFile("src/data/lab.json")) as { scenarios: Record<string, unknown>[] };
const ids = lab.scenarios.map((s) => String(s.id));
const docsText = readdirSync(join(ROOT, "docs"))
  .filter((f) => f.endsWith(".md"))
  .map((f) => repoFile(`docs/${f}`))
  .join("\n");
const forbidden = forbiddenStrings(repoFile(ANSWERS_KT), ids, docsText);

const CATALOG = "service/tool/labctl/src/main/kotlin/com/c0x12c/incidentlab/labctl/scenario/catalog";
const pointers = scenarioPointers(
  readdirSync(join(ROOT, CATALOG))
    .filter((f) => f.endsWith(".kt"))
    .map((f) => repoFile(`${CATALOG}/${f}`)),
);
const { docs: scenarioDocs, withNotes, plainParts } = pointers;
const guideChunk = guideChunkPattern(readdirSync(join(ROOT, "docs")).filter((f) => f.endsWith(".md")).map((f) => f.replace(/\.md$/, "")));

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

  it("reads the rule-out checks from Answers.kt and forbids the long ones", () => {
    const { ruleOuts } = readAnswers(repoFile(ANSWERS_KT));
    expect(ruleOuts.length).toBeGreaterThan(30);
    const long = ruleOuts.map(([, text]) => text).filter((t) => t.length >= 40 && !docsText.includes(t));
    expect(long.length).toBeGreaterThan(30);
    for (const text of long) expect(forbidden).toContain(text);
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

  it.runIf(existsSync(join(SITE, "dist")))("never bundles the fake lab the tests and LAB_MOCK use", () => {
    const leaks = files(join(SITE, "dist"))
      .filter((f) => /\.(js|html|json)$/.test(f))
      .filter((f) => /mock_fault|Mock fault for the handbook|MockLab/.test(readFileSync(f, "utf8")));
    expect(leaks).toEqual([]);
  });

  it("keeps the only reveal and answer calls in the API client", () => {
    const callers = files(join(SITE, "src"))
      .filter((f) => /\.(ts|tsx)$/.test(f) && !f.endsWith("api.ts"))
      .filter((f) => /\/exercise\/(reveal|answer)|\/quiz\/\$\{/.test(readFileSync(f, "utf8")));
    expect(callers).toEqual([]);
  });

  it.runIf(existsSync(join(SITE, "dist")))("keeps every scenario's guide pointer out of the built bundle", () => {
    const leaks = files(join(SITE, "dist"))
      .filter((f) => /\.(js|html|css|json)$/.test(f))
      .flatMap((f) => pointerLeaks(readFileSync(f, "utf8"), guideChunk.test(f), pointers).map((p) => `${f}: ${p}`));
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
