import { lab } from "./data";

export interface ModeInfo {
  id: string;
  name: string;
  target: string;
  trains: string;
  time: string;
  lab: boolean;
  score: string;
  doc: string | null;
}

const doc = (id: string) => lab.modes.find((m) => m.id === id)?.guide ?? null;
const rampMinutes = Math.round((lab.defaults.ramp_rates.length * lab.defaults.ramp_step_seconds) / 60);
const benchMinutes = Math.round(lab.defaults.bench_seconds / 60);

export const MODES: ModeInfo[] = [
  {
    id: "incident",
    name: "Incident",
    target: "new",
    trains: "The whole investigation on a live system: read a ticket, find the layer, the signal that proves it and the cause.",
    time: "10 to 30 min",
    lab: true,
    score: `0 to ${lab.scoring.max_points} points, kept in make history`,
    doc: doc("incident"),
  },
  {
    id: "toolbox",
    name: "Toolbox",
    target: "toolbox",
    trains: "The same incident with no Grafana. You prove the cause from a root shell with vmstat, pidstat, the cgroup files, psql and bpftrace.",
    time: "Like an incident",
    lab: true,
    score: `0 to ${lab.scoring.max_points} points, like an incident`,
    doc: doc("toolbox"),
  },
  {
    id: "quiz",
    name: "Quiz",
    target: "quiz",
    trains: "The ideas and the math under the guide: Little's Law, queueing, tail latency, what each metric means.",
    time: "About 1 min per question",
    lab: false,
    score: "Right answers out of the questions asked, not saved",
    doc: null,
  },
  {
    id: "ramp",
    name: "Ramp",
    target: "ramp",
    trains: `Capacity: the load climbs from ${lab.defaults.ramp_rates[0]} to ${lab.defaults.ramp_rates[lab.defaults.ramp_rates.length - 1]} rps in ${lab.defaults.ramp_rates.length} steps. You find the knee, the top throughput and the resource that filled first.`,
    time: `About ${rampMinutes} min at STEP=${lab.defaults.ramp_step_seconds}`,
    lab: true,
    score: "Scored from what Prometheus recorded",
    doc: doc("ramp"),
  },
  {
    id: "bench",
    name: "Bench",
    target: "bench",
    trains: `Closed-loop clients (${lab.defaults.bench_clients} by default) and a report of the minutes before against the bench. It shows why a simple benchmark reports a better p99 than users get.`,
    time: `${benchMinutes} min of load, then the report`,
    lab: true,
    score: "Not scored",
    doc: doc("bench"),
  },
];
