import type { SettleStatus } from "../lib/api";

export function SettleCard({ status, onCancel }: { status: SettleStatus | null; onCancel: () => void }) {
  return (
    <section className="panel settle-card" role="status" aria-labelledby="settle-title">
      <h2 id="settle-title" className="panel-title">
        Waiting for the lab to settle
      </h2>
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
                  {c.value ?? "no data"} (want {c.target})
                </span>
              </li>
            ))}
          </ul>
          <p className="muted small">Waited {Math.round(status.waited_seconds)} s since the last exercise ended.</p>
        </>
      ) : null}
      <button type="button" className="btn" onClick={onCancel}>
        Cancel
      </button>
    </section>
  );
}
