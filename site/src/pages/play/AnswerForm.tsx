import { useMemo, useState } from "react";
import { ErrorNote } from "../../components/LabUi";
import type { Answer, Option, Options } from "../../lib/api";

export type Choices = Omit<Answer, "note">;

const QUESTIONS: { key: keyof Choices; title: string; ask: string; list: (o: Options) => Option[] }[] = [
  { key: "nature", title: "Nature", ask: "What kind of problem is it?", list: (o) => o.natures },
  { key: "location", title: "Location", ask: "Where is the first layer where the numbers drift?", list: (o) => o.locations },
  { key: "signal", title: "Signal", ask: "Which comparison proves it?", list: (o) => o.signals },
  { key: "cause", title: "Cause", ask: "What is the mechanism?", list: (o) => o.causes },
];

/** Groups in the order the options list them, which is how the CLI prints its layer headers. */
function grouped(options: Option[]): { group: string | null; options: Option[] }[] {
  const out: { group: string | null; options: Option[] }[] = [];
  for (const o of options) {
    const g = o.group ?? null;
    const last = out[out.length - 1];
    if (last && last.group === g) last.options.push(o);
    else out.push({ group: g, options: [o] });
  }
  return out;
}

function Question({ q, options, value, onChange }: { q: (typeof QUESTIONS)[number]; options: Options; value: string; onChange: (v: string) => void }) {
  const list = q.list(options);
  const groups = useMemo(() => grouped(list), [list]);
  return (
    <fieldset className={`question question-${q.key}`}>
      <legend>
        <span className="question-title">{q.title}</span>
        <span className="question-ask">{q.ask}</span>
      </legend>
      {groups.map((g, gi) => (
        <div key={`${g.group}-${gi}`} className="question-group" role={g.group ? "group" : undefined} aria-label={g.group ?? undefined}>
          {g.group ? <p className="question-group-title">{g.group}</p> : null}
          {g.options.map((o) => (
            <label key={o.key} className={`choice ${value === o.key ? "is-checked" : ""}`}>
              <input type="radio" name={q.key} value={o.key} checked={value === o.key} onChange={() => onChange(o.key)} />
              <span>{o.label}</span>
            </label>
          ))}
        </div>
      ))}
    </fieldset>
  );
}

export function AnswerForm({ options, busy, error, onSubmit }: { options: Options; busy: boolean; error: unknown; onSubmit: (choices: Choices, note: string) => void }) {
  const [choices, setChoices] = useState<Partial<Choices>>({});
  const [note, setNote] = useState("");
  const done = QUESTIONS.every((q) => choices[q.key]);
  const missing = QUESTIONS.filter((q) => !choices[q.key]).map((q) => q.title.toLowerCase());

  return (
    <form
      className="answer-form"
      aria-labelledby="answer-title"
      onSubmit={(e) => {
        e.preventDefault();
        if (done) onSubmit(choices as Choices, note.trim());
      }}
    >
      <h2 id="answer-title" className="panel-title">
        Your answer
      </h2>
      <div className="questions">
        {QUESTIONS.map((q) => (
          <Question key={q.key} q={q} options={options} value={choices[q.key] ?? ""} onChange={(v) => setChoices((c) => ({ ...c, [q.key]: v }))} />
        ))}
      </div>
      <label className="field note-field">
        <span>One line about the evidence you used (optional, kept in your history)</span>
        <input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} placeholder="The panel or the number that convinced you" />
      </label>
      <div className="answer-actions">
        <button type="submit" className="btn btn-primary" disabled={!done || busy}>
          {busy ? "Scoring..." : "Submit the answer"}
        </button>
        {!done ? <span className="muted small">Still to pick: {missing.join(", ")}.</span> : null}
      </div>
      <ErrorNote error={error} />
    </form>
  );
}
