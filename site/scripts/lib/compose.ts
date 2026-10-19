import { parse } from "yaml";

export interface ComposePort {
  host: string;
  container: string;
  /** The host address the port binds to, when compose names one. */
  ip: string | null;
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
  ports: ComposePort[];
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

export interface ComposeData {
  project: string;
  groups: string[];
  services: ComposeService[];
  volumes: string[];
}

type Raw = Record<string, unknown>;

const GROUP = /^ {2}# -{5,}\s*(.+)$/;
const SERVICE = /^ {2}([a-z0-9][a-z0-9_-]*):\s*$/;
const COMMENT = /^ {2}#\s?(.*)$/;

/** Comment groups and the comment block above each service, which the YAML parser drops. */
function scanLayout(text: string): Map<string, { group: string; note: string | null }> {
  const out = new Map<string, { group: string; note: string | null }>();
  let inServices = false;
  let group = "services";
  let pending: string[] = [];
  for (const line of text.split("\n")) {
    if (/^services:\s*$/.test(line)) {
      inServices = true;
      continue;
    }
    if (inServices && /^\S/.test(line)) break;
    if (!inServices) continue;
    const g = line.match(GROUP);
    if (g) {
      group = g[1].trim();
      pending = [];
      continue;
    }
    const c = line.match(COMMENT);
    if (c) {
      pending.push(c[1].trim());
      continue;
    }
    const s = line.match(SERVICE);
    if (s) {
      out.set(s[1], { group, note: pending.length ? pending.join(" ") : null });
      pending = [];
      continue;
    }
    if (line.trim() !== "" && !line.startsWith("    ")) pending = [];
  }
  return out;
}

function str(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  return String(value);
}

function commandOf(value: unknown): string | null {
  if (Array.isArray(value)) return value.map(String).join(" ");
  return str(value);
}

function envOf(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object") return {};
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(value as Raw)) {
    if (k === "<<") continue;
    out[k] = String(v).replace(/^\$\{[A-Z_]+:-(.*)\}$/, "$1");
  }
  return out;
}

const SHORT_PORT = /^(?:(\[[^\]]+\]|[^:]+):)??(?:(\d+):)?(\d+)(?:\/\w+)?$/;

/** Short form `[ip:][host:]container[/proto]`, or the long form with `published`, `target` and `host_ip`. */
export function portOf(value: unknown): ComposePort | null {
  if (value && typeof value === "object") {
    const p = value as Raw;
    if (p.target === undefined) return null;
    const container = String(p.target);
    return { host: p.published === undefined ? container : String(p.published), container, ip: p.host_ip === undefined ? null : String(p.host_ip) };
  }
  const m = String(value).match(SHORT_PORT);
  if (!m) return null;
  const [, ip, host, container] = m;
  return { host: host ?? container, container, ip: ip ?? null };
}

function portsOf(value: unknown): ComposePort[] {
  if (!Array.isArray(value)) return [];
  return value.map(portOf).filter((p): p is ComposePort => p !== null);
}

function dependsOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String);
  if (value && typeof value === "object") return Object.keys(value);
  return [];
}

function mountsOf(service: Raw): { source: string; target: string }[] {
  const volumes = Array.isArray(service.volumes) ? service.volumes.map(String) : [];
  return volumes
    .filter((v) => v.startsWith("./config/"))
    .map((v) => {
      const [source, target] = v.split(":");
      return { source: source.replace(/^\.\//, ""), target };
    });
}

export function parseCompose(text: string): ComposeData {
  const doc = parse(text, { merge: true, maxAliasCount: -1 }) as Raw;
  const layout = scanLayout(text);
  const services = Object.entries(doc.services as Record<string, Raw>).map(([name, s]): ComposeService => {
    const env = envOf(s.environment);
    const place = layout.get(name) ?? { group: "services", note: null };
    const build = s.build === undefined ? null : typeof s.build === "string" ? s.build : str((s.build as Raw).context);
    return {
      name,
      group: place.group,
      note: place.note,
      image: str(s.image),
      build: build?.replace(/^\.\//, "") ?? null,
      launcher: env.SERVICE_LAUNCHER ?? null,
      command: commandOf(s.command),
      port: env.PORT ?? null,
      ports: portsOf(s.ports),
      cpus: str(s.cpus),
      memLimit: str(s.mem_limit),
      cpuShares: typeof s.cpu_shares === "number" ? s.cpu_shares : null,
      profiles: Array.isArray(s.profiles) ? s.profiles.map(String) : [],
      dependsOn: dependsOf(s.depends_on),
      configMounts: mountsOf(s),
      env,
      privileged: s.privileged === true,
      pidHost: s.pid === "host",
      capAdd: Array.isArray(s.cap_add) ? s.cap_add.map(String) : [],
    };
  });
  const groups = [...new Set(services.map((s) => s.group))];
  return { project: str(doc.name) ?? "", groups, services, volumes: Object.keys((doc.volumes as Raw) ?? {}) };
}
