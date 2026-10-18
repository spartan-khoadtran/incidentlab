import { CopyButton } from "../../components/Copy";
import { GuideLinks } from "../../components/LabUi";
import type { Option, Options, Result, Reveal, Verdict } from "../../lib/api";
import { lab } from "../../lib/data";
import { promqlUrl, type TimeRange } from "../../lib/grafana";
import type { Choices } from "./AnswerForm";

const ROWS: { key: keyof Choices; title: string; list: (o: Options) => Option[] }[] = [
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

export function ResultView({
  reveal,
  result,
  savedScore = null,
  choices,
  options,
  toolbox,
  grafanaUrl,
  range,
}: {
  reveal: Reveal;
  result: Result | null;
  savedScore?: number | null;
  choices: Choices | null;
  options: Options | null;
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
        ) : savedScore !== null ? (
          <div className="score" aria-label={`Score ${plain(savedScore)} out of ${lab.scoring.max_points}`}>
            <span className="score-value">{plain(savedScore)}</span>
            <span className="score-max">/ {lab.scoring.max_points}</span>
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
