import { useState } from "react";
import { Link } from "react-router";
import { PageHead, Rich } from "../components/Bits";
import { FaultLoop } from "../components/FaultLoop";
import { facts, RequestPath } from "../components/RequestPath";
import { compose, highlights, lab, service, target, type ComposeService } from "../lib/data";
import { NODES } from "../lib/topology";

function ServiceCard({ s, anchor = true }: { s: ComposeService; anchor?: boolean }) {
  const rows: [string, string][] = [];
  if (s.launcher) rows.push(["launcher", s.launcher]);
  else if (s.command && !s.image?.includes("/")) rows.push(["command", s.command]);
  if (s.image) rows.push(["image", s.image]);
  if (s.build) rows.push(["build", s.build]);
  if (s.port) rows.push(["port", s.port]);
  if (s.ports.length) rows.push(["host ports", s.ports.map((p) => (p.host === p.container ? p.host : `${p.host}:${p.container}`)).join(", ")]);
  if (s.cpus) rows.push(["cpus", s.cpus]);
  if (s.memLimit) rows.push(["mem_limit", s.memLimit]);
  if (s.cpuShares) rows.push(["cpu_shares", String(s.cpuShares)]);
  if (s.capAdd.length) rows.push(["cap_add", s.capAdd.join(", ")]);
  if (s.pidHost) rows.push(["pid", "host"]);
  if (s.privileged) rows.push(["privileged", "true"]);
  for (const [k, v] of Object.entries(s.env)) rows.push([k, v]);
  if (s.dependsOn.length) rows.push(["depends_on", s.dependsOn.join(", ")]);
  for (const m of s.configMounts) rows.push(["config", `${m.source} → ${m.target}`]);
  if (s.profiles.length) rows.push(["profile", s.profiles.join(", ")]);
  return (
    <article className="svc" id={anchor ? `svc-${s.name}` : undefined}>
      <header>
        <h3>{s.name}</h3>
        <span className="svc-facts">{facts(s)}</span>
      </header>
      {s.note ? (
        <p className="svc-note">
          <Rich text={s.note} />
        </p>
      ) : null}
      <dl className="kv">
        {rows.map(([k, v]) => (
          <div key={`${k}-${v}`}>
            <dt>{k}</dt>
            <dd>
              <code>{v}</code>
            </dd>
          </div>
        ))}
      </dl>
    </article>
  );
}

