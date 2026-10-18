import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { api, type Status } from "./api";

export type Online = "checking" | "online" | "offline";

interface LabState {
  online: Online;
  status: Status | null;
  refresh: () => Promise<void>;
}

const LabContext = createContext<LabState>({ online: "checking", status: null, refresh: async () => {} });

const OFFLINE_POLL_MS = 5_000;
const ONLINE_POLL_MS = 15_000;

/** Polls GET /api/health. The handbook works without the lab; the pages that drive it read `online`. */
export function LabProvider({ children }: { children: ReactNode }) {
  const [online, setOnline] = useState<Online>("checking");
  const [status, setStatus] = useState<Status | null>(null);
  const onlineRef = useRef<Online>("checking");

  const refresh = useCallback(async () => {
    try {
      await api.health();
      const next = await api.status();
      onlineRef.current = "online";
      setOnline("online");
      setStatus(next);
    } catch {
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
      if (live) timer = window.setTimeout(tick, onlineRef.current === "online" ? ONLINE_POLL_MS : OFFLINE_POLL_MS);
    };
    void tick();
    return () => {
      live = false;
      window.clearTimeout(timer);
    };
  }, [refresh]);

  return <LabContext.Provider value={{ online, status, refresh }}>{children}</LabContext.Provider>;
}

export function useLab(): LabState {
  return useContext(LabContext);
}
