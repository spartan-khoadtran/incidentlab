import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseCompose } from "./lib/compose.ts";
import { groupOf } from "./lib/groups.ts";
import { parseMakefile } from "./lib/makefile.ts";
import { renderDoc } from "./lib/markdown.ts";
import { buildGradleNote, firstSentence, parseDocNotes, parseModuleNotes, parseReadme, parseSettings } from "./lib/repo.ts";

const SITE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(process.env.LAB_ROOT ?? join(SITE, ".."));
const OUT = join(SITE, "src", "generated");

function read(path: string): string {
  return readFileSync(join(ROOT, path), "utf8");
}

function write(name: string, data: unknown) {
  const path = join(OUT, name);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(data) + "\n");
}

interface LabJson {
  scenarios: { id: string; title: string; level: number; topics: string[]; toolbox: boolean }[];
}

rmSync(OUT, { recursive: true, force: true });

const docFiles = readdirSync(join(ROOT, "docs")).filter((f) => f.endsWith(".md")).sort();
const known = new Set(docFiles.map((f) => f.replace(/\.md$/, "")));
const claudeMd = existsSync(join(ROOT, "CLAUDE.md")) ? read("CLAUDE.md") : "";
const notes = parseDocNotes(claudeMd);
const moduleNotes = parseModuleNotes(claudeMd);

const docs = docFiles.map((file) => {
  const slug = file.replace(/\.md$/, "");
  const rendered = renderDoc(read(`docs/${file}`), known);
  write(`docs/${slug}.json`, { slug, html: rendered.html });
  return { slug, file, ...groupOf(slug), title: rendered.title, lede: rendered.lede, note: notes[file] ?? null, words: rendered.words, headings: rendered.headings };
});
docs.sort((a, b) => a.order - b.order || a.slug.localeCompare(b.slug));

write(
  "guide.json",
  docs.map(({ headings, ...d }) => ({ ...d, headings: headings.map(({ body: _body, ...h }) => h) })),
);

const make = parseMakefile(read("Makefile"));
write("make.json", make);

const compose = parseCompose(read("docker-compose.yml"));
const KEEP_ENV = /(_URL|_HOST|_ADDRESS|_PARTITIONS)$/;
const SHARED_ENV = new Set(["DB_URL"]);
write("compose.json", {
  ...compose,
  services: compose.services.map((s) => ({ ...s, env: Object.fromEntries(Object.entries(s.env).filter(([k]) => KEEP_ENV.test(k) && !SHARED_ENV.has(k) && !k.startsWith("DD_"))) })),
});

const readme = parseReadme(read("README.md"));
const gradle = parseSettings(read("service/settings.gradle")).map((g) => ({
  ...g,
  modules: g.modules.map((m) => {
    const path = join(ROOT, "service", m.dir, "build.gradle");
    const note = existsSync(path) ? buildGradleNote(readFileSync(path, "utf8")) : null;
    const fallback = moduleNotes[m.dir.split("/").pop() ?? ""];
    const text = note ? firstSentence(note.replace(/^[a-z:-]+: /, "")) : fallback ? `${fallback}.` : null;
    return { ...m, note: text ? `${text[0].toUpperCase()}${text.slice(1)}` : null };
  }),
}));
const firstLine = (text: string, pattern: RegExp) => text.split("\n").find((l) => pattern.test(l))?.replace(pattern, "").trim() ?? null;
const files = {
  readme: firstLine(read("README.md"), /^# /),
  claude: claudeMd ? (claudeMd.split("\n\n")[1]?.trim() ?? null) : null,
  makefile: firstLine(read("Makefile"), /^# /),
};
write("repo.json", { readme, gradle, files });

const inThisLab = (() => {
  const html = (JSON.parse(readFileSync(join(OUT, "docs", "investigation-guide.json"), "utf8")) as { html: string }).html;
  const start = html.indexOf('<h2 id="in-this-lab"');
  if (start < 0) return null;
  const end = html.indexOf("<h2", start + 3);
  const section = html.slice(start, end < 0 ? undefined : end);
  return section.slice(section.indexOf("</h2>") + 5).replaceAll('href="#', 'href="/guide/investigation-guide#');
})();
write("highlights.json", { inThisLab });

const lab = JSON.parse(readFileSync(join(SITE, "src", "data", "lab.json"), "utf8")) as LabJson;
const clip = (text: string) => (text.length > 220 ? `${text.slice(0, 217).replace(/\s+\S*$/, "")}...` : text);
const search = [
  ...docs.flatMap((d) =>
    d.headings
      .filter((h) => h.depth <= 3)
      .map((h) => ({
        kind: "guide",
        title: h.text,
        context: h.depth === 3 && h.section ? `${d.title} · ${h.section}` : d.title,
        body: clip(h.body),
        href: `/guide/${d.slug}#${h.id}`,
      })),
  ),
  ...docs.map((d) => ({ kind: "guide", title: d.title, context: d.file, body: clip(d.lede), href: `/guide/${d.slug}` })),
  ...lab.scenarios.map((s) => ({
    kind: "scenario",
    title: s.title,
    context: `Level ${s.level} · ${s.topics.join(", ")}${s.toolbox ? " · toolbox" : ""}`,
    body: s.id,
    href: `/catalog?q=${encodeURIComponent(s.id)}#${s.id}`,
  })),
  ...make.groups.flatMap((g) => g.targets.map((t) => ({ kind: "command", title: `make ${t.name}`, context: g.title, body: t.help, href: `/commands#make-${t.name}` }))),
];
write("search.json", search);

console.log(`prebuild: ${docs.length} docs, ${make.groups.reduce((n, g) => n + g.targets.length, 0)} make targets, ${compose.services.length} services, ${search.length} search entries`);
