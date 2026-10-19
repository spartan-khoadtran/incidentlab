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
  levels: [1, 2, 3],
  topics: ["red", "load"],
  modes: ["incident", "toolbox"],
};

/** What the mock ticket offers. The lab knows more keys than this, and the answer refuses the others. */
export const ANSWER_OPTIONS = {
  natures: [OPTION("mock_load", "Mock nature: real load"), OPTION("mock_arch", "Mock nature: the system itself")],
  locations: [OPTION("mock_lb", "Mock location: before the app"), OPTION("mock_app", "Mock location: inside the app")],
  signals: [OPTION("mock_gap", "Mock signal: the gap between two layers"), OPTION("mock_rate", "Mock signal: rate by route")],
  causes: [
    OPTION("mock_queue", "Mock cause: a queue in front of the app", "Mock layer one"),
    OPTION("mock_retry", "Mock cause: retries", "Mock layer one"),
    OPTION("mock_cpu", "Mock cause: one hot core", "Mock layer two"),
  ],
};

/** Keys the mock lab knows but this ticket does not offer. */
export const NOT_OFFERED = {
  signal: OPTION("mock_disk_wait", "Mock signal: disk wait on the host"),
  cause: OPTION("mock_disk_full", "Mock cause: a full disk", "Mock layer three"),
};

const CHECKS: Record<string, string> = {
  mock_queue: "Mock check: the LB p99 rises while the api p99 stays flat.",
  mock_retry: "Mock check: attempts per request stay at 1, so it is not retries.",
  mock_cpu: "Mock check: process CPU stays far below one core.",
};

export const MOCK_REVEAL = {
  id: "mock_fault",
  title: "Mock fault for the handbook tests",
  level: 2,
  topics: ["red"],
  answer: {
    nature: [ANSWER_OPTIONS.natures[1]],
    location: [ANSWER_OPTIONS.locations[0]],
    signal: [ANSWER_OPTIONS.signals[0]],
    cause: ANSWER_OPTIONS.causes[0],
    partial: [ANSWER_OPTIONS.causes[1]],
  },
  explanation: "Mock explanation. The numbers of this run would be here.",
  steps: ["Mock step one.", "Mock step two.", "Mock step three.", "Mock step four."],
  evidence: ['sum(rate(http_server_requests_total{job="api"}[1m]))'],
  cli: ["curl -s http://api:8000/metrics | grep inflight   # mock: what to read"],
  guide: [{ pointer: "RED method in detail > Rate (mock note)", file: "investigation-guide.md" }],
  rule_outs: ANSWER_OPTIONS.causes.map((cause) => ({ cause, check: CHECKS[cause.key], correct: cause.key === "mock_queue" })),
};

const MOCK_HINTS = ["Mock hint one, broad.", "Mock hint two.", "Mock hint three, specific."];

interface MockActive {
  n: number;
  key: string;
  mode: string;
  level: number | null;
  started_at: number;
  hints_used: number;
  answered: boolean;
  revealed: boolean;
  result: unknown;
}

export class MockLab {
  online = true;
  active: MockActive | null = null;
  n = 0;
  history: { n: number; id: string; title: string; level: number; mode: string; score: number | null; hints: number; minutes: number; at: number; missed: string[]; note: string }[] = [];
  load = 1;
  quiz = new Map<string, { kind: string; answer: string }[]>();
  plan = { steps: [50, 75, 100, 125, 150, 200, 250, 300], every: 60, total_seconds: 480 };
  endsAt = 0;
  rampNote: string | null = null;
  /** How long ago a new ramp started. By default it is in its second step. */
  rampStartedAgo: number | null = null;
  /** One error to send on the next call to a route such as `POST /bench/report`. */
  failNext = new Map<string, { status: number; error: string; message: string }>();

  view() {
    const a = this.active!;
    return {
      n: a.n,
      key: a.key,
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
      ...(a.mode === "incident" || a.mode === "toolbox" ? { options: ANSWER_OPTIONS } : {}),
      ...((a.answered || a.revealed) && (a.mode === "incident" || a.mode === "toolbox") ? { reveal: MOCK_REVEAL } : {}),
      ...(a.answered && a.result ? { result: a.result } : {}),
      ...(a.mode === "ramp" ? { plan: this.plan } : {}),
      ...(a.mode === "bench" ? { ends_at: this.endsAt } : {}),
    };
  }

