import type { Root, RootContent } from "mdast";
import { toString as mdToString } from "mdast-util-to-string";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";

export interface ReadmeData {
  lede: string;
  trains: string[];
}

function parseMd(text: string): Root {
  const p = unified().use(remarkParse).use(remarkGfm);
  return p.runSync(p.parse(text)) as Root;
}

function flat(text: string): string {
  return text.replace(/\s+/g, " ").trim();
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
