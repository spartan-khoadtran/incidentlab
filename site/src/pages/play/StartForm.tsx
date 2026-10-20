import { useCallback, useEffect, useRef, useState } from "react";
import { Confirm, ErrorNote } from "../../components/LabUi";
import { api, ApiError, type ActiveView, type NewExercise, type Options } from "../../lib/api";
import { lab } from "../../lib/data";
import { focusIfLost, useSettledStart } from "../../lib/settle";
import { SettleCard } from "../../components/SettleCard";

export function StartForm({
  options,
  openMode,
  onStarted,
  onWaiting,
}: {
  options: Options | null;
  openMode: string | null;
  onStarted: (view: ActiveView) => void;
  onWaiting?: () => void;
}) {
  const [mode, setMode] = useState<"incident" | "toolbox">("incident");
  const [level, setLevel] = useState("");
  const [topic, setTopic] = useState("");
  const [id, setId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [confirm, setConfirm] = useState(false);

  const levels = options?.levels ?? lab.levels;
  const topics = (options?.topics ?? lab.topics).filter((t) => lab.scenarios.some((s) => s.topics.includes(t)));
  const ids = lab.scenarios.filter((s) => (mode === "toolbox" ? s.toolbox : true)).map((s) => s.id);

  const failed = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.code === "exercise_open") setConfirm(true);
    else setError(e);
  }, []);
  const { run, waiting, isWaiting, posting, cancel } = useSettledStart(onStarted, failed, onWaiting);
  const submit = useRef<HTMLButtonElement>(null);
  const wasWaiting = useRef(false);

  useEffect(() => {
    if (isWaiting) wasWaiting.current = true;
    else if (wasWaiting.current && !busy) {
      wasWaiting.current = false;
      focusIfLost(submit.current);
    }
  }, [isWaiting, busy]);

  const start = async (force: boolean) => {
    setBusy(true);
    setError(null);
    const body: NewExercise = { mode };
    if (level) body.level = Number(level);
    if (topic) body.topic = topic;
    if (id.trim()) body.id = id.trim();
    try {
      await run((retry) => api.start(force && !retry ? { ...body, force: true } : body));
    } finally {
      setBusy(false);
    }
  };

  const command = ["make", mode === "toolbox" ? "toolbox" : "new", level && `LEVEL=${level}`, topic && `TOPIC=${topic}`, id.trim() && `ID=${id.trim()}`].filter(Boolean).join(" ");

  if (isWaiting) return <SettleCard status={waiting} starting={posting} level={3} onCancel={cancel} />;

  return (
    <form
      className="start-form"
      onSubmit={(e) => {
        e.preventDefault();
        void start(false);
      }}
    >
      <fieldset className="seg">
        <legend>Mode</legend>
        <button type="button" aria-pressed={mode === "incident"} onClick={() => setMode("incident")}>
          Incident
        </button>
        <button type="button" aria-pressed={mode === "toolbox"} onClick={() => setMode("toolbox")}>
          Toolbox, shell only
        </button>
      </fieldset>
      <div className="start-grid">
        <label className="field">
          <span>Level</span>
          <select value={level} onChange={(e) => setLevel(e.target.value)}>
            <option value="">Any</option>
            {levels.map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Topic</span>
          <select value={topic} onChange={(e) => setTopic(e.target.value)}>
            <option value="">Any</option>
            {topics.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Scenario id (optional)</span>
          <input list="scenario-ids" value={id} onChange={(e) => setId(e.target.value)} placeholder="random" autoComplete="off" spellCheck={false} />
          <datalist id="scenario-ids">
            {ids.map((x) => (
              <option key={x} value={x} />
            ))}
          </datalist>
        </label>
      </div>
      <p className="start-hint">
        Same as <code>{command}</code>. With no id the lab picks one you did not play in the last {lab.recent_window} rounds and does not say which.
      </p>
      <div className="start-actions">
        <button ref={submit} type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "Starting..." : openMode ? "Start a new exercise" : "Start the exercise"}
        </button>
      </div>
      <ErrorNote error={error} />
      <Confirm
        open={confirm}
        title="An exercise is still open"
        confirmLabel="Drop it and start"
        danger
        onCancel={() => setConfirm(false)}
        onConfirm={() => {
          setConfirm(false);
          void start(true);
        }}
      >
        <p>The open exercise ends without a score and its fault stops. A new one starts in its place.</p>
      </Confirm>
    </form>
  );
}
