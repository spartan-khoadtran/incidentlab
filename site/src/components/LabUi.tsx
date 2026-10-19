import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { ApiError } from "../lib/api";
import { guide } from "../lib/guide";
import { useLab } from "../lib/lab";
import { resolvePointer } from "../lib/pointers";
import { Cmd } from "./Copy";

/** What an interactive page shows instead of its controls while the lab is not running. */
export function LabGate({ children, what }: { children: ReactNode; what: string }) {
  const { online, seen } = useLab();
  if (online === "online" || seen)
    return (
      <>
        {online === "online" ? null : (
          <div className="lab-offline lab-lost" role="status">
            <p className="lab-offline-title">
              <span className="lab-dot is-off" aria-hidden="true" /> The lab stopped answering
            </p>
            <p>Your work on this page is kept. It connects again by itself when the lab answers. If you stopped it, start it again with:</p>
            <Cmd text="make up" />
          </div>
        )}
        {children}
      </>
    );
  if (online === "checking")
    return (
      <p className="lab-wait" role="status">
        Looking for the lab...
      </p>
    );
  return (
    <div className="lab-offline" role="status">
      <p className="lab-offline-title">
        <span className="lab-dot is-off" aria-hidden="true" /> The lab is not running
      </p>
      <p>
        Start it in a terminal at the root of the repo. {what} This page connects by itself when the lab answers.
      </p>
      <Cmd text="make up" />
    </div>
  );
}

export function LabBadge() {
  const { online } = useLab();
  const label = online === "online" ? "Lab running" : online === "checking" ? "Looking for the lab" : "Lab not running";
  return (
    <span className={`lab-badge is-${online}`} title={label}>
      <span className={`lab-dot ${online === "online" ? "is-on" : "is-off"}`} aria-hidden="true" />
      <span className="lab-badge-text">{label}</span>
    </span>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : String(error);
  return (
    <p className="error-note" role="alert">
      {message}
    </p>
  );
}

export function Confirm({
  open,
  title,
  children,
  confirmLabel,
  danger = false,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) el.showModal?.();
    if (!open && el.open) el.close();
  }, [open]);
  return (
    <dialog ref={ref} className="confirm" aria-labelledby="confirm-title" onClose={onCancel} onCancel={onCancel}>
      <h2 id="confirm-title">{title}</h2>
      <div className="confirm-body">{children}</div>
      <div className="confirm-actions">
        <button type="button" className="btn" onClick={onCancel} autoFocus>
          Cancel
        </button>
        <button type="button" className={`btn ${danger ? "btn-danger" : "btn-primary"}`} onClick={onConfirm}>
          {confirmLabel}
        </button>
      </div>
    </dialog>
  );
}

function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const pad = (n: number) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(m)}:${pad(s % 60)}` : `${pad(m)}:${pad(s % 60)}`;
}

/** Seconds since an epoch-seconds start, ticking once a second. */
export function useSecondsSince(startedAt: number | null | undefined): number {
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => {
    if (!startedAt) return;
    const id = window.setInterval(() => setNow(Date.now() / 1000), 1000);
    return () => window.clearInterval(id);
  }, [startedAt]);
  return startedAt ? Math.max(0, now - startedAt) : 0;
}

export function Elapsed({ startedAt }: { startedAt: number }) {
  const seconds = useSecondsSince(startedAt);
  return (
    <time className="elapsed" dateTime={`PT${Math.floor(seconds)}S`} aria-label={`${Math.floor(seconds / 60)} minutes since the start`}>
      {clock(seconds)}
    </time>
  );
}

export function Countdown({ seconds }: { seconds: number }) {
  return <span className="elapsed">{clock(seconds)}</span>;
}

/** Guide pointers from the API, as links into the Guide pages. */
export function GuideLinks({ refs }: { refs: { pointer: string }[] }) {
  if (!refs.length) return null;
  return (
    <ul className="guide-links">
      {refs.flatMap((r) =>
        resolvePointer(r.pointer, guide).map((p, i) => (
          <li key={`${r.pointer}-${i}`}>
            {p.href ? <Link to={p.href}>{p.subsection ? `${p.section} › ${p.subsection}` : p.section}</Link> : <span>{p.label}</span>}
            {p.note ? <span className="pointer-note"> ({p.note})</span> : null}
          </li>
        )),
      )}
    </ul>
  );
}
