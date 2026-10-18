const PAIR = /"([a-z0-9_]+)" to "((?:[^"\\]|\\.)*)"/g;

function block(text: string, start: RegExp, end: RegExp): string {
  const from = text.search(start);
  if (from < 0) return "";
  const rest = text.slice(from);
  const to = rest.slice(1).search(end);
  return to < 0 ? rest : rest.slice(0, to + 1);
}

function pairs(text: string): [string, string][] {
  return [...text.matchAll(PAIR)].map((m) => [m[1], m[2]]);
}

/** The cause and signal keys and labels from Answers.kt, read as text so the site needs no JVM. */
export function readAnswers(answersKt: string): { causes: [string, string][]; signals: [string, string][] } {
  return {
    causes: pairs(block(answersKt, /CAUSES_BY_LAYER\s*:/, /\n\s*val CAUSES\s*:/)),
    signals: pairs(block(answersKt, /val SIGNALS\s*:/, /\n\s*\)\n/)),
  };
}

/**
 * Strings that would give an answer away. A scenario id is public (`make list` prints it), so a
 * cause key that is also an id is left out. Keys without an underscore are plain words and would
 * match ordinary text, so only the snake_case keys and the long labels are checked. A string the
 * docs already print (a metric or a view name) is public too.
 */
export function forbiddenStrings(answersKt: string, scenarioIds: string[], publicText = ""): string[] {
  const ids = new Set(scenarioIds);
  const { causes, signals } = readAnswers(answersKt);
  const keys = [...causes, ...signals].map(([k]) => k).filter((k) => k.includes("_") && !ids.has(k));
  const labels = [...causes, ...signals].map(([, label]) => label).filter((l) => l.length >= 40);
  return [...new Set([...keys, ...labels])].filter((s) => findLeaks(publicText, [s]).length === 0);
}

export function findLeaks(text: string, forbidden: string[]): string[] {
  return forbidden.filter((s) => {
    if (/^[a-z0-9_]+$/.test(s)) return new RegExp(`(^|[^a-zA-Z0-9_])${s}([^a-zA-Z0-9_]|$)`).test(text);
    return text.includes(s);
  });
}
