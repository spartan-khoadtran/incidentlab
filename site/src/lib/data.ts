import composeJson from "../generated/compose.json";
import guideJson from "../generated/guide.json";
import highlightsJson from "../generated/highlights.json";
import makeJson from "../generated/make.json";
import repoJson from "../generated/repo.json";
import labJson from "../data/lab.json";

export interface Scenario {
  id: string;
  title: string;
  level: number;
  topics: string[];
  doc: string;
  doc_file: string | null;
  toolbox: boolean;
}

export interface Mode {
  id: string;
  doc: string;
  doc_file: string | null;
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
  counts: { scenarios: number; toolbox: number; causes: number; signals: number; numbers: number; concepts: number };
}

export interface Heading {
  depth: number;
  raw: string;
  text: string;
  id: string;
  section: string | null;
}

export interface GuideDoc {
  slug: string;
  file: string;
  group: string;
  order: number;
  title: string;
  lede: string;
  note: string | null;
  words: number;
  headings: Heading[];
}

export interface MakeOption {
  name: string;
  example: string | null;
}

export interface MakeTarget {
  name: string;
  help: string;
  options: MakeOption[];
}

export interface MakeGroup {
  id: string;
  title: string;
  note: string | null;
  targets: MakeTarget[];
}

export interface ComposeService {
  name: string;
  group: string;
  note: string | null;
  image: string | null;
  build: string | null;
  launcher: string | null;
  command: string | null;
  port: string | null;
  ports: { host: string; container: string }[];
  cpus: string | null;
  memLimit: string | null;
  cpuShares: number | null;
  profiles: string[];
  dependsOn: string[];
  configMounts: { source: string; target: string }[];
  env: Record<string, string>;
  privileged: boolean;
  pidHost: boolean;
  capAdd: string[];
}

export interface Repo {
  readme: { lede: string; trains: string[]; layout: { path: string; note: string }[] };
  gradle: { name: string; note: string; modules: { path: string; dir: string; note: string | null }[] }[];
  files: { readme: string | null; claude: string | null; makefile: string | null };
}

export const lab = labJson as unknown as Lab;
export const guide = guideJson as unknown as GuideDoc[];
export const make = makeJson as unknown as { groups: MakeGroup[]; links: { label: string; value: string }[] };
export const compose = composeJson as unknown as { project: string; groups: string[]; services: ComposeService[]; volumes: string[] };
export const repo = repoJson as unknown as Repo;
export const highlights = highlightsJson as unknown as { inThisLab: string | null };

export function target(name: string): MakeTarget | undefined {
  return make.groups.flatMap((g) => g.targets).find((t) => t.name === name);
}

export function service(name: string): ComposeService | undefined {
  return compose.services.find((s) => s.name === name);
}

export function docBySlug(slug: string): GuideDoc | undefined {
  return guide.find((d) => d.slug === slug);
}

export function docByFile(file: string | null): GuideDoc | undefined {
  return file ? guide.find((d) => d.file === file) : undefined;
}
