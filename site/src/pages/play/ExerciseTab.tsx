import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { Confirm, ErrorNote } from "../../components/LabUi";
import { api, ApiError, type ActiveView, type Options, type Result, type Reveal } from "../../lib/api";
import { exerciseRange } from "../../lib/grafana";
import { useLab } from "../../lib/lab";
import { AnswerForm, type Choices } from "./AnswerForm";
import { ExplorePanel, Hints, TicketCard } from "./OpenExercise";
import { ResultView } from "./ResultView";
import { StartForm } from "./StartForm";

/**
 * The exercise loop. Before the answer or the reveal it only ever holds the ActiveView the API
 * sends, so nothing about the scenario can reach the page early.
 */
export function ExerciseTab() {
  const { status, refresh } = useLab();
  const [view, setView] = useState<ActiveView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [options, setOptions] = useState<Options | null>(null);
  const [reveal, setReveal] = useState<Reveal | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [choices, setChoices] = useState<Choices | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [hintError, setHintError] = useState<unknown>(null);
  const [confirmReveal, setConfirmReveal] = useState(false);
  const [starting, setStarting] = useState(false);
  const shown = useRef<string | null>(null);
  const synced = useRef<string | null>(null);

  const load = useCallback(async () => {
    try {
      const v = await api.exercise();
      if (shown.current !== v.key) {
        setChoices(null);
        setStarting(false);
      }
      shown.current = v.key;
      setView(v);
      setReveal(v.reveal ?? null);
      setResult(v.result && "verdicts" in v.result && v.result.verdicts && "nature" in v.result.verdicts ? (v.result as Result) : null);
      setError(null);
      setHintError(null);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === "no_exercise")) setError(e);
      shown.current = null;
      setView(null);
      setReveal(null);
      setResult(null);
      setChoices(null);
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    let live = true;
    const id = window.setTimeout(load, 0);
    api.options().then(
      (o) => live && setOptions(o),
      (e) => live && setError(e),
    );
    return () => {
      live = false;
      window.clearTimeout(id);
    };
  }, [load]);

  const active = status?.active;
  const statusKey = status ? (active ? `${active.key}:${active.answered}` : "none") : null;
  const viewKey = view ? `${view.key}:${view.answered}` : "none";

  useEffect(() => {
    if (statusKey === null || !loaded || busy !== null || statusKey === synced.current) return;
    synced.current = statusKey;
    if (statusKey === viewKey) return;
    const id = window.setTimeout(load, 0);
    return () => window.clearTimeout(id);
  }, [statusKey, viewKey, loaded, busy, load]);

  const act = async (name: string, fn: () => Promise<void>, onError: (e: unknown) => void = setError) => {
    setBusy(name);
    setError(null);
    setHintError(null);
    try {
      await fn();
    } catch (e) {
      if (e instanceof ApiError && (e.code === "no_exercise" || e.code === "already_answered" || e.code === "exercise_changed")) await load();
      onError(e);
    } finally {
      setBusy(null);
    }
  };

  const reset = () => {
    shown.current = null;
    setView(null);
    setReveal(null);
    setResult(null);
    setChoices(null);
  };

  if (!loaded) return <p className="lab-wait">Reading the open exercise...</p>;

  if (error instanceof ApiError && error.code === "stale_exercise") {
    return (
      <div className="callout">
        <p>{error.message}</p>
        <button
          type="button"
          className="btn btn-primary"
          onClick={() =>
            act("clear", async () => {
              await api.clear();
              reset();
              await refresh();
            })
          }
        >
          Clear it
        </button>
      </div>
    );
  }

  if (view && (view.mode === "ramp" || view.mode === "bench")) {
    return (
      <p className="callout">
        A {view.mode} is running. Follow it on the <Link to="/play?tab=capacity">Load and capacity</Link> tab.
      </p>
    );
  }

  if (!view || starting) {
    return (
      <section aria-labelledby="start-title">
        <h2 id="start-title" className="panel-title">
          Start an exercise
        </h2>
        <StartForm
          options={options}
          openMode={view?.mode ?? null}
          onStarted={(v) => {
            reset();
            setStarting(false);
            shown.current = v.key;
            setView(v);
            void refresh();
          }}
        />
        {starting ? (
          <button type="button" className="btn btn-quiet" onClick={() => setStarting(false)}>
            Back to the result
          </button>
        ) : null}
        <ErrorNote error={error} />
      </section>
    );
  }

  const toolbox = view.mode === "toolbox";
  const range = exerciseRange(view.started_at);

  if (reveal) {
    return (
      <div className="play-result">
        <ResultView reveal={reveal} result={result} choices={choices} options={options} toolbox={toolbox} grafanaUrl={status?.links.grafana ?? null} range={range} />
        <div className="result-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy !== null}
            onClick={() =>
              act("clear", async () => {
                await api.clear(view.key);
                reset();
                await refresh();
              })
            }
          >
            Clear the fault
          </button>
          <button type="button" className="btn" onClick={() => setStarting(true)}>
            New exercise
          </button>
          <span className="muted small">The fault keeps running until you clear it, so you can check the evidence on the live charts first.</span>
        </div>
        <ErrorNote error={error} />
      </div>
    );
  }

  return (
    <div className="play-open">
      <div className="play-main">
        <TicketCard view={view} />
        {options ? (
          <AnswerForm
            options={options}
            busy={busy === "answer"}
            error={busy === null ? error : null}
            onSubmit={(c, note) =>
              act("answer", async () => {
                const r = await api.answer(view.key, { ...c, ...(note ? { note } : {}) });
                setChoices(c);
                setResult(r);
                setReveal(r.reveal);
                setView({ ...view, answered: true });
                await refresh();
              })
            }
          />
        ) : (
          <p className="lab-wait">Loading the answer options...</p>
        )}
      </div>
      <aside className="play-side">
        <Hints view={view} busy={busy === "hint"} error={hintError} onHint={() => act("hint", async () => setView(await api.hint(view.key)), setHintError)} />
        <ExplorePanel view={view} status={status} />
        <section className="give-up">
          <button type="button" className="btn btn-quiet" onClick={() => setConfirmReveal(true)}>
            Show the answer without a score
          </button>
        </section>
      </aside>
      <Confirm
        open={confirmReveal}
        title="Show the answer?"
        confirmLabel="Show it, no score"
        danger
        onCancel={() => setConfirmReveal(false)}
        onConfirm={() => {
          setConfirmReveal(false);
          void act("reveal", async () => {
            setReveal(await api.reveal(view.key));
            setView({ ...view, answered: true });
            await refresh();
          });
        }}
      >
        <p>Your history keeps this exercise with no score, like <code>make reveal</code>. The fault keeps running until you clear it.</p>
      </Confirm>
    </div>
  );
}
