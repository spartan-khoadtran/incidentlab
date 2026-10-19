import { useEffect, useRef, useState } from "react";
import { ErrorNote, GuideLinks } from "../../components/LabUi";
import { api, parseNumber, type QuizQuestion, type QuizVerdict } from "../../lib/api";
import { lab } from "../../lib/data";

type Kind = "all" | "numbers" | "concepts";

interface Done {
  question: QuizQuestion;
  given: string;
  verdict: QuizVerdict;
}

const COUNT_MAX = 300;

function Setup({ onStart, busy }: { onStart: (count: number, kind: Kind) => void; busy: boolean }) {
  const [count, setCount] = useState(lab.defaults.quiz_questions);
  const [kind, setKind] = useState<Kind>("all");
  return (
    <form
      className="quiz-setup"
      onSubmit={(e) => {
        e.preventDefault();
        onStart(Math.min(Math.max(1, count), COUNT_MAX), kind);
      }}
    >
      <label className="field">
        <span>Questions</span>
        <input type="number" min={1} max={COUNT_MAX} value={count} onChange={(e) => setCount(Number(e.target.value))} />
      </label>
      <fieldset className="seg">
        <legend>Kind</legend>
        {(["all", "numbers", "concepts"] as const).map((k) => (
          <button key={k} type="button" aria-pressed={kind === k} onClick={() => setKind(k)}>
            {k === "all" ? "Both" : k === "numbers" ? `Numbers (${lab.counts.numbers})` : `Concepts (${lab.counts.concepts})`}
          </button>
        ))}
      </fieldset>
      <button type="submit" className="btn btn-primary" disabled={busy}>
        {busy ? "Drawing questions..." : "Start the quiz"}
      </button>
    </form>
  );
}

function QuestionCard({
  question,
  position,
  total,
  score,
  onAnswer,
  onSkip,
}: {
  question: QuizQuestion;
  position: number;
  total: number;
  score: number;
  onAnswer: (answer: number | string, shown: string) => Promise<void>;
  onSkip: () => Promise<void>;
}) {
  const [raw, setRaw] = useState("");
  const [choice, setChoice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const form = useRef<HTMLFormElement>(null);

  useEffect(() => {
    (input.current ?? form.current?.querySelector<HTMLInputElement>("input[type=radio]"))?.focus();
  }, []);

  const send = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    let answer: number | string;
    let shown: string;
    if (question.kind === "number") {
      const n = parseNumber(raw);
      if (n === null) {
        setError("Type a number. A comma or a dot both work as the decimal sign.");
        return;
      }
      answer = n;
      shown = `${raw.trim()}${question.unit ? ` ${question.unit}` : ""}`;
    } else {
      if (!choice) {
        setError("Pick one option.");
        return;
      }
      answer = choice;
      shown = choice;
    }
    await send(() => onAnswer(answer, shown));
  };

  return (
    <form
      ref={form}
      className="quiz-card"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
      onKeyDown={(e) => {
        if (question.kind !== "choice" || !question.options) return;
        const n = Number(e.key);
        if (n >= 1 && n <= question.options.length && !(e.target as HTMLElement).matches("input[type=text], input[inputmode]")) setChoice(question.options[n - 1]);
      }}
    >
      <p className="quiz-progress">
        Question {position} of {total} · {score} right so far
        <span className="quiz-meter" aria-hidden="true">
          <span style={{ width: `${((position - 1) / total) * 100}%` }} />
        </span>
      </p>
      <p className="quiz-text">{question.text}</p>
      {question.kind === "number" ? (
        <label className="field quiz-number">
          <span className="visually-hidden">Your answer</span>
          <input ref={input} inputMode="decimal" autoComplete="off" value={raw} onChange={(e) => setRaw(e.target.value)} aria-label="Your answer" aria-describedby="quiz-unit" />
          {question.unit ? <span id="quiz-unit">{question.unit}</span> : null}
        </label>
      ) : (
        <fieldset className="quiz-options">
          <legend className="visually-hidden">Options</legend>
          {question.options?.map((o, i) => (
            <label key={o} className={`choice ${choice === o ? "is-checked" : ""}`}>
              <input type="radio" name={`q-${question.index}`} value={o} checked={choice === o} onChange={() => setChoice(o)} />
              <span className="choice-key" aria-hidden="true">
                {i + 1}
              </span>
              <span>{o}</span>
            </label>
          ))}
        </fieldset>
      )}
      <div className="quiz-actions">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          Check
        </button>
        <button type="button" className="btn" disabled={busy} onClick={() => void send(onSkip)}>
          Skip
        </button>
        {question.kind === "choice" ? <span className="muted small">Keys 1 to {question.options?.length} pick an option, Enter checks.</span> : null}
      </div>
      {error ? (
        <p className="error-note" role="alert">
          {error}
        </p>
      ) : null}
    </form>
  );
}

