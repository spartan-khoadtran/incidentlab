/**
 * A fake labctl API that follows the contract, for the tests (through msw) and for
 * `LAB_MOCK=1 npm run dev`. Every scenario, key and text here is made up, so no real answer
 * can leak through a fixture.
 */
export interface MockReply {
  status: number;
  body: unknown;
}

const OPTION = (key: string, label: string, group?: string) => (group ? { key, label, group } : { key, label });

export const OPTIONS = {
  natures: [OPTION("mock_load", "Mock nature: real load"), OPTION("mock_arch", "Mock nature: the system itself")],
  locations: [OPTION("mock_lb", "Mock location: before the app"), OPTION("mock_app", "Mock location: inside the app")],
  signals: [OPTION("mock_gap", "Mock signal: the gap between two layers"), OPTION("mock_rate", "Mock signal: rate by route")],
  causes: [
    OPTION("mock_queue", "Mock cause: a queue in front of the app", "Mock layer one"),
    OPTION("mock_retry", "Mock cause: retries", "Mock layer one"),
    OPTION("mock_cpu", "Mock cause: one hot core", "Mock layer two"),
  ],
  levels: [1, 2, 3],
  topics: ["red", "load"],
  modes: ["incident", "toolbox"],
};

export const MOCK_REVEAL = {
  id: "mock_fault",
  title: "Mock fault for the handbook tests",
  level: 2,
  topics: ["red"],
  answer: { nature: [OPTIONS.natures[1]], location: [OPTIONS.locations[0]], signal: [OPTIONS.signals[0]], cause: OPTIONS.causes[0], partial: [OPTIONS.causes[1]] },
  explanation: "Mock explanation. The numbers of this run would be here.",
  steps: ["Mock step one.", "Mock step two.", "Mock step three.", "Mock step four."],
  evidence: ['sum(rate(http_server_requests_total{job="api"}[1m]))'],
  cli: ["curl -s http://api:8000/metrics | grep inflight   # mock: what to read"],
  guide: [{ pointer: "RED method in detail > Rate (mock note)", file: "investigation-guide.md" }],
};

const MOCK_HINTS = ["Mock hint one, broad.", "Mock hint two.", "Mock hint three, specific."];

export class MockLab {
  online = true;
  active: null | { n: number; mode: string; level: number | null; started_at: number; hints_used: number; answered: boolean; revealed: boolean } = null;
  n = 0;
  history: unknown[] = [];
  load = 1;
  quiz = new Map<string, { kind: string; answer: string }[]>();

  view() {
    const a = this.active!;
    return {
      n: a.n,
      mode: a.mode,
      level: a.level,
      ticket: `At 14:05, a mock teammate reports that the mock checkout is slow. Ticket number ${a.n}.`,
      started_at: a.started_at,
      elapsed_seconds: Date.now() / 1000 - a.started_at,
      hints_used: a.hints_used,
      hints_total: MOCK_HINTS.length,
      hint_penalty: 0.5,
      hints: MOCK_HINTS.slice(0, a.hints_used),
      answered: a.answered,
      rules: a.mode === "toolbox" ? "Mock rules: no Grafana, prove it from make shell." : null,
      ...(a.answered || a.revealed ? { reveal: MOCK_REVEAL } : {}),
    };
  }

