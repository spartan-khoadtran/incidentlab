import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, type SettleStatus } from "./api";

export const SETTLE_POLL_MS = 3000;

/**
 * Runs a start. While the lab answers 409 lab_settling, it holds the status, polls the gate and runs
 * the start again once the lab is back to normal or the gate gave up. `start` gets `retry` true on
 * those later posts, so a caller can drop `force` and never clear an exercise opened meanwhile.
 */
export function useSettledStart<T>(onStarted: (value: T) => void, onError: (e: unknown) => void) {
  const [waiting, setWaiting] = useState<SettleStatus | null>(null);
  const pending = useRef<object | null>(null);
  const timer = useRef<number | null>(null);
  const wake = useRef<(() => void) | null>(null);

  const stop = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    pending.current = null;
    wake.current?.();
    wake.current = null;
    setWaiting(null);
  }, []);

  useEffect(() => stop, [stop]);

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
        try {
          const value = await start(retry);
          if (!live()) return;
          stop();
          onStarted(value);
          return;
        } catch (e) {
          if (!live()) return;
          if (!(e instanceof ApiError && e.code === "lab_settling")) {
            stop();
            onError(e);
            return;
          }
          setWaiting((e.body.settle as SettleStatus | undefined) ?? null);
        }
        for (;;) {
          await pause();
          if (!live()) return;
          let status: SettleStatus;
          try {
            status = await api.settle();
          } catch (e) {
            if (live()) {
              stop();
              onError(e);
            }
            return;
          }
          if (!live()) return;
          setWaiting(status);
          if (status.settled || status.gave_up) break;
        }
      }
    },
    [onStarted, onError, stop],
  );

  return { run, waiting, cancel: stop };
}
