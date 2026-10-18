import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { Countdown, ErrorNote, useSecondsSince } from "../../components/LabUi";
import { api, ApiError, parseNumber, rampResources, type ActiveView, type BenchReport, type Option, type RampPlan, type RampResult } from "../../lib/api";
import { lab } from "../../lib/data";
import { useLab } from "../../lib/lab";

const PLAN_KEY = "incidentlab-ramp-plan";
const BENCH_KEY = "incidentlab-bench-end";

function remember(key: string, n: number, value: unknown) {
  try {
    sessionStorage.setItem(key, JSON.stringify({ n, value }));
  } catch {
    /* the page still works, only a reload loses the countdown */
  }
}

function recall<T>(key: string, n: number): T | null {
  try {
    const saved = JSON.parse(sessionStorage.getItem(key) ?? "null") as { n: number; value: T } | null;
    return saved && saved.n === n ? saved.value : null;
  } catch {
    return null;
  }
}

function LoadControl() {
  const { status, refresh } = useLab();
  const limits = lab.limits.load_multiplier;
  const current = status?.load_multiplier ?? 1;
  const base = status?.base_rps ?? lab.defaults.base_rps;
  const [value, setValue] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const shown = value ?? current;

  return (
    <section className="panel" aria-labelledby="load-title">
      <h2 id="load-title" className="panel-title">
        Background load
      </h2>
      <p className="small muted">
        Same as <code>make load X={shown.toFixed(1)}</code>. The ramp only runs at 1.0.
      </p>
      <div className="load-row">
        <input
          type="range"
          min={limits.min}
          max={limits.max}
          step={0.1}
          value={shown}
          aria-label="Load multiplier"
          aria-valuetext={`${shown.toFixed(1)} times, about ${Math.round(shown * base)} requests per second`}
          onChange={(e) => setValue(Number(e.target.value))}
        />
        <output className="load-out">
          <strong>{shown.toFixed(1)}×</strong> <span>{Math.round(shown * base)} rps</span>
        </output>
        <button
          type="button"
          className="btn"
          disabled={busy || value === null || value === current}
          onClick={async () => {
            setBusy(true);
            setError(null);
            try {
              await api.load(shown);
              setValue(null);
              await refresh();
            } catch (e) {
              setError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          Apply
        </button>
      </div>
      <p className="small muted">
        Now {current.toFixed(1)}×, about {Math.round(current * base)} rps.
      </p>
      <ErrorNote error={error} />
    </section>
  );
}

function RampRunning({ view, onDone }: { view: ActiveView; onDone: () => void }) {
  const plan = view.plan ?? recall<RampPlan>(PLAN_KEY, view.n);
  const elapsed = useSecondsSince(view.started_at);
  const [resources, setResources] = useState<Option[]>([]);
  const [knee, setKnee] = useState("");
  const [max, setMax] = useState("");
  const [resource, setResource] = useState("");
  const [result, setResult] = useState<RampResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api.rampOptions().then((r) => setResources(rampResources(r)), setError);
  }, []);

  const steps = plan?.steps ?? lab.defaults.ramp_rates;
  const step = plan ? Math.min(Math.floor(elapsed / plan.every), steps.length) : null;
  const finished = plan ? elapsed >= plan.total_seconds : false;

  if (result) {
    return (
      <div className="ramp-result">
        <p className="score">
          <span className="score-value">{result.score}</span>
          <span className="score-max">/ {result.max}</span>
        </p>
        <p className="muted">
          Measured: knee about {result.measured.knee_rps} rps, top throughput {result.measured.max_rps} rps, limit: {result.measured.resource}.
        </p>
        <table className="data-table">
          <thead>
            <tr>
              <th scope="col">Offered rps</th>
              <th scope="col">Achieved rps</th>
              <th scope="col">p99</th>
              <th scope="col">Errors</th>
            </tr>
          </thead>
          <tbody>
            {result.table.map((r) => (
              <tr key={r.rps_offered}>
                <td className="num">{r.rps_offered}</td>
                <td className="num">{r.rps_achieved.toFixed(1)}</td>
                <td className="num">{(r.p99 * 1000).toFixed(0)} ms</td>
                <td className="num">{(r.error_share * 100).toFixed(1)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="explanation">{result.explanation}</p>
        <button type="button" className="btn btn-primary" onClick={onDone}>
          Clear and go back
        </button>
      </div>
    );
  }

  return (
    <div>
      <ol className="ramp-steps" aria-label="Ramp steps">
        {steps.map((rps, i) => (
          <li key={i} className={step === null ? "" : i < step ? "is-done" : i === step ? "is-now" : ""} aria-current={i === step ? "step" : undefined}>
            <span className="ramp-bar" style={{ height: `${(rps / steps[steps.length - 1]) * 100}%` }} />
            <span className="ramp-rps">{rps}</span>
          </li>
        ))}
      </ol>
      <p className="small muted">
        {plan
          ? finished
            ? "The ramp is over. Read the charts, then answer."
            : (
              <>
                Step {Math.min((step ?? 0) + 1, steps.length)} of {steps.length}, next in <Countdown seconds={plan.every - (elapsed % plan.every)} />. You can answer at any time. The score reads what Prometheus recorded.
              </>
            )
          : "Started before this page opened, so the step timer is unknown. You can answer at any time."}
      </p>
      <form
        className="ramp-form"
        onSubmit={async (e) => {
          e.preventDefault();
          const k = parseNumber(knee);
          const m = parseNumber(max);
          if (k === null || m === null || !resource) {
            setError(new Error("Give two numbers in rps and pick a resource."));
            return;
          }
          setBusy(true);
          setError(null);
          try {
            setResult(await api.rampAnswer({ knee_rps: k, max_rps: m, resource }));
          } catch (err) {
            setError(err);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label className="field">
          <span>Knee (rps)</span>
          <input inputMode="decimal" value={knee} onChange={(e) => setKnee(e.target.value)} />
        </label>
        <label className="field">
          <span>Highest throughput (rps)</span>
          <input inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} />
        </label>
        <label className="field">
          <span>Resource that filled first</span>
          <select value={resource} onChange={(e) => setResource(e.target.value)}>
            <option value="">Pick one</option>
            {resources.map((r) => (
              <option key={r.key} value={r.key}>
                {r.label}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "Reading Prometheus..." : "Submit"}
        </button>
      </form>
      <ErrorNote error={error} />
    </div>
  );
}

function BenchRunning({ view, onDone }: { view: ActiveView; onDone: () => void }) {
  const endsAt = view.ends_at ?? recall<number>(BENCH_KEY, view.n);
  const [report, setReport] = useState<BenchReport | null>(null);
  const [left, setLeft] = useState<number | null>(() => (endsAt ? Math.max(0, endsAt - Date.now() / 1000) : null));
  const [error, setError] = useState<unknown>(null);

  const poll = useCallback(async () => {
    try {
      setReport(await api.benchReport());
    } catch (e) {
      if (e instanceof ApiError && e.code === "still_running") setLeft(Math.max(1, Number(e.body.seconds_left ?? 0)));
      else setError(e);
    }
  }, []);

  useEffect(() => {
    if (report) return;
    const id = window.setInterval(() => setLeft((l) => (l === null ? l : Math.max(0, l - 1))), 1000);
    return () => window.clearInterval(id);
  }, [report]);

  useEffect(() => {
    if (report) return;
    if (left === null || left <= 0) {
      const id = window.setTimeout(poll, 0);
      return () => window.clearTimeout(id);
    }
  }, [left, report, poll]);

  if (!report) {
    return (
      <div>
        <p className="bench-count">
          {left !== null && left > 0 ? (
            <>
              Closed-loop clients running. Report in <Countdown seconds={left} />.
            </>
          ) : (
            "Reading the report..."
          )}
        </p>
        <ErrorNote error={error} />
      </div>
    );
  }
  const keys = [...new Set([...Object.keys(report.before), ...Object.keys(report.during)])];
  const cell = (v: number | string | null | undefined) => (typeof v === "number" ? (Number.isInteger(v) ? v : v.toFixed(3)) : (v ?? "-"));
  return (
    <div>
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col" />
            <th scope="col">The minutes before</th>
            <th scope="col">During the bench</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((k) => (
            <tr key={k}>
              <th scope="row">{k.replace(/_/g, " ")}</th>
              <td className="num">{cell(report.before[k])}</td>
              <td className="num">{cell(report.during[k])}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="explanation">{report.explanation}</p>
      <button type="button" className="btn btn-primary" onClick={onDone}>
        Clear and go back
      </button>
    </div>
  );
}

function StartCapacity({ onStarted }: { onStarted: (v: ActiveView) => void }) {
  const l = lab.limits;
  const [every, setEvery] = useState(lab.defaults.ramp_step_seconds);
  const [clients, setClients] = useState(lab.defaults.bench_clients);
  const [seconds, setSeconds] = useState(lab.defaults.bench_seconds);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const run = async (fn: () => Promise<ActiveView>, save: (v: ActiveView) => void) => {
    setBusy(true);
    setError(null);
    try {
      const v = await fn();
      save(v);
      onStarted(v);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const rates = lab.defaults.ramp_rates;
  return (
    <div className="capacity-start">
      <section className="panel" aria-labelledby="ramp-title">
        <h2 id="ramp-title" className="panel-title">
          Ramp
        </h2>
        <p className="small">
          The load climbs from {rates[0]} to {rates[rates.length - 1]} rps in {rates.length} steps while something may limit the lab. Find the knee, the top throughput and the resource that filled first.
        </p>
        <label className="field">
          <span>Seconds per step</span>
          <input type="number" min={l.ramp_step_seconds.min} max={l.ramp_step_seconds.max} value={every} onChange={(e) => setEvery(Number(e.target.value))} />
        </label>
        <p className="small muted">
          About {Math.round((every * rates.length) / 60)} minutes. Same as <code>make ramp STEP={every}</code>.
        </p>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => run(() => api.ramp(every), (v) => v.plan && remember(PLAN_KEY, v.n, v.plan))}>
          Start the ramp
        </button>
      </section>
      <section className="panel" aria-labelledby="bench-title">
        <h2 id="bench-title" className="panel-title">
          Bench
        </h2>
        <p className="small">Closed-loop clients with no think time, then the minutes before against the bench. Not scored.</p>
        <div className="field-row">
          <label className="field">
            <span>Clients</span>
            <input type="number" min={l.bench_clients.min} max={l.bench_clients.max} value={clients} onChange={(e) => setClients(Number(e.target.value))} />
          </label>
          <label className="field">
            <span>Seconds</span>
            <input type="number" min={l.bench_seconds.min} max={l.bench_seconds.max} value={seconds} onChange={(e) => setSeconds(Number(e.target.value))} />
          </label>
        </div>
        <p className="small muted">
          Same as <code>make bench CLIENTS={clients} SECONDS={seconds}</code>.
        </p>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => run(() => api.bench({ clients, seconds }), (v) => v.ends_at && remember(BENCH_KEY, v.n, v.ends_at))}>
          Start the bench
        </button>
      </section>
      <ErrorNote error={error} />
    </div>
  );
}

export function CapacityTab() {
  const { refresh } = useLab();
  const [view, setView] = useState<ActiveView | null | undefined>(undefined);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let live = true;
    api.exercise().then(
      (v) => live && setView(v),
      (e) => {
        if (!live) return;
        if (!(e instanceof ApiError && e.code === "no_exercise")) setError(e);
        setView(null);
      },
    );
    return () => {
      live = false;
    };
  }, []);

  const done = async () => {
    try {
      await api.clear();
    } catch (e) {
      setError(e);
    }
    setView(null);
    await refresh();
  };

  return (
    <div className="capacity">
      <LoadControl />
      {view === undefined ? (
        <p className="lab-wait">Reading the lab...</p>
      ) : view && (view.mode === "incident" || view.mode === "toolbox") && !view.answered ? (
        <p className="callout">
          An exercise is open. Finish or clear it on the <Link to="/play">Exercise</Link> tab before a ramp or a bench.
        </p>
      ) : view && view.mode === "ramp" ? (
        <section className="panel" aria-labelledby="ramp-run-title">
          <h2 id="ramp-run-title" className="panel-title">
            Ramp running
          </h2>
          <RampRunning view={view} onDone={done} />
        </section>
      ) : view && view.mode === "bench" ? (
        <section className="panel" aria-labelledby="bench-run-title">
          <h2 id="bench-run-title" className="panel-title">
            Bench running
          </h2>
          <BenchRunning view={view} onDone={done} />
        </section>
      ) : (
        <StartCapacity
          onStarted={(v) => {
            setView(v);
            void refresh();
          }}
        />
      )}
      <ErrorNote error={error} />
    </div>
  );
}
