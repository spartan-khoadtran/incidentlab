import { useSearchParams } from "react-router";
import { PageHead } from "../components/Bits";
import { LabBadge, LabGate } from "../components/LabUi";
import { CapacityTab } from "./play/CapacityTab";
import { ExerciseTab } from "./play/ExerciseTab";
import { HistoryTab } from "./play/HistoryTab";

const TABS = [
  { id: "exercise", label: "Exercise" },
  { id: "capacity", label: "Load and capacity" },
  { id: "history", label: "History" },
] as const;

type Tab = (typeof TABS)[number]["id"];

export function Play() {
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.some((t) => t.id === params.get("tab")) ? (params.get("tab") as Tab) : "exercise";

  return (
    <div className="page play">
      <PageHead kicker="Play" title="Run the lab from here">
        <p>
          The same loop as <code>make new</code>, <code>make hint</code> and <code>make answer</code>, with the dashboards one click away. Both ways share one state: start here, answer in the terminal, or the other way round.
        </p>
      </PageHead>
      <div className="tabs-row">
        <div className="tabs" role="tablist" aria-label="Play">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              id={`tab-${t.id}`}
              aria-selected={tab === t.id}
              aria-controls={`panel-${t.id}`}
              className="tab"
              onClick={() => setParams(t.id === "exercise" ? {} : { tab: t.id }, { replace: true })}
            >
              {t.label}
            </button>
          ))}
        </div>
        <LabBadge />
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tab-panel">
        <LabGate what={tab === "history" ? "Your history lives in the lab's state volume." : "The exercise runs on the live lab."}>
          {tab === "exercise" ? <ExerciseTab /> : tab === "capacity" ? <CapacityTab /> : <HistoryTab />}
        </LabGate>
      </div>
    </div>
  );
}