function VerdictCard({ done, last, onNext }: { done: Done; last: boolean; onNext: () => void }) {
  const next = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    next.current?.focus();
  }, []);
  return (
    <div className={`quiz-verdict ${done.verdict.correct ? "is-right" : done.verdict.skipped ? "is-skipped" : "is-wrong"}`} role="status">
      <p className="quiz-verdict-title">{done.verdict.correct ? "Right." : done.verdict.skipped ? "Skipped." : "Not quite."}</p>
      <p className="quiz-text">{done.question.text}</p>
      <dl className="kv">
        <div>
          <dt>You said</dt>
          <dd>{done.given}</dd>
        </div>
        <div>
          <dt>Answer</dt>
          <dd>{done.verdict.answer}</dd>
        </div>
      </dl>
      <p className="explanation">{done.verdict.explanation}</p>
      <GuideLinks refs={done.verdict.guide} />
      <button ref={next} type="button" className="btn btn-primary" onClick={onNext}>
        {last ? "See the summary" : "Next question"}
      </button>
    </div>
  );
}

export function QuizRunner() {
  const [session, setSession] = useState<{ id: string; questions: QuizQuestion[] } | null>(null);
  const [done, setDone] = useState<Done[]>([]);
  const [showing, setShowing] = useState<Done | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const start = async (count: number, kind: Kind) => {
    setBusy(true);
    setError(null);
    try {
      const s = await api.quiz({ count, kind });
      setSession({ id: s.session, questions: s.questions });
      setDone([]);
      setShowing(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!session) {
    return (
      <>
        <Setup onStart={start} busy={busy} />
        <ErrorNote error={error} />
      </>
    );
  }

  const total = session.questions.length;
  const score = done.filter((d) => d.verdict.correct).length;
  const skipped = done.filter((d) => d.verdict.skipped).length;

  if (showing) {
    return (
      <VerdictCard
        key={showing.question.index}
        done={showing}
        last={done.length === total}
        onNext={() => setShowing(null)}
      />
    );
  }

  if (done.length === total) {
    const missed = done.filter((d) => !d.verdict.correct);
    return (
      <div className="quiz-summary">
        <p className="score">
          <span className="score-value">{score}</span>
          <span className="score-max">/ {total} right</span>
        </p>
        {skipped ? <p className="muted">{skipped} skipped</p> : null}
        {missed.length ? (
          <>
            <h3 className="panel-title">Read these again</h3>
            <ol className="quiz-missed">
              {missed.map((d) => (
                <li key={d.question.index}>
                  <p>{d.question.text}</p>
                  <p className="muted small">Answer: {d.verdict.answer}</p>
                  <GuideLinks refs={d.verdict.guide} />
                </li>
              ))}
            </ol>
          </>
        ) : (
          <p>Every answer right.</p>
        )}
        <button type="button" className="btn btn-primary" onClick={() => setSession(null)}>
          Start again
        </button>
      </div>
    );
  }

  const question = session.questions[done.length];
  const record = async (grade: () => Promise<QuizVerdict>, given: string) => {
    setError(null);
    try {
      const entry = { question, given, verdict: await grade() };
      setDone((d) => [...d, entry]);
      setShowing(entry);
    } catch (e) {
      setError(e);
    }
  };
  return (
    <>
      <QuestionCard
        key={question.index}
        question={question}
        position={done.length + 1}
        total={total}
        score={score}
        onAnswer={(answer, given) => record(() => api.quizAnswer(session.id, question.index, answer), given)}
        onSkip={() => record(() => api.quizSkip(session.id, question.index), "Skipped")}
      />
      <ErrorNote error={error} />
    </>
  );
}