export function HowItWorks() {
  const [selected, setSelected] = useState<string | null>("api");
  const picked = selected ? service(selected) : undefined;
  const s = lab.scoring;
  const toolboxRules = target("toolbox")?.help;

  return (
    <div className="page">
      <PageHead kicker="How it works" title="One system, one fault at a time">
        <p>
          Docker Compose runs a checkout system and its observability stack. A controller turns one fault on. You find it with the dashboards, then <code>make answer</code> scores you.
        </p>
      </PageHead>

      <section className="block" aria-labelledby="h-system">
        <div className="block-head">
          <p className="kicker">01 · The system</p>
          <h2 id="h-system">The request path</h2>
          <p>Pick a box to see its settings from docker-compose.yml. The faded boxes do not talk to it directly.</p>
        </div>
        <div className="system">
          <RequestPath selected={selected} onSelect={(id) => setSelected((cur) => (cur === id ? null : id))} />
          <aside className="system-detail" aria-live="polite">
            {picked ? <ServiceCard s={picked} anchor={false} /> : <p className="muted">Pick a box in the diagram.</p>}
          </aside>
        </div>
        <p className="muted small">
          Around the request path: {compose.services.filter((x) => !NODES.some((n) => n.id === x.name) && x.profiles.length === 0).map((x) => x.name).join(", ")}. Each one has its card below.
        </p>
      </section>

      <section className="block" aria-labelledby="h-loop">
        <div className="block-head">
          <p className="kicker">02 · The fault control loop</p>
          <h2 id="h-loop">The daemon owns the faults</h2>
          <p>
            <code>control</code> runs <code>labctl daemon</code>. Every {lab.tick_seconds} seconds it does the same four steps, so a hand-made <code>PUT /admin/faults</code> lasts at most one tick.
          </p>
        </div>
        <FaultLoop />
      </section>

      <section className="block" aria-labelledby="h-exercise">
        <div className="block-head">
          <p className="kicker">03 · One exercise</p>
          <h2 id="h-exercise">From the ticket to the score</h2>
        </div>
        <ol className="timeline">
          <li>
            <h3>
              <code>make new</code>
            </h3>
            <p>
              Picks a scenario you did not play in the last {lab.recent_window} rounds and draws its random numbers. Within {lab.tick_seconds} seconds the daemon turns the fault on. You get a ticket written like a message from a teammate.
            </p>
          </li>
          <li>
            <h3>Investigate</h3>
            <p>
              Dashboards <em>01 · RED by layer</em> and <em>02 · USE by layer</em>, traces in Explore &gt; Tempo, profiles in Explore &gt; Pyroscope. Compare with the minutes before the ticket. An orange line on a chart is a change event, and some of them are decoys.
            </p>
          </li>
          <li>
            <h3>
              <code>make hint</code>, if you need it
            </h3>
            <p>Three hints per scenario, from broad to specific. Each one costs {s.hint_penalty} point.</p>
          </li>
          <li>
            <h3>
              <code>make answer</code>
            </h3>
            <p>Four multiple choice questions, then a one-line note about the evidence you used. The note shows up in <code>make history</code> next to the score.</p>
          </li>
          <li>
            <h3>The explanation</h3>
            <p>The mechanism with the numbers of your run, the PromQL that proves it, the investigation step by step and the guide section to read again. Then Enter clears the fault.</p>
          </li>
        </ol>
        {toolboxRules ? (
          <p className="callout">
            <strong>Toolbox mode</strong> is the same loop with no Grafana. <code>make toolbox</code>: <Rich text={toolboxRules.replace(/\. Options:.*$/, "")} />.
          </p>
        ) : null}
      </section>

      <section className="block" aria-labelledby="h-score">
        <div className="block-head">
          <p className="kicker">04 · Scoring</p>
          <h2 id="h-score">{s.max_points} points per exercise</h2>
        </div>
        <div className="score-sheet">
          <table>
            <thead>
              <tr>
                <th scope="col">Question</th>
                <th scope="col">What it asks</th>
                <th scope="col" className="num">
                  Points
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">Nature</th>
                <td>Load, architecture, or a rate that rose at one layer without real demand</td>
                <td className="num">{s.nature_points}</td>
              </tr>
              <tr>
                <th scope="row">Location</th>
                <td>The first layer where the numbers drift</td>
                <td className="num">{s.location_points}</td>
              </tr>
              <tr>
                <th scope="row">Signal</th>
                <td>The one comparison that proves it</td>
                <td className="num">{s.signal_points}</td>
              </tr>
              <tr>
                <th scope="row">Cause</th>
                <td>The mechanism. A visible effect one step before the root cause earns {s.partial_cause_points}</td>
                <td className="num">{s.cause_points}</td>
              </tr>
              <tr className="row-minus">
                <th scope="row">Each hint</th>
                <td>Taken off the total, never below 0</td>
                <td className="num">−{s.hint_penalty}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Max</th>
                <td />
                <td className="num">{s.max_points}</td>
              </tr>
            </tfoot>
          </table>
          <p className="muted small">
            The answer lists hold {lab.counts.causes} causes and {lab.counts.signals} signals. Some causes belong to no scenario, so the list is not a copy of the catalog.
          </p>
        </div>
      </section>

      {highlights.inThisLab ? (
        <section className="block" aria-labelledby="h-dash">
          <div className="block-head">
            <p className="kicker">05 · The dashboards</p>
            <h2 id="h-dash">Where each layer lives</h2>
            <p>
              From the guide's <Link to="/guide/investigation-guide#in-this-lab">In this lab</Link> section.
            </p>
          </div>
          <div className="prose html-content" dangerouslySetInnerHTML={{ __html: highlights.inThisLab }} />
        </section>
      ) : null}

      <section className="block" aria-labelledby="h-all">
        <div className="block-head">
          <p className="kicker">06 · Every container</p>
          <h2 id="h-all">From docker-compose.yml</h2>
        </div>
        {compose.groups.map((g) => (
          <div key={g} className="svc-group">
            <h3 className="svc-group-title">{g}</h3>
            <div className="svc-grid">
              {compose.services
                .filter((x) => x.group === g)
                .map((x) => (
                  <ServiceCard key={x.name} s={x} />
                ))}
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}
