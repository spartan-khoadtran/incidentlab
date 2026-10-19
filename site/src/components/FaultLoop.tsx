import type { CSSProperties, ReactNode } from "react";
import { lab } from "../lib/data";
import { useReducedMotion } from "../lib/motion";

interface Station {
  title: string;
  body: ReactNode;
}

const STATIONS: Station[] = [
  {
    title: "Read the state",
    body: (
      <>
        <code>/state/state.json</code> on the <code>labstate</code> volume: the active exercise, the history and the base load from <code>make load</code>.
      </>
    ),
  },
  {
    title: "Build the overrides",
    body: <>Per service: the base load plus the faults the scenario asks for at this second. A fault can change while you investigate.</>,
  },
  {
    title: "Push the faults",
    body: (
      <>
        <code>PUT /admin/faults</code> to every service. A service the scenario does not name gets <code>{"{}"}</code>, which resets it.
      </>
    ),
  },
  {
    title: "Set the containers",
    body: <>Through the Docker socket: the api CPU limit, netem shaping on api, the write IOPS limit of postgres.</>,
  },
];

export function FaultLoop() {
  const reduced = useReducedMotion();
  const tick = lab.tick_seconds;
  const style = { "--tick": `${tick}s` } as CSSProperties;
  const r = 104;
  return (
    <div className={`fault-loop ${reduced ? "is-still" : ""}`} style={style}>
      <svg viewBox="-150 -150 300 300" className="loop-dial" role="img" aria-label={`The control daemon runs these four steps every ${tick} seconds`}>
        <circle r={r} className="loop-ring" />
        {Array.from({ length: 24 }, (_, i) => {
          const a = (i / 24) * Math.PI * 2;
          const long = i % 6 === 0;
          return <line key={i} x1={Math.sin(a) * (r - (long ? 14 : 7))} y1={-Math.cos(a) * (r - (long ? 14 : 7))} x2={Math.sin(a) * r} y2={-Math.cos(a) * r} className="loop-tick" />;
        })}
        <g className="loop-hand">
          <path d={`M 0 0 L 0 ${-r + 4}`} />
          <circle r="5" />
        </g>
        {STATIONS.map((s, i) => {
          const a = ((i + 0.5) / STATIONS.length) * Math.PI * 2;
          return (
            <g key={s.title} className="loop-station" style={{ "--i": i } as CSSProperties} transform={`translate(${Math.sin(a) * r} ${-Math.cos(a) * r})`}>
              <circle r="15" />
              <text dy="5" textAnchor="middle">
                {i + 1}
              </text>
            </g>
          );
        })}
        <text y="-6" textAnchor="middle" className="loop-center">
          labctl daemon
        </text>
        <text y="16" textAnchor="middle" className="loop-center-sub">
          every {tick} s
        </text>
      </svg>
      <ol className="loop-steps">
        {STATIONS.map((s, i) => (
          <li key={s.title} style={{ "--i": i } as CSSProperties}>
            <span className="loop-num" aria-hidden="true">
              {i + 1}
            </span>
            <div>
              <h4>{s.title}</h4>
              <p>{s.body}</p>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
