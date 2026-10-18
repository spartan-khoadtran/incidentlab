import { Link } from "react-router";
import { Pointer, Rich, Stat } from "../components/Bits";
import { Cmd } from "../components/Copy";
import { ArrowIcon } from "../components/Icons";
import { RequestPath } from "../components/RequestPath";
import { compose, guide, lab, make, readme, target } from "../lib/data";
import { MODES } from "../lib/modes";

function splitLede(lede: string): { head: string; rest: string } {
  const i = lede.indexOf(": ");
  if (i < 0) return { head: lede, rest: "" };
  const rest = lede.slice(i + 2);
  return { head: lede.slice(0, i), rest: rest[0].toUpperCase() + rest.slice(1) };
}

function linkify(value: string) {
  const parts = value.split(/(https?:\/\/[^\s),]+)/g);
  return parts.map((p, i) =>
    /^https?:\/\//.test(p) ? (
      <a key={i} href={p} target="_blank" rel="noopener noreferrer">
        {p.replace(/^https?:\/\//, "")}
      </a>
    ) : (
      <span key={i}>{p}</span>
    ),
  );
}

const QUICK = ["up", "new", "answer"];

export function Home() {
  const { head, rest } = splitLede(readme.lede);
  const grafana = make.links.find((l) => l.label === "Grafana");
  const containers = compose.services.filter((s) => s.profiles.length === 0).length;

  return (
    <div className="home">
      <section className="hero">
        <div className="hero-copy">
          <p className="kicker">Field handbook · incidentlab</p>
          <h1 className="hero-title">{head}.</h1>
          <p className="hero-lede">{rest}</p>
          <div className="hero-actions">
            <Link to="/how-it-works" className="btn btn-primary">
              How the lab works <ArrowIcon />
            </Link>
            <Link to="/guide" className="btn">
              Read the guide
            </Link>
          </div>
        </div>
        <div className="console" aria-label="Quick start">
          <div className="console-bar">
            <span className="console-dots" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            <span>quick start</span>
          </div>
          <ol className="console-steps">
            {QUICK.map((name, i) => (
              <li key={name}>
                <span className="console-step">{String(i + 1).padStart(2, "0")}</span>
                <div>
                  <Cmd text={`make ${name}`} />
                  <p>
                    <Rich text={target(name)?.help ?? ""} />
                  </p>
                  {name === "up" && grafana ? <p className="console-aside">Then open Grafana at {linkify(grafana.value)}.</p> : null}
                </div>
              </li>
            ))}
          </ol>
          <p className="console-foot">
            Stuck? <code>make hint</code> costs {lab.scoring.hint_penalty} point. <code>make</code> alone lists every command.
          </p>
        </div>
      </section>

      <section className="stats-band" aria-label="The lab in numbers">
        <Stat value={lab.counts.scenarios} label="incident scenarios" />
        <Stat value={lab.counts.toolbox} label="with a shell-only proof" />
        <Stat value={lab.counts.numbers} label="number drills" />
        <Stat value={lab.counts.concepts} label="concept questions" />
        <Stat value={guide.length} label="guide files" />
        <Stat value={containers} label="containers" />
      </section>

      <section className="block">
        <div className="block-head">
          <p className="kicker">Modes</p>
          <h2>Five ways to practice</h2>
          <p>Start with the quiz until the guide makes sense, then use it on a live system.</p>
        </div>
        <div className="modes">
          {MODES.map((m) => (
            <article key={m.id} className={`mode mode-${m.id}`}>
              <header>
                <h3>{m.name}</h3>
                <span className={`tag ${m.lab ? "tag-live" : "tag-offline"}`}>{m.lab ? "needs the lab" : "no lab needed"}</span>
              </header>
              <p className="mode-trains">{m.trains}</p>
              <dl className="mode-facts">
                <div>
                  <dt>Time</dt>
                  <dd>{m.time}</dd>
                </div>
                <div>
                  <dt>Score</dt>
                  <dd>{m.score}</dd>
                </div>
              </dl>
              <Cmd text={`make ${m.target}`} />
              <p className="mode-read">
                Read: {m.doc ? <Pointer doc={m.doc} compact /> : <Link to="/quiz">how the quiz works</Link>}
              </p>
            </article>
          ))}
        </div>
      </section>

      <section className="block split">
        <div>
          <div className="block-head">
            <p className="kicker">What it trains</p>
            <h2>Skills for the hour after the page</h2>
          </div>
          <ul className="drill-list">
            {readme.trains.map((t) => (
              <li key={t}>{t[0].toUpperCase() + t.slice(1)}</li>
            ))}
          </ul>
        </div>
        <div>
          <div className="block-head">
            <p className="kicker">When the lab is up</p>
            <h2>Where things are</h2>
          </div>
          <dl className="links-table">
            {make.links.map((l) => (
              <div key={l.label}>
                <dt>{l.label}</dt>
                <dd>{linkify(l.value)}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      <section className="block">
        <div className="block-head">
          <p className="kicker">The system</p>
          <h2>What you investigate</h2>
          <p>
            A small checkout system. The <Link to="/how-it-works">How it works</Link> page shows each container and the loop that injects the faults.
          </p>
        </div>
        <RequestPath />
      </section>
    </div>
  );
}
