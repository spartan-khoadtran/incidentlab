import { useMemo } from "react";
import { useSearchParams } from "react-router";
import { Level, PageHead } from "../components/Bits";
import { Cmd } from "../components/Copy";
import { lab, type Scenario } from "../lib/data";
import { normalize } from "../lib/search";

function matches(s: Scenario, level: number | null, topic: string | null, toolbox: boolean, q: string): boolean {
  if (level !== null && s.level !== level) return false;
  if (topic && !s.topics.includes(topic)) return false;
  if (toolbox && !s.toolbox) return false;
  if (q) {
    const hay = normalize(`${s.id.replace(/_/g, " ")} ${s.id} ${s.title} ${s.topics.join(" ")}`);
    return normalize(q)
      .split(" ")
      .every((t) => hay.includes(t));
  }
  return true;
}

const TOPIC_COUNTS: Record<string, number> = Object.fromEntries(lab.topics.map((t) => [t, lab.scenarios.filter((s) => s.topics.includes(t)).length]));
const TOPICS = lab.topics.filter((t) => TOPIC_COUNTS[t] > 0);
/** A topic no incident carries, with the modes whose guide file is named after it (capacity: ramp and bench). */
const NO_INCIDENT = lab.topics
  .filter((t) => TOPIC_COUNTS[t] === 0)
  .map((t) => ({ topic: t, modes: lab.modes.filter((m) => m.guide_file === `${t}.md`).map((m) => m.id) }));

export function Catalog() {
  const [params, setParams] = useSearchParams();
  const levelParam = Number(params.get("level"));
  const level = lab.levels.includes(levelParam) ? levelParam : null;
  const topicParam = params.get("topic");
  const topic = topicParam && TOPICS.includes(topicParam) ? topicParam : null;
  const toolbox = params.get("toolbox") === "1";
  const q = params.get("q") ?? "";

  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null || value === "") next.delete(key);
    else next.set(key, value);
    setParams(next, { replace: true, preventScrollReset: true });
  };

  const shown = useMemo(() => lab.scenarios.filter((s) => matches(s, level, topic, toolbox, q)), [level, topic, toolbox, q]);
  const filterArgs = [level !== null ? `LEVEL=${level}` : null, topic ? `TOPIC=${topic}` : null].filter(Boolean).join(" ");
  const randomCmd = `make ${toolbox ? "toolbox" : "new"}${filterArgs ? ` ${filterArgs}` : ""}`;

  return (
    <div className="page">
      <PageHead kicker="Catalog" title={`${lab.counts.scenarios} incidents to work`}>
        <p>
          The same list <code>make list</code> prints: id, title, level and topics. Titles hint at the cause, so <code>make new</code> picks at random and does not say which one it chose.
        </p>
        <p className="callout callout-quiet">
          No answers here. The cause, the signal, the ticket, the hints, the proof and the guide section to read again only show in the terminal, after you play.
        </p>
      </PageHead>

      <div className="filters" role="search" aria-label="Filter the catalog">
        <fieldset className="seg">
          <legend>Level</legend>
          <button type="button" aria-pressed={level === null} onClick={() => set("level", null)}>
            All
          </button>
          {lab.levels.map((l) => (
            <button key={l} type="button" aria-pressed={level === l} onClick={() => set("level", level === l ? null : String(l))}>
              <Level level={l} />
            </button>
          ))}
        </fieldset>
        <fieldset className="chips">
          <legend>Topic</legend>
          {TOPICS.map((t) => (
            <button key={t} type="button" className="chip" aria-pressed={topic === t} onClick={() => set("topic", topic === t ? null : t)}>
              {t}
              <span className="chip-count">{TOPIC_COUNTS[t]}</span>
            </button>
          ))}
        </fieldset>
        {NO_INCIDENT.length ? (
          <p className="filters-note">
            {NO_INCIDENT.map(({ topic: t, modes }, i) => (
              <span key={t}>
                {i > 0 ? " " : ""}
                No incident has the topic <code>{t}</code>
                {modes.length ? (
                  <>
                    : {modes.map((m, j) => (
                      <span key={m}>
                        {j > 0 ? " and " : ""}
                        <code>make {m}</code>
                      </span>
                    ))}{" "}
                    {modes.length > 1 ? "train" : "trains"} it.
                  </>
                ) : (
                  "."
                )}
              </span>
            ))}
          </p>
        ) : null}
        <div className="filters-row">
          <label className="switch">
            <input type="checkbox" checked={toolbox} onChange={(e) => set("toolbox", e.target.checked ? "1" : null)} />
            <span className="switch-track" aria-hidden="true" />
            Playable from the shell (<code>[toolbox]</code>)
          </label>
          <label className="filter-text">
            <span className="visually-hidden">Filter by id or title</span>
            <input type="search" placeholder="Filter by id or title" value={q} onChange={(e) => set("q", e.target.value)} />
          </label>
        </div>
        <div className="filters-play">
          <span>{q ? "A random one from this level and topic:" : "A random one from this filter:"}</span>
          <Cmd text={randomCmd} />
        </div>
      </div>

      <p className="board-count" role="status">
        {shown.length === lab.scenarios.length ? `All ${shown.length} scenarios` : `${shown.length} of ${lab.scenarios.length} scenarios`}
      </p>

      {lab.levels.map((l) => {
        const rows = shown.filter((s) => s.level === l);
        if (!rows.length) return null;
        return (
          <section key={l} className="board" aria-labelledby={`level-${l}`}>
            <h2 id={`level-${l}`} className="board-title">
              <Level level={l} /> Level {l}
              <span className="board-title-count">{rows.length}</span>
            </h2>
            <ul className="board-rows">
              {rows.map((s) => (
                <li key={s.id} id={s.id} className="board-row">
                  <div className="row-id">
                    <code>{s.id}</code>
                    {s.toolbox ? <span className="tag tag-toolbox">toolbox</span> : null}
                  </div>
                  <h3 className="row-title">{s.title}</h3>
                  <div className="row-topics" aria-label="Topics">
                    {s.topics.map((t) => (
                      <button key={t} type="button" className="chip chip-small" aria-pressed={topic === t} onClick={() => set("topic", topic === t ? null : t)}>
                        {t}
                      </button>
                    ))}
                  </div>
                  <div className="row-play">
                    <Cmd text={`make new ID=${s.id}`} />
                    {s.toolbox ? <Cmd text={`make toolbox ID=${s.id}`} /> : null}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
      {shown.length === 0 ? <p className="muted">No scenario matches. Clear a filter.</p> : null}
    </div>
  );
}
