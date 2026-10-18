import { useEffect, useRef, useState } from "react";
import { copyText } from "../lib/copy";
import { CheckIcon, CopyIcon } from "./Icons";

export function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <button
      type="button"
      className={`copy ${done ? "is-done" : ""}`}
      aria-label={`${label}: ${text}`}
      onClick={async () => {
        await copyText(text);
        setDone(true);
        window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => setDone(false), 1400);
      }}
    >
      {done ? <CheckIcon /> : <CopyIcon />}
    </button>
  );
}

/** One shell line with a prompt and a copy button. */
export function Cmd({ text, note }: { text: string; note?: string }) {
  return (
    <div className="cmd">
      <code className="cmd-text">
        <span className="cmd-prompt" aria-hidden="true">
          $
        </span>
        {text}
      </code>
      {note ? <span className="cmd-note">{note}</span> : null}
      <CopyButton text={text} />
    </div>
  );
}
