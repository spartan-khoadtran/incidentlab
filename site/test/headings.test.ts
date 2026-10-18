import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { copyKind, renderDoc } from "../scripts/lib/markdown.ts";
import { resolvePointer, type PointerDoc } from "../src/lib/pointers.ts";
import { ROOT, repoFile, siteFile } from "./paths.ts";

const FIXTURE = [
  "# Title",
  "",
  "Intro with a link to [the queue](#why-latency-rises-before-100) and [cpu](cpu.md#busy-polling).",
  "",
  "## Linking RED and USE",
  "",
  "### Why latency rises before 100%",
  "",
  "Text with `rate(x_total[1m])`, `make new` and `word`.",
  "",
  "### Little's Law",
  "",
  "## Other section",
  "",
  "### Little's Law",
  "",
  "| a | b |",
  "|---|---|",
  "| 1 | 2 |",
].join("\n");

function docs(): PointerDoc[] {
  const files = readdirSync(join(ROOT, "docs")).filter((f) => f.endsWith(".md"));
  const known = new Set(files.map((f) => f.replace(/\.md$/, "")));
  return files.map((f) => {
    const r = renderDoc(repoFile(`docs/${f}`), known);
    return { slug: f.replace(/\.md$/, ""), title: r.title, headings: r.headings };
  });
}

describe("renderDoc", () => {
  const doc = renderDoc(FIXTURE, new Set(["cpu"]));

  it("gives GitHub slugs, so the links the docs write between themselves keep working", () => {
    expect(doc.headings.map((h) => [h.depth, h.id])).toEqual([
      [2, "linking-red-and-use"],
      [3, "why-latency-rises-before-100"],
      [3, "littles-law"],
      [2, "other-section"],
      [3, "littles-law-1"],
    ]);
    expect(doc.html).toContain('<h3 id="why-latency-rises-before-100">');
    expect(doc.html).toContain('href="/guide/cpu#busy-polling"');
  });

  it("prints under each subsection the pointer the CLI prints", () => {
    expect(doc.html).toContain('data-copy="Linking RED and USE > Why latency rises before 100%"');
    expect(doc.headings.find((h) => h.id === "littles-law-1")?.section).toBe("Other section");
  });

  it("marks PromQL and commands as copyable, and leaves plain words alone", () => {
    expect(copyKind("rate(x_total[1m])")).toBe("promql");
    expect(copyKind("sum by (le) (rate(x_bucket[1m]))")).toBe("promql");
    expect(copyKind("make new")).toBe("command");
    expect(copyKind("kafka_consumer_lag_seconds")).toBe("metric");
    expect(copyKind("word")).toBeNull();
    expect(doc.html).toContain('<span class="tok-fn">rate</span>');
  });

  it("wraps tables so a wide one scrolls on a phone", () => {
    expect(doc.html).toMatch(/<div class="table-wrap"[^>]*><table>/);
  });
});

describe("pointers", () => {
  const all = docs();

  it("resolves a pointer to the subsection anchor, keeps the note, and skips a second subsection with the same title", () => {
    const fixture: PointerDoc[] = [{ slug: "x", title: "X", headings: renderDoc(FIXTURE, new Set()).headings }];
    const [part] = resolvePointer("Other section > Little's Law (the note)", fixture);
    expect(part.href).toBe("/guide/x#littles-law-1");
    expect(part.note).toBe("the note");
    expect(resolvePointer("Nowhere > Rate", fixture)[0].href).toBeNull();
  });

  it("finds every pointer of the catalog and the modes in the docs", () => {
    const lab = JSON.parse(siteFile("src/data/lab.json")) as { scenarios: { id: string; doc: string }[]; modes: { id: string; doc: string }[] };
    const broken = [...lab.scenarios, ...lab.modes].flatMap((s) => resolvePointer(s.doc, all).filter((p) => !p.href).map((p) => `${s.id}: ${p.label}`));
    expect(broken).toEqual([]);
  });

  it("keeps every in-page link of the docs pointing at a heading that exists", () => {
    const files = readdirSync(join(ROOT, "docs")).filter((f) => f.endsWith(".md"));
    const known = new Set(files.map((f) => f.replace(/\.md$/, "")));
    const ids = new Map(all.map((d) => [d.slug, new Set(d.headings.map((h) => h.id))]));
    const broken: string[] = [];
    for (const f of files) {
      const slug = f.replace(/\.md$/, "");
      const html = renderDoc(repoFile(`docs/${f}`), known).html;
      for (const m of html.matchAll(/href="(?:\/guide\/([a-z0-9-]+))?#([^"]+)"/g)) {
        const target = m[1] ?? slug;
        if (!ids.get(target)?.has(m[2]) && !html.includes(`id="${m[2]}"`)) broken.push(`${f} -> ${target}#${m[2]}`);
      }
    }
    expect(broken).toEqual([]);
  });

  it("has a unique section title across the docs files, like GuidePointersTest", () => {
    const titles = all.flatMap((d) => d.headings.filter((h) => h.depth === 2).map((h) => h.raw));
    expect(titles.length).toBe(new Set(titles).size);
  });
});