  handle(method: string, path: string, headers: Record<string, string | null>, body: Record<string, unknown>): MockReply {
    const err = (status: number, error: string, message: string, extra: Record<string, unknown> = {}) => ({ status, body: { error, message, ...extra } });
    if (method === "POST" && (headers["content-type"] !== "application/json" || headers["x-lab-client"] !== "web")) return err(403, "forbidden", "Forbidden.");
    const route = `${method} ${path.replace(/^\/api/, "")}`;
    switch (true) {
      case route === "GET /health":
        return { status: 200, body: { ok: true } };
      case route === "GET /status":
        return {
          status: 200,
          body: {
            services: { api: null },
            active: this.active ? this.view() : null,
            load_multiplier: this.load,
            base_rps: 50,
            links: { grafana: "http://localhost:3000", prometheus: "http://localhost:9090", pyroscope: "http://localhost:4040" },
          },
        };
      case route === "GET /options":
        return { status: 200, body: OPTIONS };
      case route === "POST /exercises": {
        if (this.active && !body.force) return err(409, "exercise_open", "An exercise is open. Answer, reveal or clear it first.");
        this.n += 1;
        this.active = { n: this.n, mode: String(body.mode ?? "incident"), level: (body.level as number) ?? 2, started_at: Date.now() / 1000 - 125, hints_used: 0, answered: false, revealed: false };
        return { status: 201, body: this.view() };
      }
      case route === "GET /exercise":
        return this.active ? { status: 200, body: this.view() } : err(404, "no_exercise", "No exercise is open.");
      case route === "POST /exercise/hint":
        if (!this.active) return err(404, "no_exercise", "No exercise is open.");
        if (this.active.hints_used >= MOCK_HINTS.length) return err(409, "no_hints_left", "No hint left.");
        this.active.hints_used += 1;
        return { status: 200, body: this.view() };
      case route === "POST /exercise/answer": {
        if (!this.active) return err(404, "no_exercise", "No exercise is open.");
        if (this.active.answered) return err(409, "already_answered", "Already answered.");
        const keys = ["nature", "location", "signal", "cause"] as const;
        const lists = { nature: OPTIONS.natures, location: OPTIONS.locations, signal: OPTIONS.signals, cause: OPTIONS.causes };
        if (keys.some((k) => !lists[k].some((o) => o.key === body[k]))) return err(400, "bad_answer", "Unknown answer key.");
        const verdicts = {
          nature: body.nature === "mock_arch" ? "correct" : "wrong",
          location: body.location === "mock_lb" ? "correct" : "wrong",
          signal: body.signal === "mock_gap" ? "correct" : "wrong",
          cause: body.cause === "mock_queue" ? "correct" : body.cause === "mock_retry" ? "partial" : "wrong",
        };
        const raw = (verdicts.nature === "correct" ? 1 : 0) + (verdicts.location === "correct" ? 1 : 0) + (verdicts.signal === "correct" ? 1 : 0) + (verdicts.cause === "correct" ? 2 : verdicts.cause === "partial" ? 1 : 0);
        const score = Math.max(0, raw - 0.5 * this.active.hints_used);
        this.active.answered = true;
        const missed = keys.filter((k) => verdicts[k] !== "correct");
        this.history.push({ n: this.active.n, id: MOCK_REVEAL.id, title: MOCK_REVEAL.title, level: 2, mode: this.active.mode, score, hints: this.active.hints_used, minutes: 4.2, at: Date.now() / 1000, missed, note: String(body.note ?? "") });
        return { status: 200, body: { score, raw, max: 5, hints_used: this.active.hints_used, verdicts, missed, reveal: MOCK_REVEAL } };
      }
      case route === "POST /exercise/reveal":
        if (!this.active) return err(404, "no_exercise", "No exercise is open.");
        this.active.revealed = true;
        return { status: 200, body: MOCK_REVEAL };
      case route === "POST /exercise/clear":
        this.active = null;
        return { status: 200, body: { cleared: true } };
      case route === "GET /history": {
        const scored = (this.history as { score: number | null }[]).filter((e) => e.score !== null);
        return {
          status: 200,
          body: {
            entries: this.history,
            average: scored.length ? scored.reduce((s, e) => s + (e.score ?? 0), 0) / scored.length : null,
            by_topic: scored.length ? [{ topic: "red", average: 3.5, count: scored.length }] : [],
            most_missed_cause: scored.length ? OPTIONS.causes[2] : null,
          },
        };
      }
      case route === "POST /load": {
        const m = Number(body.multiplier);
        if (!(m >= 0.1 && m <= 6)) return err(400, "bad_load", "X must be between 0.1 and 6.");
        this.load = m;
        return { status: 200, body: { multiplier: m, rps: Math.round(m * 50) } };
      }
      case route === "POST /ramp": {
        if (this.active) return err(409, "exercise_open", "An exercise is open.");
        if (this.load !== 1) return err(409, "load_not_default", "Set the load back to 1 first.");
        this.n += 1;
        const every = Number(body.every ?? 60);
        this.active = { n: this.n, mode: "ramp", level: null, started_at: Date.now() / 1000 - every - 5, hints_used: 0, answered: false, revealed: false };
        const steps = [50, 75, 100, 125, 150, 200, 250, 300];
        return { status: 201, body: { ...this.view(), plan: { steps, every, total_seconds: steps.length * every } } };
      }
      case route === "GET /ramp/options":
        return { status: 200, body: [OPTION("mock_pool", "Mock resource: the pool"), OPTION("mock_cores", "Mock resource: api CPU")] };
      case route === "POST /ramp/answer":
        return {
          status: 200,
          body: {
            score: 2,
            max: 3,
            table: [
              { rps_offered: 50, rps_achieved: 50, p99: 0.08, error_share: 0 },
              { rps_offered: 75, rps_achieved: 74.6, p99: 0.31, error_share: 0.004 },
            ],
            measured: { knee_rps: 75, max_rps: 75, resource: "Mock resource: the pool" },
            explanation: "Mock ramp explanation.",
          },
        };
      case route === "POST /bench": {
        if (this.active) return err(409, "exercise_open", "An exercise is open.");
        this.n += 1;
        this.active = { n: this.n, mode: "bench", level: null, started_at: Date.now() / 1000, hints_used: 0, answered: false, revealed: false };
        return { status: 201, body: { ...this.view(), ends_at: Date.now() / 1000 - 1 } };
      }
      case route === "GET /bench/report":
        return { status: 200, body: { before: { p99_ms: 120, rps: 50 }, during: { p99_ms: 35, rps: 410 }, explanation: "Mock bench explanation." } };
      case route === "POST /quiz": {
        const session = `s${this.quiz.size + 1}`;
        const questions = [
          { index: 0, kind: "number", text: "Mock: 200 rps at 50 ms each. How many requests are in flight?", unit: "requests" },
          { index: 1, kind: "choice", text: "Mock: which layer sees the time a request waits before the app?", options: ["The load balancer", "The app", "The database"] },
        ].slice(0, Math.max(1, Number(body.count ?? 2)));
        this.quiz.set(session, [
          { kind: "number", answer: "10" },
          { kind: "choice", answer: "The load balancer" },
        ]);
        return { status: 200, body: { session, questions } };
      }
      default: {
        const quiz = route.match(/^POST \/quiz\/([^/]+)\/(\d+)$/);
        if (quiz) {
          const q = this.quiz.get(quiz[1])?.[Number(quiz[2])];
          if (!q) return err(404, "no_question", "No such question.");
          const correct = q.kind === "number" ? Math.abs(Number(body.answer) - Number(q.answer)) < 0.5 : body.answer === q.answer;
          return { status: 200, body: { correct, answer: q.answer, explanation: "Mock explanation: L = λ × W.", guide: [{ pointer: "Linking RED and USE > Little's Law", file: "investigation-guide.md" }] } };
        }
        return err(404, "not_found", "No such endpoint.");
      }
    }
  }
}
