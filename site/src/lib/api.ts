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
  reveal?: Reveal;
  plan?: RampPlan;
  ends_at?: number;
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
}

export interface Result {
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
  natures: Option[];
  locations: Option[];
  signals: Option[];
  causes: Option[];
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

export interface History {
  entries: HistoryEntry[];
  average: number | null;
  by_topic: { topic: string; average: number; count: number }[];
  most_missed_cause: Option | null;
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
  rps_offered: number;
  rps_achieved: number;
  p99: number;
  error_share: number;
}

export interface RampResult {
  score: number;
  max: number;
  table: RampRow[];
  measured: { knee_rps: number; max_rps: number; resource: string };
  explanation: string;
}

export type BenchRow = Record<string, number | string | null>;

export type BenchReport = { before: BenchRow; during: BenchRow; explanation: string };

export interface QuizQuestion {
  index: number;
  kind: "number" | "choice";
  text: string;
  unit?: string;
  options?: string[];
}

export interface QuizVerdict {
  correct: boolean;
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
  hint: () => call<ActiveView>("POST", "/exercise/hint"),
  answer: (body: Answer) => call<Result>("POST", "/exercise/answer", body),
  reveal: () => call<Reveal>("POST", "/exercise/reveal"),
  clear: () => call<{ cleared: boolean }>("POST", "/exercise/clear"),
  history: () => call<History>("GET", "/history"),
  load: (multiplier: number) => call<{ multiplier: number; rps: number }>("POST", "/load", { multiplier }),
  ramp: (every?: number) => call<ActiveView>("POST", "/ramp", every ? { every } : {}),
  rampOptions: () => call<Option[] | { resources: Option[] }>("GET", "/ramp/options"),
  rampAnswer: (body: { knee_rps: number; max_rps: number; resource: string }) => call<RampResult>("POST", "/ramp/answer", body),
  bench: (body: { clients?: number; seconds?: number }) => call<ActiveView>("POST", "/bench", body),
  benchReport: () => call<BenchReport>("GET", "/bench/report"),
  quiz: (body: { count?: number; kind?: "numbers" | "concepts" | "all" }) => call<{ session: string; questions: QuizQuestion[] }>("POST", "/quiz", body),
  quizAnswer: (session: string, index: number, answer: number | string) =>
    call<QuizVerdict>("POST", `/quiz/${encodeURIComponent(session)}/${index}`, { answer }),
};

/** `GET /api/ramp/options` is written as "resource Options"; accept a bare list or one wrapped in an object. */
export function rampResources(body: Option[] | Record<string, unknown>): Option[] {
  if (Array.isArray(body)) return body;
  const list = Object.values(body).find(Array.isArray);
  return (list as Option[] | undefined) ?? [];
}

/** A number typed by a learner: a comma or a dot both work as the decimal sign, like the CLI. */
export function parseNumber(raw: string): number | null {
  const cleaned = raw.trim().replace(/\s/g, "").replace(",", ".");
  if (!/^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(cleaned)) return null;
  const value = Number(cleaned);
  return Number.isFinite(value) ? value : null;
}
