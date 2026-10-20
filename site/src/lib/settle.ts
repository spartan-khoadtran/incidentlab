import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, type SettleStatus } from "./api";

export const SETTLE_POLL_MS = 3000;
const MAX_POLL_FAILURES = 3;

/** The lab not answering or a 5xx can pass, so the wait goes on. A 4xx will not change by asking again. */
function passing(e: unknown): boolean {
  return e instanceof ApiError && (e.code === "offline" || e.status === 0 || e.status >= 500);
}

/** Moves focus to `el` only when nothing holds it, as after the settle card or a start button left the page. */
export function focusIfLost(el: HTMLElement | null) {
  const active = document.activeElement;
  if (el && (!active || active === document.body || !active.isConnected)) el.focus();
}

/**
 * Runs a start. While the lab answers 409 lab_settling, it holds the status, polls the gate and runs
 * the start again once the lab is back to normal or the gate gave up. `start` gets `retry` true on
 * those later posts, so a caller can drop `force` and never clear an exercise opened meanwhile.
 * `onWaiting` runs on the first lab_settling of a start: a forced start has cleared the open exercise by then.
 * A start the lab accepted always reaches `onStarted` while the page is mounted, even after a cancel.
 */
export function useSettledStart<T>(onStarted: (value: T) => void, onError: (e: unknown) => void, onWaiting?: () => void) {
  const [waiting, setWaiting] = useState<SettleStatus | null>(null);
  const [isWaiting, setIsWaiting] = useState(false);
  const [posting, setPosting] = useState(false);
  const pending = useRef<object | null>(null);
  const mounted = useRef(false);
  const timer = useRef<number | null>(null);
  const wake = useRef<(() => void) | null>(null);

  const stop = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    pending.current = null;
    wake.current?.();
    wake.current = null;
    setWaiting(null);
    setIsWaiting(false);
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      stop();
    };
  }, [stop]);

  const run = useCallback(
    async (start: (retry: boolean) => Promise<T>) => {
      const token = {};
      pending.current = token;
      const live = () => pending.current === token;
      const pause = () =>
        new Promise<void>((resolve) => {
          wake.current = resolve;
          timer.current = window.setTimeout(resolve, SETTLE_POLL_MS);
        });
      for (let retry = false; ; retry = true) {
        setPosting(true);
        let outcome: { value: T } | { error: unknown };
        try {
          outcome = { value: await start(retry) };
        } catch (error) {
          outcome = { error };
        }
        if (!mounted.current) return;
        setPosting(false);
        if ("value" in outcome) {
          if (live()) stop();
          onStarted(outcome.value);
          return;
        }
        const e = outcome.error;
        if (!live()) return;
        if (!(e instanceof ApiError && e.code === "lab_settling")) {
          stop();
          onError(e);
          return;
        }
        if (!retry) onWaiting?.();
        setIsWaiting(true);
        setWaiting((e.body.settle as SettleStatus | undefined) ?? null);
        for (let failures = 0; ; ) {
          await pause();
          if (!live()) return;
          let status: SettleStatus;
          try {
            status = await api.settle();
            failures = 0;
          } catch (e) {
            if (!live()) return;
            failures += 1;
            if (passing(e) && failures < MAX_POLL_FAILURES) continue;
            stop();
            onError(e);
            return;
          }
          if (!live()) return;
          setWaiting(status);
          if (status.settled || status.gave_up) break;
        }
      }
    },
    [onStarted, onError, onWaiting, stop],
  );

  return { run, waiting, isWaiting, posting, cancel: stop };
}