  handle(method: string, path: string, headers: Record<string, string | null>, body: Record<string, unknown>): MockReply {
    const err = (status: number, error: string, message: string, extra: Record<string, unknown> = {}) => ({ status, body: { error, message, ...extra } });
    if (method === "POST" && (headers["content-type"] !== "application/json" || headers["x-lab-client"] !== "web")) return err(403, "forbidden", "Forbidden.");
    const route = `${method} ${path.replace(/^\/api/, "")}`;
    const failure = this.failNext.get(route);
    if (failure) {
      this.failNext.delete(route);
      return err(failure.status, failure.error, failure.message);
    }
    const changed = () =>
      typeof body.key === "string" && body.key !== this.active?.key
        ? err(409, "exercise_changed", "Mock: the exercise on this page is no longer the open one.")
        : null;
    const incident = () => {
      const other = changed();
      if (other) return other;
      if (!this.active) return err(404, "no_exercise", "No exercise is open.");
      if (this.active.mode !== "incident" && this.active.mode !== "toolbox") return err(409, "wrong_mode", `The open exercise is a ${this.active.mode}.`);
      return null;
    };
    const fresh = (mode: string, started_at: number, level: number | null = null): MockActive => ({
      n: this.n,
      key: `mock-key-${this.n}`,
      mode,
      level,
      started_at,
      hints_used: 0,
      answered: false,
      revealed: false,
      result: null,
    });
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
        this.active = fresh(String(body.mode ?? "incident"), Date.now() / 1000 - 125, (body.level as number) ?? 2);
        return { status: 201, body: this.view() };
      }
      case route === "GET /exercise":
        return this.active ? { status: 200, body: this.view() } : err(404, "no_exercise", "No exercise is open.");
      case route === "POST /exercise/hint": {
        if (changed()) return changed()!;
        if (this.active && (this.active.mode === "ramp" || this.active.mode === "bench")) return err(409, "no_hints_left", `A ${this.active.mode} has no hints.`);
        const refused = incident();
        if (refused) return refused;
        if (this.active!.answered) return err(409, "already_answered", "This exercise was already submitted.");
        if (this.active!.hints_used >= MOCK_HINTS.length) return err(409, "no_hints_left", "No hint left.");
        this.active!.hints_used += 1;
        return { status: 200, body: this.view() };
      }
      case route === "POST /exercise/answer": {
        const keys = ["nature", "location", "signal", "cause"] as const;
        const lists = { nature: ANSWER_OPTIONS.natures, location: ANSWER_OPTIONS.locations, signal: ANSWER_OPTIONS.signals, cause: ANSWER_OPTIONS.causes };
        const missing = keys.find((k) => typeof body[k] !== "string");
        if (missing) return err(400, "missing_field", `The field '${missing}' is needed.`);
        const unknown = keys.find((k) => !lists[k].some((o) => o.key === body[k]));
        const refused = incident();
        if (refused) return refused;
        if (unknown) return err(400, "unknown_key", `'${String(body[unknown])}' is not a ${unknown} this exercise offers.`);
        if (this.active!.answered) return err(409, "already_answered", "This exercise was already submitted.");
        const verdicts = {
          nature: body.nature === "mock_arch" ? "correct" : "wrong",
          location: body.location === "mock_lb" ? "correct" : "wrong",
          signal: body.signal === "mock_gap" ? "correct" : "wrong",
          cause: body.cause === "mock_queue" ? "correct" : body.cause === "mock_retry" ? "partial" : "wrong",
        };
        const raw = (verdicts.nature === "correct" ? 1 : 0) + (verdicts.location === "correct" ? 1 : 0) + (verdicts.signal === "correct" ? 1 : 0) + (verdicts.cause === "correct" ? 2 : verdicts.cause === "partial" ? 1 : 0);
        const active = this.active!;
        const score = Math.max(0, raw - 0.5 * active.hints_used);
        const missed = keys.filter((k) => verdicts[k] !== "correct");
        this.history.push({ n: active.n, id: MOCK_REVEAL.id, title: MOCK_REVEAL.title, level: 2, mode: active.mode, score, hints: active.hints_used, minutes: 4.2, at: Date.now() / 1000, missed, note: String(body.note ?? "") });
        active.answered = true;
        active.result = { n: active.n, score, raw, max: 5, hints_used: active.hints_used, verdicts, missed, reveal: MOCK_REVEAL };
        return { status: 200, body: active.result };
      }
      case route === "POST /exercise/reveal": {
        const refused = incident();
        if (refused) return refused;
        const active = this.active!;
        if (!active.answered) this.history.push({ n: active.n, id: MOCK_REVEAL.id, title: MOCK_REVEAL.title, level: 2, mode: active.mode, score: null, hints: active.hints_used, minutes: 4.2, at: Date.now() / 1000, missed: [], note: "" });
        active.answered = true;
        active.revealed = true;
        return { status: 200, body: MOCK_REVEAL };
      }
      case route === "POST /exercise/clear": {
        if (this.active && changed()) return changed()!;
        const cleared = this.active !== null;
        this.active = null;
        return { status: 200, body: { cleared } };
      }
      case route === "GET /history": {
        const scored = this.history.filter((e) => e.score !== null && (e.mode === "incident" || e.mode === "toolbox"));
        const ramps = this.history.filter((e) => e.score !== null && e.mode === "ramp");
        const mean = (list: { score: number | null }[]) => (list.length ? list.reduce((s, e) => s + (e.score ?? 0), 0) / list.length : null);
        return {
          status: 200,
          body: {
            entries: this.history,
            average: mean(scored),
            scored_count: scored.length,
            by_topic: scored.length ? [{ topic: "red", average: 3.5, count: scored.length }] : [],
            most_missed_cause: scored.length ? { group: null, ...ANSWER_OPTIONS.causes[2], count: 1 } : null,
            ramp_average: mean(ramps),
            ramp_count: ramps.length,
          },
        };
      }
      case route === "POST /load": {
        const m = Number(body.multiplier);
        if (!(m >= 0.1 && m <= 6)) return err(400, "bad_multiplier", "Mock: multiplier must be a number from 0.1 to 6.");
        const rampLeft = this.active?.mode === "ramp" ? Math.ceil(this.active.started_at + this.plan.total_seconds - Date.now() / 1000) : 0;
        if (rampLeft > 0) return err(409, "ramp_running", `Mock: a ramp is running for ${rampLeft} s more.`, { seconds_left: rampLeft });
        this.load = m;
        return { status: 200, body: { multiplier: m, rps: Math.round(m * 50) } };
      }
      case route === "POST /ramp": {
        if (this.active) return err(409, "exercise_open", "An exercise is open.");
        if (this.load !== 1) return err(409, "load_not_one", "Set the load back to 1 first.");
        this.n += 1;
        const every = Number(body.every ?? 60);
        this.active = fresh("ramp", Date.now() / 1000 - (this.rampStartedAgo ?? every + 5));
        this.plan = { ...this.plan, every, total_seconds: this.plan.steps.length * every };
        return { status: 201, body: this.view() };
      }
      case route === "GET /ramp/options":
        return { status: 200, body: { resources: [OPTION("mock_pool", "Mock resource: the pool"), OPTION("mock_cores", "Mock resource: api CPU")] } };
      case route === "POST /ramp/answer":
      case route === "POST /ramp/reveal": {
        const answered = route.endsWith("answer");
        const resources = ["mock_pool", "mock_cores"];
        if (answered) {
          const missing = ["knee_rps", "max_rps", "resource"].find((k) => body[k] === undefined || body[k] === null);
          if (missing) return err(400, "missing_field", `The field '${missing}' is needed.`);
        }
        if (changed()) return changed()!;
        if (!this.active) return err(404, "no_exercise", "No exercise is open.");
        const active = this.active;
        if (active.mode !== "ramp") return err(409, "wrong_mode", `The open exercise is a ${active.mode}.`);
        if (answered) {
          if (active.answered) return err(409, "already_answered", "This ramp was already submitted.");
          const left = Math.ceil(active.started_at + this.plan.total_seconds - Date.now() / 1000);
          if (left > 0) return err(409, "too_early", `The last step is not over yet: ${left} s to go.`, { seconds_left: left });
          if (!resources.includes(String(body.resource))) return err(400, "unknown_key", `'${String(body.resource)}' is not a resource key.`);
          this.rampNote = typeof body.note === "string" ? body.note : null;
        }
        const result = {
          n: active.n,
          title: "Capacity ramp",
          score: answered ? 4 : null,
          max: 5,
          steps_run: 2,
          steps_total: 8,
          table: [
            { step: 1, target_rps: 50, rps_offered: 50, rps_achieved: 50, p99: 0.08, error_share: 0, knee: false },
            { step: 2, target_rps: 75, rps_offered: 75.2, rps_achieved: 74.6, p99: 0.31, error_share: 0.004, knee: true },
          ],
          measured: { knee_rps: 75, knee_step: 2, max_rps: 74.6, resource: "mock_pool", resource_label: "Mock resource: the pool" },
          given: answered ? { knee_rps: Number(body.knee_rps), max_rps: Number(body.max_rps), resource: String(body.resource) } : null,
          verdicts: answered ? { knee: true, max: true, resource: false } : null,
          missed: answered ? ["resource"] : [],
          prometheus_down: false,
          explanation: "Mock ramp explanation.",
          guide: [{ pointer: "Capacity from a ramp test > Finding the knee", file: "capacity.md" }],
        };
        if (!active.answered) this.history.push({ n: active.n, id: "ramp", title: result.title, level: 0, mode: "ramp", score: result.score, hints: 0, minutes: 8, at: Date.now() / 1000, missed: result.missed, note: this.rampNote ?? "" });
        active.answered = true;
        if (answered) active.result = result;
        return { status: 200, body: result };
      }
      case route === "POST /bench": {
        if (this.active) return err(409, "exercise_open", "An exercise is open.");
        this.n += 1;
        this.active = fresh("bench", Date.now() / 1000);
        this.endsAt = Date.now() / 1000 - 1;
        return { status: 201, body: this.view() };
      }
      case route === "GET /bench/report":
      case route === "POST /bench/report": {
        const row = (rate: number, p50: number, p99: number) => ({ rate_rps: rate, p50, p99, error_share: 0 });
        if (changed()) return changed()!;
        if (!this.active) return err(404, "no_exercise", "No exercise is open.");
        if (this.active.mode !== "bench") return err(409, "wrong_mode", `The open exercise is a ${this.active.mode}.`);
        if (route.startsWith("POST")) {
          this.history.push({ n: this.active.n, id: "bench", title: "Closed-loop bench", level: 0, mode: "bench", score: null, hints: 0, minutes: 2, at: Date.now() / 1000, missed: [], note: "" });
          this.active = null;
        }
        return {
          status: 200,
          body: {
            n: this.n,
            clients: 20,
            seconds: 120,
            before: { client: row(50, 0.04, 0.12), api: row(50, 0.03, 0.1), client_mean: 0.05, api_in_flight: 2.5 },
            during: { client: row(410, 0.03, 0.035), api: row(410, 0.03, 0.034), client_mean: 0.031, api_in_flight: 19.8 },
            explanation: "Mock bench explanation.",
            guide: [{ pointer: "Closed-loop clients in this lab > What make bench does", file: "capacity.md" }],
          },
        };
      }
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
        return { status: 201, body: { session, questions } };
      }
      default: {
        const quiz = route.match(/^POST \/quiz\/([^/]+)\/(\d+)$/);
        if (quiz) {
          const session = this.quiz.get(quiz[1]);
          if (!session) return err(404, "no_session", "Mock: this quiz is over or has expired.");
          const q = session[Number(quiz[2])];
          if (!q) return err(404, "no_question", `Mock: this quiz has no question ${quiz[2]}.`);
          if (body.skip === true) {
            return { status: 200, body: { correct: false, skipped: true, answer: q.answer, explanation: "Mock explanation: L = λ × W.", guide: [{ pointer: "Linking RED and USE > Little's Law", file: "investigation-guide.md" }] } };
          }
          const given = typeof body.answer === "string" && q.kind === "number" ? Number(body.answer.replace(",", ".")) : body.answer;
          const correct = q.kind === "number" ? Math.abs(Number(given) - Number(q.answer)) < 0.5 : given === q.answer;
          return { status: 200, body: { correct, skipped: false, answer: q.answer, explanation: "Mock explanation: L = λ × W.", guide: [{ pointer: "Linking RED and USE > Little's Law", file: "investigation-guide.md" }] } };
        }
        return err(404, "not_found", "No such endpoint.");
      }
    }
  }
}
