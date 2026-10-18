import { useState } from "react";
import { lab, service } from "../lib/data";
import { facts } from "../lib/facts";
import { useReducedMotion } from "../lib/motion";
import { EDGES, edgePath, labelPoint, NODE_H, NODE_PAD, NODE_W, NODES, STAGES, VIEW_H, VIEW_W } from "../lib/topology";

const STAGE_SECONDS = 0.7;
const CYCLE = STAGES * STAGE_SECONDS + 1.2;

function Packet({ path, stage }: { path: string; stage: number }) {
  const start = (stage * STAGE_SECONDS) / CYCLE;
  const end = ((stage + 1) * STAGE_SECONDS) / CYCLE;
  const t = (n: number) => n.toFixed(4);
  return (
    <circle r="4.5" className="packet">
      <animateMotion dur={`${CYCLE}s`} repeatCount="indefinite" path={path} keyPoints="0;0;1;1" keyTimes={`0;${t(start)};${t(end)};1`} calcMode="linear" />
      <animate
        attributeName="opacity"
        dur={`${CYCLE}s`}
        repeatCount="indefinite"
        values="0;0;1;1;0;0"
        keyTimes={`0;${t(start)};${t(start + 0.005)};${t(end - 0.005)};${t(end)};1`}
      />
    </circle>
  );
}

export function RequestPath({ selected, onSelect }: { selected?: string | null; onSelect?: (id: string) => void }) {
  const reduced = useReducedMotion();
  const [hover, setHover] = useState<string | null>(null);
  const focus = selected ?? hover;
  const near = (id: string) => !focus || focus === id || EDGES.some((e) => (e.from === focus && e.to === id) || (e.to === focus && e.from === id));

  return (
    <figure className="diagram request-path">
      <svg viewBox={`0 0 ${VIEW_W} ${VIEW_H}`} role={onSelect ? "group" : "img"} aria-labelledby="rp-title rp-desc">
        <title id="rp-title">Request path of the lab</title>
        <desc id="rp-desc">
          loadgen sends requests to Envoy, Envoy to api. api reads Redis and Postgres, calls pricing and writes each order to Kafka. worker consumes Kafka and writes to Postgres.
        </desc>
        <defs>
          <marker id="rp-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" className="arrow-head" />
          </marker>
        </defs>
        {EDGES.map((e) => {
          const p = labelPoint(e);
          const lit = !focus || e.from === focus || e.to === focus;
          return (
            <g key={`${e.from}-${e.to}`} className={`edge ${lit ? "" : "is-dim"}`}>
              <path d={edgePath(e)} markerEnd="url(#rp-arrow)" />
              <text x={p.x} y={p.y} className="edge-label" textAnchor={p.anchor}>
                {e.label}
              </text>
            </g>
          );
        })}
        {!reduced && EDGES.map((e) => <Packet key={`p-${e.from}-${e.to}`} path={edgePath(e)} stage={e.stage} />)}
        {NODES.map((n) => {
          const s = service(n.id);
          const interactive = Boolean(onSelect);
          return (
            <g
              key={n.id}
              className={`node ${selected === n.id ? "is-selected" : ""} ${near(n.id) ? "" : "is-dim"} ${interactive ? "is-interactive" : ""}`}
              transform={`translate(${n.x - NODE_W / 2} ${n.y - NODE_H / 2})`}
              role={interactive ? "button" : undefined}
              tabIndex={interactive ? 0 : undefined}
              aria-label={interactive ? `${n.id}, ${n.role}. Show its settings` : undefined}
              aria-pressed={interactive ? selected === n.id : undefined}
              onClick={() => onSelect?.(n.id)}
              onKeyDown={(ev) => {
                if (onSelect && (ev.key === "Enter" || ev.key === " ")) {
                  ev.preventDefault();
                  onSelect(n.id);
                }
              }}
              onMouseEnter={() => setHover(n.id)}
              onMouseLeave={() => setHover(null)}
            >
              <rect width={NODE_W} height={NODE_H} rx="7" />
              <text x={NODE_PAD} y="22" className="node-name">
                {n.id}
              </text>
              <text x={NODE_PAD} y="40" className="node-facts">
                {facts(s) || n.role}
              </text>
            </g>
          );
        })}
      </svg>
      <figcaption>
        <span className="diagram-hint">Swipe sideways to see the whole path. </span>
        loadgen sends about {lab.defaults.base_rps} requests per second at the default load: one every {Math.round(1000 / lab.defaults.base_rps)} ms on average. {reduced ? "Motion is off because your system asks for less motion." : "Each dot is one hop of a checkout."}
      </figcaption>
    </figure>
  );
}
