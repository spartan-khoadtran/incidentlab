export type Mode = "incident" | "toolbox" | "ramp" | "bench";
export type Verdict = "correct" | "partial" | "wrong";

export interface Option {
  key: string;
  label: string;
  group?: string;
}

export interface RampPlan {
  steps: number[];
  every: number;
  total_seconds: number;
}

export interface ActiveView {
  n: number;
  /** Random per exercise. Every answer, reveal and clear sends it, so a call never hits an exercise started since the page read this one. */
  key: string;
  mode: Mode;
  level: number | null;
  ticket: string;
  started_at: number;
  elapsed_seconds: number;
  hints_used: number;
  hints_total: number;
  hint_penalty: number;
  hints: string[];
  answered: boolean;
  rules: string | null;
  /** For an incident or a toolbox exercise: the only keys the answer takes. */
  options?: AnswerOptions;
  /** From the start of a ramp. */
  plan?: RampPlan;
  /** From the start of a bench. */
  ends_at?: number;
  /** Once an incident is answered or revealed. */
  reveal?: Reveal;
  /** Once it is answered: a Result for an incident, a RampResult for a ramp. */
  result?: Result | RampResult;
}

/** Drawn from what the ticket shows, so they say nothing the ticket did not. Causes come in layer order with a `group`. */
export interface AnswerOptions {
  natures: Option[];
  locations: Option[];
  signals: Option[];
  causes: Option[];
}

export interface GuideRef {
  pointer: string;
  file: string | null;
}

export interface Reveal {
  id: string;
  title: string;
  level: number;
  topics: string[];
  answer: { nature: Option[]; location: Option[]; signal: Option[]; cause: Option; partial: Option[] };
  explanation: string;
  steps: string[];
  evidence: string[];
  cli: string[];
  guide: GuideRef[];
  /** Every offered cause, in the offered order, with the check that rules it in or out. */
  rule_outs: RuleOut[];
}

export interface RuleOut {
  cause: Option;
  check: string;
  correct: boolean;
}

export interface Result {
  n: number;
  score: number;
  raw: number;
  max: number;
  hints_used: number;
  verdicts: { nature: Verdict; location: Verdict; signal: Verdict; cause: Verdict };
  missed: string[];
  reveal: Reveal;
}

export interface Status {
  services: Record<string, string | null>;
  active: ActiveView | null;
  load_multiplier: number;
  base_rps: number;
  links: { grafana: string; prometheus: string; pyroscope: string };
}

export interface Options {
  levels: number[];
  topics: string[];
  modes: string[];
}

export interface HistoryEntry {
  n: number;
  id: string;
  title: string;
  level: number;
  mode: Mode;
  score: number | null;
  hints: number;
  minutes: number;
  at: number;
  missed: string[];
  note: string;
}

/** `average`, `scored_count`, `by_topic` and `most_missed_cause` count scored incident and toolbox entries only. */
export interface History {
  entries: HistoryEntry[];
  average: number | null;
  scored_count: number;
  by_topic: { topic: string; average: number; count: number }[];
  most_missed_cause: { key: string; label: string; group: string | null; count: number } | null;
  ramp_average: number | null;
  ramp_count: number;
}

export interface Answer {
  nature: string;
  location: string;
  signal: string;
  cause: string;
  note?: string;
}

export interface NewExercise {
  mode?: "incident" | "toolbox";
  level?: number;
  topic?: string;
  id?: string;
  force?: boolean;
}

export interface RampRow {
  step: number;
  target_rps: number;
  rps_offered: number | null;
  rps_achieved: number | null;
  /** Seconds. */
  p99: number | null;
  error_share: number | null;
  knee: boolean;
}

export interface RampResult {
  n: number;
  title: string;
  score: number | null;
  max: number;
  steps_run: number;
  steps_total: number;
  table: RampRow[];
  measured: { knee_rps: number | null; knee_step: number | null; max_rps: number | null; resource: string; resource_label: string };
  given: { knee_rps: number; max_rps: number; resource: string } | null;
  verdicts: { knee: boolean; max: boolean; resource: boolean } | null;
  missed: string[];
  prometheus_down: boolean;
  explanation: string;
  guide: GuideRef[];
}

/** p50 and p99 in seconds, rates in requests per second. Null when Prometheus had no data. */
export interface BenchRow {
  rate_rps: number | null;
  p50: number | null;
  p99: number | null;
  error_share: number | null;
}

