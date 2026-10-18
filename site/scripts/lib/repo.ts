import type { Root, RootContent } from "mdast";
import { toString as mdToString } from "mdast-util-to-string";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";

export interface GradleModule {
  path: string;
  dir: string;
  note: string | null;
}

export interface GradleGroup {
  name: string;
  note: string;
  modules: GradleModule[];
}

export interface ReadmeData {
  lede: string;
  trains: string[];
  layout: { path: string; note: string }[];
}

function parseMd(text: string): Root {
  const p = unified().use(remarkParse).use(remarkGfm);
  return p.runSync(p.parse(text)) as Root;
}

function flat(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/** `include(...)` blocks of settings.gradle, each named by the `// ─── name: note ───` line above it. */
export function parseSettings(text: string): GradleGroup[] {
  const groups: GradleGroup[] = [];
  const header = /^\/\/ ─+ ([^:]+): (.+?) ─+\s*$/;
  let current: GradleGroup | null = null;
  let inInclude = false;
  for (const line of text.split("\n")) {
    const h = line.match(header);
    if (h) {
      current = { name: h[1].trim(), note: h[2].trim(), modules: [] };
      groups.push(current);
      continue;
    }
    if (/^include\(/.test(line)) inInclude = true;
    if (inInclude && current) {
      for (const m of line.matchAll(/"([^"]+)"/g)) {
        current.modules.push({ path: m[1], dir: m[1].replace(/:/g, "/"), note: null });
      }
    }
    if (inInclude && line.includes(")")) inInclude = false;
  }
  return groups;
}

/** The comment block at the top of a module's build.gradle, which says what the module holds. */
export function buildGradleNote(text: string): string | null {
  const lines: string[] = [];
  for (const line of text.split("\n")) {
    if (line.startsWith("// ")) lines.push(line.slice(3));
    else if (lines.length > 0) break;
  }
  return lines.length ? flat(lines.join(" ")) : null;
}

/** First sentence of a note, so a long header comment still fits one line of a tree. */
export function firstSentence(text: string): string {
  const m = text.match(/^(.+?[.!?])(\s|$)/);
  return (m ? m[1] : text).trim();
}

function sectionNodes(tree: Root, title: string): RootContent[] {
  const start = tree.children.findIndex((n) => n.type === "heading" && n.depth === 2 && mdToString(n) === title);
  if (start < 0) return [];
  const rest = tree.children.slice(start + 1);
  const end = rest.findIndex((n) => n.type === "heading" && n.depth <= 2);
  return end < 0 ? rest : rest.slice(0, end);
}

function bulletPairs(nodes: RootContent[]): { path: string; note: string }[] {
  const out: { path: string; note: string }[] = [];
  for (const node of nodes) {
    if (node.type !== "list") continue;
    for (const item of node.children) {
      const para = item.children[0];
      if (para?.type !== "paragraph") continue;
      const first = para.children[0];
      if (first?.type !== "inlineCode") continue;
      const note = flat(mdToString(para)).slice(first.value.length).replace(/^:\s*/, "");
      out.push({ path: first.value, note });
    }
  }
  return out;
}

export function parseReadme(text: string): ReadmeData {
  const tree = parseMd(text);
  const h1 = tree.children.findIndex((n) => n.type === "heading" && n.depth === 1);
  const body = tree.children.slice(h1 + 1);
  const firstParagraph = body.find((n) => n.type === "paragraph");
  const firstList = body.find((n) => n.type === "list");
  return {
    lede: firstParagraph ? flat(mdToString(firstParagraph)) : "",
    trains: firstList && firstList.type === "list" ? firstList.children.map((i) => flat(mdToString(i))) : [],
    layout: bulletPairs(sectionNodes(tree, "Layout")),
  };
}

/** The `- \`file.md\`: what it covers` bullets CLAUDE.md keeps for the docs folder. */
export function parseDocNotes(claudeMd: string): Record<string, string> {
  const out: Record<string, string> = {};
  const tree = parseMd(claudeMd);
  for (const { path, note } of bulletPairs(sectionNodes(tree, "What this is"))) {
    if (path.endsWith(".md")) out[path] = note.replace(/\.$/, "");
  }
  return out;
}

/** `module-x` (what it holds) pairs from the Gradle layout part of CLAUDE.md, for a module whose build.gradle says nothing. */
export function parseModuleNotes(claudeMd: string): Record<string, string> {
  const start = claudeMd.indexOf("### Gradle layout");
  if (start < 0) return {};
  const rest = claudeMd.slice(start + 1);
  const section = rest.slice(0, rest.search(/\n#{2,3} /) + 1 || undefined);
  const out: Record<string, string> = {};
  for (const m of section.matchAll(/`(module-[a-z-]+)` \(((?:[^()]|\([^()]*\))*)\)/g)) {
    out[m[1]] = m[2].replace(/`/g, "");
  }
  return out;
}
