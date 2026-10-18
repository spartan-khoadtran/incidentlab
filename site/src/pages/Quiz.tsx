import { Link } from "react-router";
import { PageHead, Stat } from "../components/Bits";
import { Cmd } from "../components/Copy";
import { docByFile, lab, target } from "../lib/data";

export function Quiz() {
  const quiz = target("quiz");
  const kinds = quiz?.options.find((o) => o.name === "KIND")?.example?.split("|") ?? [];
  const rows = lab.quiz
    .map((q) => ({ ...q, doc: docByFile(q.file), total: q.numbers + q.concepts }))
    .sort((a, b) => (a.doc?.order ?? 999) - (b.doc?.order ?? 999));
  const max = Math.max(...rows.map((r) => r.total));
  const n = lab.defaults.quiz_questions;

  return (
    <div className="page">
      <PageHead kicker="Quiz" title="Drills from the guide, in the terminal">
        <p>{quiz?.help}.</p>
      </PageHead>

      <section className="stats-band" aria-label="Quiz size">
        <Stat value={lab.counts.numbers} label="number drills, new values every run" />
        <Stat value={lab.counts.concepts} label="multiple choice questions" />
        <Stat value={n} label="questions by default" />
      </section>

      <section className="block split">
        <div>
          <div className="block-head">
            <p className="kicker">How it works</p>
            <h2>One question at a time</h2>
          </div>
          <ul className="plain-list">
            <li>
              A <strong>number</strong> drill draws new values each time and takes a number. A comma or a dot both work as the decimal sign. The answer shows the arithmetic.
            </li>
            <li>
              A <strong>concept</strong> question takes the number of one option. The options are shuffled every run, so a position is never the answer.
            </li>
            <li>Every question ends with the guide section to read again, in the same <code>Section &gt; Subsection</code> form the Guide shows under each heading.</li>
            <li>The score is right answers out of the questions asked. Nothing is saved.</li>
          </ul>
        </div>
        <div>
          <div className="block-head">
            <p className="kicker">Commands</p>
            <h2>Pick the size and the kind</h2>
          </div>
          <div className="cmd-stack">
            <Cmd text="make quiz" note={`${n} mixed questions`} />
            <Cmd text="make quiz N=10" note="10 mixed questions" />
            {kinds
              .filter((k) => k !== "all")
              .map((k) => (
                <Cmd key={k} text={`make quiz KIND=${k}`} note={`${k} only`} />
              ))}
          </div>
        </div>
      </section>

      <section className="block">
        <div className="block-head">
          <p className="kicker">Coverage</p>
          <h2>Questions per guide file</h2>
          <p>A question counts for the file of the first section it points at. The questions and answers stay in the terminal.</p>
        </div>
        <table className="coverage">
          <thead>
            <tr>
              <th scope="col">Guide file</th>
              <th scope="col" className="num">
                Numbers
              </th>
              <th scope="col" className="num">
                Concepts
              </th>
              <th scope="col" className="bar-col">
                <span className="visually-hidden">Share</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.file}>
                <th scope="row">
                  {r.doc ? <Link to={`/guide/${r.doc.slug}`}>{r.doc.title}</Link> : r.file}
                  <code className="coverage-file">{r.file}</code>
                </th>
                <td className="num">{r.numbers}</td>
                <td className="num">{r.concepts}</td>
                <td className="bar-col" aria-hidden="true">
                  <span className="bar">
                    <span className="bar-numbers" style={{ width: `${(r.numbers / max) * 100}%` }} />
                    <span className="bar-concepts" style={{ width: `${(r.concepts / max) * 100}%` }} />
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">All files</th>
              <td className="num">{lab.counts.numbers}</td>
              <td className="num">{lab.counts.concepts}</td>
              <td className="bar-col legend">
                <span className="legend-numbers">numbers</span> <span className="legend-concepts">concepts</span>
              </td>
            </tr>
          </tfoot>
        </table>
      </section>
    </div>
  );
}
