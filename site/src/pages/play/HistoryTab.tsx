import { useEffect, useState } from "react";
import { Stat } from "../../components/Bits";
import { ErrorNote } from "../../components/LabUi";
import { api, type History } from "../../lib/api";
import { lab } from "../../lib/data";

const day = new Intl.DateTimeFormat(undefined, { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });

function plain(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

export function HistoryTab() {
  const [history, setHistory] = useState<History | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    void api.history().then(setHistory, setError);
  }, []);

  if (error) return <ErrorNote error={error} />;
  if (!history) return <p className="lab-wait">Reading your history...</p>;
  if (!history.entries.length) return <p className="muted">No exercise yet. Your scores and notes show up here after the first answer.</p>;

  const max = lab.scoring.max_points;
  const scored = history.entries.filter((e) => e.score !== null);
  return (
    <div className="history">
      <section className="stats-band" aria-label="Summary">
        <Stat value={history.average === null ? "-" : `${history.average.toFixed(2)}`} label={`average score out of ${max}`} />
        <Stat value={scored.length} label="scored exercises" />
        <Stat value={history.entries.length - scored.length} label="revealed or not scored" />
      </section>
      <div className="split">
        <section>
          <h2 className="panel-title">By topic</h2>
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Topic</th>
                <th scope="col" className="num">
                  Average
                </th>
                <th scope="col" className="num">
                  Exercises
                </th>
              </tr>
            </thead>
            <tbody>
              {history.by_topic.map((t) => (
                <tr key={t.topic}>
                  <th scope="row">{t.topic}</th>
                  <td className="num">{t.average.toFixed(1)}</td>
                  <td className="num">{t.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <section>
          <h2 className="panel-title">The cause you miss most</h2>
          <p>
            {history.most_missed_cause ? (
              <>
                {history.most_missed_cause.label} <span className="muted small">({history.most_missed_cause.count} times)</span>
              </>
            ) : (
              "None yet."
            )}
          </p>
        </section>
      </div>
      <h2 className="panel-title">Every exercise</h2>
      <div className="table-scroll">
        <table className="data-table history-table">
          <thead>
            <tr>
              <th scope="col">#</th>
              <th scope="col">When</th>
              <th scope="col">Exercise</th>
              <th scope="col" className="num">
                Score
              </th>
              <th scope="col" className="num">
                Hints
              </th>
              <th scope="col" className="num">
                Min
              </th>
              <th scope="col">Missed</th>
            </tr>
          </thead>
          <tbody>
            {[...history.entries].reverse().map((e) => (
              <tr key={e.n}>
                <td className="num">{e.n}</td>
                <td className="nowrap">{day.format(new Date(e.at * 1000))}</td>
                <td>
                  <span className="history-title">{e.title}</span>
                  <span className="muted small">
                    {" "}
                    {e.mode !== "incident" ? `${e.mode}` : ""}{e.mode !== "incident" && e.level ? " · " : ""}{e.level ? `level ${e.level}` : ""}
                  </span>
                  {e.note ? <span className="history-note">{e.note}</span> : null}
                </td>
                <td className="num">{e.score === null ? "-" : `${plain(e.score)}/${max}`}</td>
                <td className="num">{e.hints}</td>
                <td className="num">{plain(e.minutes)}</td>
                <td className="small">{e.missed.join(", ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
