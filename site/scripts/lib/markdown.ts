import GithubSlugger from "github-slugger";
import type { Element, ElementContent, Root as HastRoot } from "hast";
import { toString as hastToString } from "hast-util-to-string";
import type { Heading, Root as MdastRoot, RootContent } from "mdast";
import { toString as mdToString } from "mdast-util-to-string";
import rehypeStringify from "rehype-stringify";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import remarkRehype from "remark-rehype";
import { unified } from "unified";
import { visit } from "unist-util-visit";
import { looksLikePromql, tokenize } from "./promql.ts";

export interface DocHeading {
  depth: number;
  /** The heading as written after the hashes, the way GuidePointersTest reads it. */
  raw: string;
  text: string;
  id: string;
  /** The raw title of the `## ` section this heading sits in (itself for a `## `). */
  section: string | null;
  /** Plain text under this heading, up to the next heading of any depth. */
  body: string;
}

export interface RenderedDoc {
  title: string;
  lede: string;
  headings: DocHeading[];
  html: string;
  words: number;
}

const COMMAND = /^(make|curl|cd|docker|vmstat|mpstat|pidstat|iostat|sar|ss|nstat|free|dmesg|top|uptime|perf|bpftrace|psql|cat|grep|ls|ps|kill|cg|pid (?:api|pricing|worker|noisy|postgres|SERVICE)|containers|nsenter|tc|ip|pgrep|toolbox-help|kernel-check|offcputime|profile|biolatency|runqlat|execsnoop|opensnoop|tcpretrans|strace|ltrace|jcmd|jstack|watch)\b/;
const METRIC = /^[a-z][a-z0-9]*(?:_[a-z0-9]+){2,}$/;

