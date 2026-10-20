import { useState } from "react";
import { Cmd } from "../../components/Copy";
import { Elapsed, ErrorNote } from "../../components/LabUi";
import type { ActiveView, Status } from "../../lib/api";
import { dashboardUrl, exerciseRange, exploreUrl, grafana } from "../../lib/grafana";

function plain(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

export function TicketCard({ view }: { view: ActiveView }) {
  return (
    <article className="ticket" aria-labelledby="ticket-title">
      <header className="ticket-head">
        <p id="ticket-title" className="ticket-id">
          Ticket #{view.n}
        </p>
        <dl className="ticket-meta">
          <div>
            <dt>Mode</dt>
            <dd>{view.mode}</dd>
          </div>
          {view.level ? (
            <div>
              <dt>Level</dt>
              <dd>{view.level}</dd>
            </div>
          ) : null}
          <div>
            <dt>Open for</dt>
            <dd>
              <Elapsed startedAt={view.started_at} />
            </dd>
          </div>
          <div>
            <dt>Hints</dt>
            <dd>
              {view.hints_used} / {view.hints_total}
            </dd>
          </div>
        </dl>
      </header>
      <p className="ticket-text">{view.ticket}</p>
      {view.rules ? <p className="ticket-rules">{view.rules}</p> : null}
    </article>
  );
}

export function Hints({ view, busy, error, onHint }: { view: ActiveView; busy: boolean; error: unknown; onHint: () => void }) {
  const left = view.hints_total - view.hints_used;
  const penalty = view.hints_used * view.hint_penalty;
  return (
    <section className="hints" aria-labelledby="hints-title">
      <h2 id="hints-title" className="panel-title">
        Hints
      </h2>
      {view.hints.length ? (
        <ol className="hint-list">
          {view.hints.map((h, i) => (
            <li key={i}>{h}</li>
          ))}
        </ol>
      ) : (
        <p className="muted small">No hint taken. They go from broad to specific.</p>
      )}
      <div className="hint-actions">
        <button type="button" className="btn" onClick={onHint} disabled={busy || left === 0 || view.answered}>
          {left === 0 ? "No hint left" : `Take hint ${view.hints_used + 1} (−${plain(view.hint_penalty)} point)`}
        </button>
        <span className="muted small">{penalty ? `Penalty so far: −${plain(penalty)}` : "No penalty yet"}</span>
      </div>
      <ErrorNote error={error} />
    </section>
  );
}

export function ExplorePanel({ view, status }: { view: ActiveView; status: Status | null }) {
  const [open, setOpen] = useState(true);
  if (view.mode === "toolbox") {
    return (
      <section className="explore" aria-labelledby="explore-title">
        <h2 id="explore-title" className="panel-title">
          Your tools
        </h2>
        <p className="small">No Grafana in this mode. Open a root shell on the Docker VM and prove the cause with commands.</p>
        <Cmd text="make shell" />
      </section>
    );
  }
  const g = status?.links.grafana;
  const range = exerciseRange(view.started_at, view.baseline_from);
  const links: { label: string; note: string; href: string | null }[] = g
    ? [
        ...grafana.dashboards.map((d) => ({ label: d.title, note: "dashboard", href: dashboardUrl(g, d.uid, range) })),
        { label: "Explore: Prometheus", note: "PromQL", href: exploreUrl(g, "prometheus", range, { expr: "" }) },
        { label: "Explore: Tempo", note: "traces", href: exploreUrl(g, "tempo", range) },
        { label: "Explore: Pyroscope", note: "profiles", href: exploreUrl(g, "grafana-pyroscope-datasource", range) },
      ]
    : [];
  if (status?.links.pyroscope) links.push({ label: "Pyroscope UI", note: "profiles", href: status.links.pyroscope });
  return (
    <section className="explore" aria-labelledby="explore-title">
      <h2 id="explore-title" className="panel-title">
        <button type="button" className="panel-toggle" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          Investigate
        </button>
      </h2>
      {open ? (
        <>
          <p className="small muted">From the end of the last exercise, or 15 minutes before the ticket, to now. So the baseline is on the chart, with no part of the exercise before.</p>
          <ul className="explore-links">
            {links
              .filter((l) => l.href)
              .map((l) => (
                <li key={l.label}>
                  <a href={l.href!} target="_blank" rel="noopener noreferrer">
                    <span>{l.label}</span>
                    <span className="explore-note">{l.note}</span>
                  </a>
                </li>
              ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}
