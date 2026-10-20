const PAIR = /"([a-z0-9_]+)" to "((?:[^"\\]|\\.)*)"/g;

function block(text: string, start: RegExp, end: RegExp): string {
  const from = text.search(start);
  if (from < 0) return "";
  const rest = text.slice(from);
  const to = rest.slice(1).search(end);
  return to < 0 ? rest : rest.slice(0, to + 1);
}

function pairs(text: string): [string, string][] {
  return [...text.matchAll(PAIR)].map((m) => [m[1], m[2].replace(/\\(.)/g, "$1")]);
}

/** The cause and signal keys and labels, and the rule-out checks, from Answers.kt, read as text so the site needs no JVM. */
export function readAnswers(answersKt: string): { causes: [string, string][]; signals: [string, string][]; ruleOuts: [string, string][] } {
  return {
    causes: pairs(block(answersKt, /CAUSES_BY_LAYER\s*:/, /\n\s*val CAUSES\s*:/)),
    signals: pairs(block(answersKt, /val SIGNALS\s*:/, /\n\s*\)\n/)),
    ruleOuts: pairs(block(answersKt, /val RULE_OUTS\s*:/, /\n\s*\)\n/)),
  };
}

/**
 * Strings that would give an answer away. A scenario id is public (`make list` prints it), so a
 * cause key that is also an id is left out. Keys without an underscore are plain words and would
 * match ordinary text, so only the snake_case keys, the long labels and the long rule-out checks
 * are checked. A string the docs already print (a metric or a view name) is public too.
 */
export function forbiddenStrings(answersKt: string, scenarioIds: string[], publicText = ""): string[] {
  const ids = new Set(scenarioIds);
  const { causes, signals, ruleOuts } = readAnswers(answersKt);
  const keys = [...causes, ...signals].map(([k]) => k).filter((k) => k.includes("_") && !ids.has(k));
  const labels = [...causes, ...signals, ...ruleOuts].map(([, text]) => text).filter((t) => t.length >= 40);
  return [...new Set([...keys, ...labels])].filter((s) => findLeaks(publicText, [s]).length === 0);
}

export function findLeaks(text: string, forbidden: string[]): string[] {
  return forbidden.filter((s) => {
    if (/^[a-z0-9_]+$/.test(s)) return new RegExp(`(^|[^a-zA-Z0-9_])${s}([^a-zA-Z0-9_]|$)`).test(text);
    return text.includes(s);
  });
}

const NOTE = /\s*\([^)]*\)$/;

/**
 * The `doc` pointers of the catalog sources. `withNotes` is the pointer as the CLI prints it and
 * each part with its note. `plainParts` is each part without its note.
 */
export function scenarioPointers(catalogSources: string[]): { docs: string[]; withNotes: string[]; plainParts: string[] } {
  const docs = catalogSources.flatMap((text) => [...text.matchAll(/^\s+doc = "([^"]+)"/gm)].map((m) => m[1]));
  const withNotes = [...new Set(docs.flatMap((d) => [d, ...d.split("; ")]).filter((p) => NOTE.test(p) || p.includes("; ")))];
  const plainParts = [...new Set(docs.flatMap((d) => d.split("; ").map((p) => p.replace(NOTE, "").trim())))];
  return { docs, withNotes, plainParts };
}

/**
 * The scenario pointers found in one built file. The guide prints the same `Section > Subsection`
 * line under every heading, so in a guide chunk only the pointers with a note count.
 */
export function pointerLeaks(text: string, guideChunk: boolean, pointers: { withNotes: string[]; plainParts: string[] }): string[] {
  const subsectionParts = pointers.plainParts.filter((p) => p.includes(" > "));
  return (guideChunk ? pointers.withNotes : [...pointers.withNotes, ...subsectionParts]).filter((p) => text.includes(p));
}

/** Matches the chunk Vite builds for one docs file, like `/assets/capacity-AbC123.js`. */
export function guideChunkPattern(docNames: string[]): RegExp {
  return new RegExp(`/assets/(${docNames.join("|")})-[\\w-]+\\.js$`);
}