/** Turns `cpu.md#busy-polling` into the site route for that doc. */
export function guideHref(href: string, knownDocs: Set<string>): string {
  const m = href.match(/^(?:\.\/)?([a-z0-9-]+)\.md(#.*)?$/);
  if (m && knownDocs.has(m[1])) return `/guide/${m[1]}${m[2] ?? ""}`;
  return href;
}

export function copyKind(code: string): "promql" | "command" | "metric" | null {
  if (looksLikePromql(code)) return "promql";
  if (COMMAND.test(code) && code.length > 3) return "command";
  if (METRIC.test(code)) return "metric";
  return null;
}

function headingRaw(node: Heading, source: string): string {
  const start = node.position?.start.offset;
  const end = node.position?.end.offset;
  if (start === undefined || end === undefined) return mdToString(node);
  return source.slice(start, end).replace(/^#+\s*/, "").replace(/\s+#+\s*$/, "").trim();
}

function bodyText(nodes: RootContent[]): string {
  return nodes.map((n) => mdToString(n)).join(" ").replace(/\s+/g, " ").trim();
}

/** Ids follow GitHub's slugs, so the links the docs already write between themselves keep working. */
function collectHeadings(tree: MdastRoot, source: string): DocHeading[] {
  const slugger = new GithubSlugger();
  const out: DocHeading[] = [];
  let section: string | null = null;
  let collecting: { heading: DocHeading; nodes: RootContent[] } | null = null;
  const flush = () => {
    if (collecting) collecting.heading.body = bodyText(collecting.nodes);
  };
  for (const node of tree.children) {
    if (node.type === "heading") {
      flush();
      const raw = headingRaw(node, source);
      const text = mdToString(node);
      const id = slugger.slug(text);
      if (node.depth <= 2) section = node.depth === 2 ? raw : null;
      const heading: DocHeading = { depth: node.depth, raw, text, id, section, body: "" };
      node.data = { ...node.data, hProperties: { ...(node.data?.hProperties ?? {}), id } };
      out.push(heading);
      collecting = { heading, nodes: [] };
    } else if (collecting) {
      collecting.nodes.push(node);
    }
  }
  flush();
  return out;
}

function promqlChildren(code: string): ElementContent[] {
  return tokenize(code).map((t) =>
    t.kind === "text" ? { type: "text", value: t.value } : { type: "element", tagName: "span", properties: { className: [`tok-${t.kind}`] }, children: [{ type: "text", value: t.value }] },
  );
}

function decorate(knownDocs: Set<string>, headings: DocHeading[]) {
  const byId = new Map(headings.map((h) => [h.id, h]));
  return (tree: HastRoot) => {
    visit(tree, "element", (node: Element, index, parent) => {
      if (node.tagName === "a" && typeof node.properties.href === "string") {
        const href = guideHref(node.properties.href, knownDocs);
        node.properties.href = href;
        if (/^https?:/.test(href)) {
          node.properties.target = "_blank";
          node.properties.rel = ["noopener", "noreferrer"];
        }
      }
      if (node.tagName === "code" && parent && (parent as Element).tagName !== "pre") {
        const text = hastToString(node);
        const kind = copyKind(text);
        if (kind) {
          node.properties.className = ["copyable", `code-${kind}`];
          node.properties.dataCopy = text;
          node.properties.tabIndex = 0;
          node.properties.role = "button";
          node.properties.title = "Click to copy";
          if (kind === "promql") node.children = promqlChildren(text);
        }
      }
      if (node.tagName === "pre" && parent && index !== undefined) {
        const text = hastToString(node);
        const lang = String(((node.children[0] as Element)?.properties?.className as string[] | undefined)?.[0] ?? "").replace("language-", "");
        if (lang === "promql") (node.children[0] as Element).children = promqlChildren(text);
        parent.children[index] = {
          type: "element",
          tagName: "div",
          properties: { className: ["code-block"] },
          children: [
            node,
            { type: "element", tagName: "button", properties: { type: "button", className: ["copy-btn"], dataCopy: text.replace(/\n$/, "") }, children: [{ type: "text", value: "Copy" }] },
          ],
        };
        return "skip";
      }
      if (node.tagName === "table" && parent && index !== undefined) {
        parent.children[index] = {
          type: "element",
          tagName: "div",
          properties: { className: ["table-wrap"], tabIndex: 0, role: "region", ariaLabel: "Table" },
          children: [node],
        };
        return "skip";
      }
      if (/^h[2-4]$/.test(node.tagName) && typeof node.properties.id === "string") {
        const heading = byId.get(node.properties.id);
        node.children = [
          ...node.children,
          { type: "element", tagName: "a", properties: { className: ["h-anchor"], href: `#${node.properties.id}`, ariaLabel: `Link to ${hastToString(node)}` }, children: [{ type: "text", value: "#" }] },
        ];
        if (heading?.depth === 3 && heading.section && parent && index !== undefined) {
          const pointer = `${heading.section} > ${heading.raw}`;
          parent.children.splice(index + 1, 0, {
            type: "element",
            tagName: "p",
            properties: { className: ["pointer"] },
            children: [
              { type: "element", tagName: "span", properties: { className: ["pointer-label"] }, children: [{ type: "text", value: "pointer" }] },
              { type: "element", tagName: "code", properties: { className: ["copyable", "code-pointer"], dataCopy: pointer, tabIndex: 0, role: "button", title: "Click to copy" }, children: [{ type: "text", value: pointer }] },
            ],
          });
        }
      }
      return undefined;
    });
  };
}

/** Parses one docs file once: the heading index and the HTML come from the same tree, so ids always match. */
export function renderDoc(source: string, knownDocs: Set<string>): RenderedDoc {
  const parser = unified().use(remarkParse).use(remarkGfm);
  const tree = parser.runSync(parser.parse(source)) as MdastRoot;
  const headings = collectHeadings(tree, source);
  const h1Index = tree.children.findIndex((n) => n.type === "heading" && n.depth === 1);
  const title = h1Index >= 0 ? mdToString(tree.children[h1Index]) : "";
  const firstParagraph = tree.children.slice(h1Index + 1).find((n) => n.type === "paragraph");
  const lede = firstParagraph ? mdToString(firstParagraph) : "";
  if (h1Index >= 0) tree.children.splice(h1Index, 1);

  const processor = unified().use(remarkRehype).use(() => decorate(knownDocs, headings)).use(rehypeStringify);
  const html = processor.stringify(processor.runSync(tree) as HastRoot);

  const words = mdToString(tree).split(/\s+/).filter(Boolean).length;
  return { title, lede, headings: headings.filter((h) => h.depth > 1), html, words };
}
