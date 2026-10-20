import { useEffect, useId, useRef } from "react";
import type { SettleStatus } from "../lib/api";

function announcement(status: SettleStatus | null): string {
  if (status?.settled) return "The lab is back to normal. Starting now.";
  if (status?.gave_up) return "The lab took too long to settle. Starting anyway.";
  const names = status?.checks.filter((c) => !c.ok).map((c) => c.name) ?? [];
  return names.length ? `Still waiting for: ${names.join(", ")}.` : "Waiting for the lab to settle.";
}

export function SettleCard({ status, starting, level = 2, onCancel }: { status: SettleStatus | null; starting: boolean; level?: 2 | 3; onCancel: () => void }) {
  const id = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const Heading = level === 3 ? "h3" : "h2";

  useEffect(() => heading.current?.focus(), []);

  return (
    <section className="panel settle-card" aria-labelledby={id}>
      <Heading id={id} ref={heading} tabIndex={-1} className="panel-title">
        Waiting for the lab to settle
      </Heading>
      <p role="status" className="visually-hidden">
        {announcement(status)}
      </p>
      <p className="small">
        The last exercise is still fading out. The next one starts by itself once the lab is back to normal, so its baseline holds no part of the old fault.
      </p>
      {status ? (
        <>
          <ul className="settle-checks">
            {status.checks.map((c) => (
              <li key={c.name} className={c.ok ? "is-ok" : "is-waiting"}>
                <span className="settle-mark">{c.ok ? "Ready" : "Waiting"}</span>
                <span>{c.name}</span>
                <span className="muted small">
                  {c.value === null ? "No data yet" : `Now ${c.value}`}. Want {c.target}.
                </span>
              </li>
            ))}
          </ul>
          <p className="muted small">Waited {Math.round(status.waited_seconds)} s since the last exercise ended.</p>
        </>
      ) : null}
      {starting ? <p className="small">Starting...</p> : null}
      <button type="button" className="btn" onClick={onCancel} disabled={starting}>
        Cancel
      </button>
    </section>
  );
}
