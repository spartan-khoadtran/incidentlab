import type { ReactNode } from "react";
import { Link } from "react-router";
import { guide } from "../lib/guide";
import { resolvePointer } from "../lib/pointers";

export function PageHead({ kicker, title, children }: { kicker: string; title: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-head">
      <p className="kicker">{kicker}</p>
      <h1>{title}</h1>
      {children ? <div className="page-lede">{children}</div> : null}
    </header>
  );
}

export function Level({ level }: { level: number }) {
  return (
    <span className={`level level-${level}`} title={`Level ${level}`}>
      <span className="level-led" aria-hidden="true" />
      <span className="level-text">L{level}</span>
    </span>
  );
}

/** A `Section > Subsection` pointer as links into the Guide, in the same words the CLI prints. */
export function Pointer({ doc, compact = false }: { doc: string; compact?: boolean }) {
  const parts = resolvePointer(doc, guide);
  return (
    <span className={`pointer-links ${compact ? "is-compact" : ""}`}>
      {parts.map((p, i) => (
        <span key={`${p.label}-${i}`} className="pointer-part">
          {p.href ? (
            <Link to={p.href}>
              {p.subsection ? (
                <>
                  <span className="pointer-section">{p.section}</span>
                  <span className="pointer-sep" aria-hidden="true">
                    {" › "}
                  </span>
                  {p.subsection}
                </>
              ) : (
                p.section
              )}
            </Link>
          ) : (
            <span>{p.label}</span>
          )}
          {p.note && !compact ? <span className="pointer-note"> ({p.note})</span> : null}
        </span>
      ))}
    </span>
  );
}

/** Text from the Makefile or a docs line, with its `code` spans and NAME=value options set as code. */
export function Rich({ text }: { text: string }) {
  const parts = text.split(/(`[^`]+`|\b[A-Z][A-Z_]*=[^\s,)]+)/g);
  return (
    <>
      {parts.map((p, i) => (p.startsWith("`") ? <code key={i}>{p.slice(1, -1)}</code> : /^[A-Z][A-Z_]*=/.test(p) ? <code key={i}>{p}</code> : <span key={i}>{p}</span>))}
    </>
  );
}

export function Stat({ value, label }: { value: ReactNode; label: string }) {
  return (
    <div className="stat">
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
    </div>
  );
}
