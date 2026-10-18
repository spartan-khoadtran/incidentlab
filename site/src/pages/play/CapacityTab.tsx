import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router";
import { Confirm, Countdown, ErrorNote, GuideLinks, useSecondsSince } from "../../components/LabUi";
import { api, ApiError, parseNumber, type ActiveView, type BenchReport, type BenchRow, type Option, type RampResult } from "../../lib/api";
import { lab } from "../../lib/data";
import { useLab } from "../../lib/lab";

const ms = (s: number | null) => (s === null ? "-" : `${Math.round(s * 1000)} ms`);
const pct = (s: number | null) => (s === null ? "-" : `${(s * 100).toFixed(1)}%`);
const rps = (s: number | null) => (s === null ? "-" : s.toFixed(1));
const isRampResult = (r: unknown): r is RampResult => typeof r === "object" && r !== null && "table" in r;

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

function RampResultView({ result, onDone }: { result: RampResult; onDone: () => void }) {
  const m = result.measured;
  return (
    <div className="ramp-result">
      {result.score === null ? (
        <p className="score score-none">
          <span className="score-value">-</span>
          <span className="score-max">revealed, not scored</span>
        </p>
      ) : (
        <p className="score" aria-label={`Score ${result.score} out of ${result.max}`}>
          <span className="score-value">{result.score}</span>
          <span className="score-max">/ {result.max}</span>
        </p>
      )}
      {result.prometheus_down ? <p className="error-note">Prometheus did not answer, so the table is empty.</p> : null}
      <dl className="kv">
        <div>
          <dt>Knee</dt>
          <dd>
            {m.knee_rps ? `${m.knee_rps} rps` : "no knee"}
            {result.given ? ` · you said ${result.given.knee_rps || "none"}${result.verdicts ? (result.verdicts.knee ? " (right)" : " (missed)") : ""}` : ""}
          </dd>
        </div>
        <div>
          <dt>Top throughput</dt>
          <dd>
            {m.max_rps === null ? "-" : `${m.max_rps.toFixed(1)} rps`}
            {result.given ? ` · you said ${result.given.max_rps}${result.verdicts ? (result.verdicts.max ? " (right)" : " (missed)") : ""}` : ""}
          </dd>
        </div>
        <div>
          <dt>Filled first</dt>
          <dd>
            {m.resource_label}
            {result.verdicts ? (result.verdicts.resource ? " (right)" : " (missed)") : ""}
          </dd>
        </div>
      </dl>
      <div className="table-scroll">
        <table className="data-table">
          <thead>
            <tr>
              <th scope="col">Step</th>
              <th scope="col" className="num">
                Target
              </th>
              <th scope="col" className="num">
                Offered
              </th>
              <th scope="col" className="num">
                Achieved
              </th>
              <th scope="col" className="num">
                p99
              </th>
              <th scope="col" className="num">
                Errors
              </th>
            </tr>
          </thead>
          <tbody>
            {result.table.map((r) => (
              <tr key={r.step} className={r.knee ? "is-knee" : ""}>
                <td>
                  {r.step}
                  {r.knee ? <span className="tag tag-live"> knee</span> : null}
                </td>
                <td className="num">{r.target_rps}</td>
                <td className="num">{rps(r.rps_offered)}</td>
                <td className="num">{rps(r.rps_achieved)}</td>
                <td className="num">{ms(r.p99)}</td>
                <td className="num">{pct(r.error_share)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="explanation">{result.explanation}</p>
      <GuideLinks refs={result.guide} />
      <button type="button" className="btn btn-primary" onClick={onDone}>
        Clear and go back
      </button>
    </div>
  );
}

function RampRunning({ view, onDone }: { view: ActiveView; onDone: () => void }) {
  const plan = view.plan ?? null;
  const elapsed = useSecondsSince(view.started_at);
  const [resources, setResources] = useState<Option[]>([]);
  const [knee, setKnee] = useState("");
  const [max, setMax] = useState("");
  const [resource, setResource] = useState("");
  const [result, setResult] = useState<RampResult | null>(isRampResult(view.result) ? view.result : null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);

  useEffect(() => {
    let live = true;
    api.rampOptions().then(
      (r) => live && setResources(r.resources),
      (e) => live && setError(e),
    );
    return () => {
      live = false;
    };
  }, []);

  if (result) return <RampResultView result={result} onDone={onDone} />;

  const steps = plan?.steps ?? lab.defaults.ramp_rates;
  const step = plan ? Math.min(Math.floor(elapsed / plan.every), steps.length) : null;
  const finished = plan ? elapsed >= plan.total_seconds : false;

  const run = async (fn: () => Promise<RampResult>) => {
    setBusy(true);
    setError(null);
    try {
      setResult(await fn());
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <ol className="ramp-steps" aria-label="Ramp steps">
        {steps.map((r, i) => (
          <li key={i} className={step === null ? "" : i < step ? "is-done" : i === step ? "is-now" : ""} aria-current={i === step ? "step" : undefined}>
            <span className="ramp-bar" style={{ height: `${(r / steps[steps.length - 1]) * 100}%` }} />
            <span className="ramp-rps">{r}</span>
          </li>
        ))}
      </ol>
      <p className="small muted">
        {!plan ? (
          "The step timer is unknown."
        ) : finished ? (
          "The ramp is over. Read the charts, then answer."
        ) : (
          <>
            Step {Math.min((step ?? 0) + 1, steps.length)} of {steps.length}, next in <Countdown seconds={plan.every - (elapsed % plan.every)} />. You can answer after the first step. The score reads what Prometheus recorded.
          </>
        )}
      </p>
      <form
        className="ramp-form"
        onSubmit={(e) => {
          e.preventDefault();
          const k = parseNumber(knee);
          const m = parseNumber(max);
          if (k === null || m === null || !resource) {
            setError(new Error("Give two numbers in rps (0 for no knee) and pick a resource."));
            return;
          }
          void run(() => api.rampAnswer({ knee_rps: k, max_rps: m, resource }));
        }}
      >
        <label className="field">
          <span>Knee (rps, 0 for none)</span>
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
      <button type="button" className="btn btn-quiet" onClick={() => setConfirm(true)}>
        Show the result without a score
      </button>
      <ErrorNote error={error} />
      <Confirm
        open={confirm}
        title="Show the ramp result?"
        confirmLabel="Show it, no score"
        danger
        onCancel={() => setConfirm(false)}
        onConfirm={() => {
          setConfirm(false);
          void run(() => api.rampReveal());
        }}
      >
        <p>You see the knee, the top throughput and the resource without a score. The ramp keeps running until you clear it.</p>
      </Confirm>
    </div>
  );
}

function BenchTable({ report }: { report: BenchReport }) {
  const rows: { label: string; get: (w: BenchReport["before"]) => string }[] = [];
  const side = (name: "client" | "api", title: string) => {
    const pick = (f: (r: BenchRow) => string) => (w: BenchReport["before"]) => f(w[name]);
    rows.push(
      { label: `${title}: rate`, get: pick((r) => (r.rate_rps === null ? "-" : `${r.rate_rps.toFixed(1)} rps`)) },
      { label: `${title}: p50`, get: pick((r) => ms(r.p50)) },
      { label: `${title}: p99`, get: pick((r) => ms(r.p99)) },
      { label: `${title}: errors`, get: pick((r) => pct(r.error_share)) },
    );
  };
  side("client", "Clients");
  side("api", "api");
  rows.push({ label: "Clients: mean response", get: (w) => ms(w.client_mean) }, { label: "api: requests in flight", get: (w) => (w.api_in_flight === null ? "-" : w.api_in_flight.toFixed(1)) });
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col" />
            <th scope="col" className="num">
              The 2 minutes before
            </th>
            <th scope="col" className="num">
              During the bench
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <th scope="row">{r.label}</th>
              <td className="num">{r.get(report.before)}</td>
              <td className="num">{r.get(report.during)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BenchRunning({ view, onDone }: { view: ActiveView; onDone: () => void }) {
  const [report, setReport] = useState<BenchReport | null>(null);
  const [left, setLeft] = useState<number | null>(() => (view.ends_at ? Math.max(0, view.ends_at - Date.now() / 1000) : null));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

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
    if (report || (left !== null && left > 0)) return;
    const id = window.setTimeout(poll, 0);
    return () => window.clearTimeout(id);
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
  return (
    <div className="ramp-result">
      <p className="muted">
        {report.clients} closed-loop clients for {report.seconds} s.
      </p>
      <BenchTable report={report} />
      <p className="explanation">{report.explanation}</p>
      <GuideLinks refs={report.guide} />
      <button
        type="button"
        className="btn btn-primary"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          try {
            await api.benchClose();
          } catch (e) {
            setError(e);
          }
          setBusy(false);
          onDone();
        }}
      >
        Save to history and go back
      </button>
      <ErrorNote error={error} />
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
  const run = async (fn: () => Promise<ActiveView>) => {
    setBusy(true);
    setError(null);
    try {
      onStarted(await fn());
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
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => run(() => api.ramp(every))}>
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
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => run(() => api.bench({ clients, seconds }))}>
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
      if (!(e instanceof ApiError && e.code === "no_exercise")) setError(e);
    }
    setView(null);
    await refresh();
  };

  return (
    <div className="capacity">
      <LoadControl />
      {view === undefined ? (
        <p className="lab-wait">Reading the lab...</p>
      ) : view && (view.mode === "incident" || view.mode === "toolbox") ? (
        <p className="callout">
          An exercise is open. Finish or clear it on the <Link to="/play">Exercise</Link> tab before a ramp or a bench.
        </p>
      ) : view && view.mode === "ramp" ? (
        <section className="panel" aria-labelledby="ramp-run-title">
          <h2 id="ramp-run-title" className="panel-title">
            Ramp
          </h2>
          <RampRunning view={view} onDone={done} />
        </section>
      ) : view && view.mode === "bench" ? (
        <section className="panel" aria-labelledby="bench-run-title">
          <h2 id="bench-run-title" className="panel-title">
            Bench
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