export interface BenchWindow {
  client: BenchRow;
  api: BenchRow;
  /** Seconds. */
  client_mean: number | null;
  api_in_flight: number | null;
}

export interface BenchReport {
  n: number;
  clients: number;
  seconds: number;
  before: BenchWindow;
  during: BenchWindow;
  explanation: string;
  guide: GuideRef[];
}

export interface QuizQuestion {
  index: number;
  kind: "number" | "choice";
  text: string;
  unit?: string;
  options?: string[];
}

export interface QuizVerdict {
  correct: boolean;
  skipped: boolean;
  answer: string;
  explanation: string;
  guide: GuideRef[];
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly body: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

const BASE = "/api";

async function call<T>(method: "GET" | "POST", path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method === "POST") {
    headers["Content-Type"] = "application/json";
    headers["X-Lab-Client"] = "web";
  }
  let response: Response;
  try {
    response = await fetch(new URL(`${BASE}${path}`, window.location.href), { method, headers, body: method === "POST" ? JSON.stringify(body ?? {}) : undefined, signal });
  } catch (e) {
    if ((e as Error).name === "AbortError") throw e;
    throw new ApiError(0, "offline", "The lab does not answer. Start it with make up.");
  }
  const text = await response.text();
  let json: Record<string, unknown>;
  try {
    json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    throw new ApiError(response.status, "offline", "The lab does not answer. Start it with make up.");
  }
  if (!response.ok) {
    throw new ApiError(response.status, String(json.error ?? "error"), String(json.message ?? `The lab answered ${response.status}.`), json);
  }
  return json as T;
}

export const api = {
  health: (signal?: AbortSignal) => call<{ ok: boolean }>("GET", "/health", undefined, signal),
  status: () => call<Status>("GET", "/status"),
  options: () => call<Options>("GET", "/options"),
  start: (body: NewExercise) => call<ActiveView>("POST", "/exercises", body),
  exercise: () => call<ActiveView>("GET", "/exercise"),
  hint: (key: string) => call<ActiveView>("POST", "/exercise/hint", { key }),
  answer: (key: string, body: Answer) => call<Result>("POST", "/exercise/answer", { ...body, key }),
  reveal: (key: string) => call<Reveal>("POST", "/exercise/reveal", { key }),
  /** Without a key it ends whatever is open: only for an exercise the page cannot read, such as a stale one. */
  clear: (key?: string) => call<{ cleared: boolean }>("POST", "/exercise/clear", key ? { key } : {}),
  history: () => call<History>("GET", "/history"),
  load: (multiplier: number) => call<{ multiplier: number; rps: number }>("POST", "/load", { multiplier }),
  ramp: (every?: number) => call<ActiveView>("POST", "/ramp", every ? { every } : {}),
  rampOptions: () => call<{ resources: Option[] }>("GET", "/ramp/options"),
  rampAnswer: (key: string, body: { knee_rps: number; max_rps: number; resource: string; note?: string }) => call<RampResult>("POST", "/ramp/answer", { ...body, key }),
  rampReveal: (key: string) => call<RampResult>("POST", "/ramp/reveal", { key }),
  bench: (body: { clients?: number; seconds?: number }) => call<ActiveView>("POST", "/bench", body),
  benchReport: () => call<BenchReport>("GET", "/bench/report"),
  benchClose: (key: string) => call<BenchReport>("POST", "/bench/report", { key }),
  quiz: (body: { count?: number; kind?: "numbers" | "concepts" | "all" }) => call<{ session: string; questions: QuizQuestion[] }>("POST", "/quiz", body),
  /** A number question also takes the text as typed (`"12,5"`); the daemon reads it like the CLI. */
  quizAnswer: (session: string, index: number, answer: number | string) =>
    call<QuizVerdict>("POST", `/quiz/${encodeURIComponent(session)}/${index}`, { answer }),
  quizSkip: (session: string, index: number) => call<QuizVerdict>("POST", `/quiz/${encodeURIComponent(session)}/${index}`, { skip: true }),
};

/** A number typed by a learner: a comma or a dot both work as the decimal sign, like the CLI. */
export function parseNumber(raw: string): number | null {
  const cleaned = raw.trim().replace(/\s/g, "").replace(",", ".");
  if (!/^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(cleaned)) return null;
  const value = Number(cleaned);
  return Number.isFinite(value) ? value : null;
}
