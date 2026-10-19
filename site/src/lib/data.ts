import labJson from "../data/lab.json";

export interface Scenario {
  id: string;
  title: string;
  level: number;
  topics: string[];
  toolbox: boolean;
}

export interface Mode {
  id: string;
  guide: string;
  guide_file: string | null;
}

export interface QuizFile {
  file: string;
  numbers: number;
  concepts: number;
}

export interface Lab {
  scenarios: Scenario[];
  topics: string[];
  levels: number[];
  modes: Mode[];
  quiz: QuizFile[];
  scoring: {
    nature_points: number;
    location_points: number;
    signal_points: number;
    cause_points: number;
    partial_cause_points: number;
    hint_penalty: number;
    max_points: number;
    ramp_max_points: number;
  };
  tick_seconds: number;
  recent_window: number;
  defaults: {
    base_rps: number;
    ramp_rates: number[];
    ramp_step_seconds: number;
    bench_clients: number;
    bench_seconds: number;
    quiz_questions: number;
  };
  limits: Record<"load_multiplier" | "ramp_step_seconds" | "bench_clients" | "bench_seconds", { min: number; max: number }>;
  counts: { scenarios: number; toolbox: number; causes: number; signals: number; numbers: number; concepts: number };
}

export const lab = labJson as unknown as Lab;
