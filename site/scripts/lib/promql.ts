export type TokenKind = "fn" | "kw" | "str" | "dur" | "num" | "metric" | "label" | "op" | "punct" | "text";

export interface Token {
  kind: TokenKind;
  value: string;
}

const FUNCTIONS = new Set([
  "abs", "absent", "absent_over_time", "avg", "avg_over_time", "ceil", "changes", "clamp", "clamp_max", "clamp_min", "count",
  "count_over_time", "delta", "deriv", "floor", "histogram_quantile", "holt_winters", "idelta", "increase", "irate", "label_replace",
  "last_over_time", "max", "max_over_time", "min", "min_over_time", "predict_linear", "quantile", "quantile_over_time", "rate", "resets",
  "round", "scalar", "sort", "sort_desc", "sqrt", "stddev", "sum", "sum_over_time", "time", "timestamp", "topk", "bottomk", "vector",
]);
const KEYWORDS = new Set(["by", "without", "offset", "on", "ignoring", "group_left", "group_right", "bool", "and", "or", "unless"]);

const PATTERN =
  /("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(\[\d+[smhdwy](?::\d*[smhdwy]?)?\])|(\b\d+(?:\.\d+)?(?:e[+-]?\d+)?\b)|([a-zA-Z_:][a-zA-Z0-9_:]*)|(=~|!~|!=|==|>=|<=|[-+*/%^=<>])|([(){}[\],])|(\s+)|(.)/g;

/** True when an inline code span reads as a PromQL expression rather than a word or a command. */
export function looksLikePromql(code: string): boolean {
  const fn = code.match(/^([a-z_]+)\s*\(/);
  if (fn && FUNCTIONS.has(fn[1])) return true;
  const grouped = code.match(/^([a-z_]+)\s+(by|without)\s*\(/);
  if (grouped && FUNCTIONS.has(grouped[1])) return true;
  if (/^[a-z_:][a-z0-9_:]*\{[^}]*\}/.test(code)) return true;
  if (/^[a-z_:][a-z0-9_:]*\[\d+[smhdwy]\]/.test(code)) return true;
  return false;
}

const GROUPING = new Set(["by", "without", "on", "ignoring", "group_left", "group_right"]);

export function tokenize(code: string): Token[] {
  const tokens: Token[] = [];
  let braceDepth = 0;
  let afterGrouping = false;
  let inGrouping = false;
  for (const m of code.matchAll(PATTERN)) {
    const [whole, str, dur, num, word, op, punct, space] = m;
    if (str) tokens.push({ kind: "str", value: str });
    else if (dur) tokens.push({ kind: "dur", value: dur });
    else if (num) tokens.push({ kind: "num", value: num });
    else if (word) {
      const next = code.slice((m.index ?? 0) + word.length).trimStart();
      let kind: TokenKind = "metric";
      if (braceDepth > 0 || inGrouping) kind = "label";
      else if (KEYWORDS.has(word)) kind = "kw";
      else if (FUNCTIONS.has(word) && next.startsWith("(")) kind = "fn";
      tokens.push({ kind, value: word });
      afterGrouping = kind === "kw" && GROUPING.has(word);
    } else if (op) {
      tokens.push({ kind: "op", value: op });
      afterGrouping = false;
    } else if (punct) {
      if (punct === "{") braceDepth++;
      if (punct === "}") braceDepth = Math.max(0, braceDepth - 1);
      if (punct === "(" && afterGrouping) inGrouping = true;
      if (punct === ")") inGrouping = false;
      if (punct !== ",") afterGrouping = false;
      tokens.push({ kind: "punct", value: punct });
    } else if (space) tokens.push({ kind: "text", value: space });
    else tokens.push({ kind: "text", value: whole });
  }
  return merge(tokens);
}

function merge(tokens: Token[]): Token[] {
  const out: Token[] = [];
  for (const t of tokens) {
    const last = out[out.length - 1];
    if (last && last.kind === t.kind && (t.kind === "text" || t.kind === "punct")) last.value += t.value;
    else out.push({ ...t });
  }
  return out;
}
