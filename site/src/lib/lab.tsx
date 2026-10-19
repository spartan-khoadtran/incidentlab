import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { api, type Status } from "./api";

export type Online = "checking" | "online" | "offline";

interface LabState {
  online: Online;
  /** True once the lab answered on this page, so a page can keep its work through an outage. */
  seen: boolean;
  status: Status | null;
  refresh: () => Promise<void>;
}

const LabContext = createContext<LabState>({ online: "checking", seen: false, status: null, refresh: async () => {} });

const OFFLINE_POLL_MS = 5_000;
const ONLINE_POLL_MS = 15_000;
const RETRY_MS = 2_000;
/** A lab that was up goes offline only after this many failed polls in a row, so one dropped call does not wipe a page. */
const FAILURES_TO_OFFLINE = 2;

/** Polls GET /api/health. The handbook works without the lab; the pages that drive it read `online`. */
export function LabProvider({ children }: { children: ReactNode }) {
  const [online, setOnline] = useState<Online>("checking");
  const [status, setStatus] = useState<Status | null>(null);
  const [seen, setSeen] = useState(false);
  const onlineRef = useRef<Online>("checking");
  const failures = useRef(0);

  const refresh = useCallback(async () => {
    try {
      await api.health();
      const next = await api.status();
      failures.current = 0;
      onlineRef.current = "online";
      setOnline("online");
      setSeen(true);
      setStatus(next);
    } catch {
      failures.current += 1;
      if (onlineRef.current === "online" && failures.current < FAILURES_TO_OFFLINE) return;
      onlineRef.current = "offline";
      setOnline("offline");
      setStatus(null);
    }
  }, []);

  useEffect(() => {
    let live = true;
    let timer: number | undefined;
    const tick = async () => {
      await refresh();
      const wait = onlineRef.current !== "online" ? OFFLINE_POLL_MS : failures.current > 0 ? RETRY_MS : ONLINE_POLL_MS;
      if (live) timer = window.setTimeout(tick, wait);
    };
    void tick();
    return () => {
      live = false;
      window.clearTimeout(timer);
    };
  }, [refresh]);

  return <LabContext.Provider value={{ online, seen, status, refresh }}>{children}</LabContext.Provider>;
}

export function useLab(): LabState {
  return useContext(LabContext);
}
