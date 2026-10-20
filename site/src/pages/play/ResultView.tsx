import { CopyButton } from "../../components/Copy";
import { GuideLinks } from "../../components/LabUi";
import type { AnswerOptions, Option, Result, Reveal, RuleOut, Verdict } from "../../lib/api";
import { promqlUrl, type TimeRange } from "../../lib/grafana";
import { grouped, type Choices } from "./AnswerForm";

const ROWS: { key: keyof Choices; title: string; list: (o: AnswerOptions) => Option[] }[] = [
  { key: "nature", title: "Nature", list: (o) => o.natures },
  { key: "location", title: "Location", list: (o) => o.locations },
  { key: "signal", title: "Signal", list: (o) => o.signals },
  { key: "cause", title: "Cause", list: (o) => o.causes },
];

const VERDICT_LABEL: Record<Verdict, string> = { correct: "Right", partial: "Partial", wrong: "Missed" };

function accepted(reveal: Reveal, key: keyof Choices): Option[] {
  return key === "cause" ? [reveal.answer.cause] : reveal.answer[key];
}

function plain(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

function pickClass(r: RuleOut): string {
  if (r.correct) return "verdict-correct";
  return r.partial ? "verdict-partial" : "verdict-wrong";
}

function RuleOuts({ ruleOuts, picked }: { ruleOuts: RuleOut[]; picked: string | null }) {
  const byKey = new Map(ruleOuts.map((r) => [r.cause.key, r]));
  const groups = grouped(ruleOuts.map((r) => r.cause));
  return (
    <section className="result-block" aria-labelledby="ruleouts-title">
      <h3 id="ruleouts-title">Rule out the look-alikes</h3>
      <p className="muted small">Every cause the form offered, with the check that tells it apart. Next time, run these checks before you pick.</p>
      {groups.map((g, gi) => (
        <div key={`${g.group}-${gi}`} className="ruleout-group">
          {g.group ? <p className="question-group-title">{g.group}</p> : null}
          <ul className="ruleouts" aria-label={g.group ?? undefined}>
            {g.options.map((o) => {
              const r = byKey.get(o.key)!;
              const mine = picked === o.key;
              const half = !r.correct && r.partial;
              const wrongPick = mine && !r.correct && !r.partial;
              return (
                <li key={o.key} className={`ruleout${r.correct ? " is-cause" : ""}${mine && half ? " is-partial-pick" : ""}${wrongPick ? " is-wrong-pick" : ""}`}>
                  <p className="ruleout-head">
                    <span className="ruleout-label">{o.label}</span>
                    {r.correct ? <span className="verdict verdict-correct">The cause</span> : null}
                    {half ? <span className="verdict verdict-partial">1 point</span> : null}
                    {mine ? <span className={`verdict ${pickClass(r)}`}>Your pick</span> : null}
                  </p>
                  <p className="ruleout-check">{r.check}</p>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </section>
  );
}

export function ResultView({
  reveal,
  result,
  choices,
  options,
  toolbox,
  grafanaUrl,
  range,
}: {
  reveal: Reveal;
  result: Result | null;
  choices: Choices | null;
  options: AnswerOptions | null;
  toolbox: boolean;
  grafanaUrl: string | null;
  range: TimeRange;
}) {
  const label = (key: keyof Choices, value: string) => (options ? ROWS.find((r) => r.key === key)!.list(options).find((o) => o.key === value)?.label : null) ?? value;

  return (
    <article className="result" aria-labelledby="result-title">
      <header className="result-head">
        {result ? (
          <div className="score" aria-label={`Score ${plain(result.score)} out of ${plain(result.max)}`}>
            <span className="score-value">{plain(result.score)}</span>
            <span className="score-max">/ {plain(result.max)}</span>
          </div>
        ) : (
          <div className="score score-none">
            <span className="score-value">-</span>
            <span className="score-max">revealed, not scored</span>
          </div>
        )}
        <div>
          <p className="kicker">
            It was <code>{reveal.id}</code> · level {reveal.level} · {reveal.topics.join(", ")}
          </p>
          <h2 id="result-title">{reveal.title}</h2>
          {result ? (
            <p className="muted">
              Correct answers {plain(result.raw)} / {plain(result.max)}, hints used {result.hints_used}.
            </p>
          ) : null}
        </div>
      </header>

      <table className="verdicts">
        <thead>
          <tr>
            <th scope="col">Question</th>
            {choices ? <th scope="col">You said</th> : null}
            {result ? <th scope="col">Verdict</th> : null}
            <th scope="col">Accepted</th>
          </tr>
        </thead>
        <tbody>
          {ROWS.map((r) => {
            const verdict = result?.verdicts[r.key];
            return (
              <tr key={r.key}>
                <th scope="row">{r.title}</th>
                {choices ? <td>{label(r.key, choices[r.key])}</td> : null}
                {verdict ? (
                  <td>
                    <span className={`verdict verdict-${verdict}`}>{VERDICT_LABEL[verdict]}</span>
                  </td>
                ) : null}
                <td>
                  {accepted(reveal, r.key)
                    .map((o) => o.label)
                    .join(" / ")}
                  {r.key === "cause" && reveal.answer.partial.length ? (
                    <span className="muted small"> (1 point for: {reveal.answer.partial.map((o) => o.label).join(" / ")})</span>
                  ) : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <section className="result-block">
        <h3>What happened</h3>
        <p className="explanation">{reveal.explanation}</p>
      </section>

      <section className="result-block">
        <h3>The investigation, step by step</h3>
        <ol className="trace">
          {reveal.steps.map((s, i) => (
            <li key={i}>{s}</li>
          ))}
        </ol>
      </section>

      {reveal.rule_outs?.length ? <RuleOuts ruleOuts={reveal.rule_outs} picked={choices?.cause ?? null} /> : null}

      {reveal.evidence.length ? (
        <section className="result-block">
          <h3>The PromQL that proves it</h3>
          <ul className="evidence">
            {reveal.evidence.map((q) => {
              const href = !toolbox && grafanaUrl ? promqlUrl(grafanaUrl, q, range) : null;
              return (
                <li key={q}>
                  <code className="evidence-query">{q}</code>
                  <span className="evidence-actions">
                    <CopyButton text={q} label="Copy the query" />
                    {href ? (
                      <a href={href} target="_blank" rel="noopener noreferrer" className="evidence-link">
                        Open in Grafana Explore
                      </a>
                    ) : null}
                  </span>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {reveal.cli.length ? (
        <section className="result-block">
          <h3>The same proof from a shell</h3>
          <p className="muted small">
            Run these in <code>make shell</code>. The part after <code>#</code> says what to read.
          </p>
          <ul className="evidence">
            {reveal.cli.map((c) => {
              const [command, read] = c.split(/\s{2,}#\s*/);
              return (
                <li key={c}>
                  <code className="evidence-query">{command}</code>
                  <span className="evidence-actions">
                    <CopyButton text={command} label="Copy the command" />
                  </span>
                  {read ? <span className="evidence-read">{read}</span> : null}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {reveal.guide.length ? (
        <section className="result-block">
          <h3>Read again in the guide</h3>
          <GuideLinks refs={reveal.guide} />
        </section>
      ) : null}
    </article>
  );
}
