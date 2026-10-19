import composeJson from "../generated/compose.json";

export interface ComposeService {
  name: string;
  group: string;
  note: string | null;
  image: string | null;
  build: string | null;
  launcher: string | null;
  command: string | null;
  port: string | null;
  ports: { host: string; container: string; ip: string | null }[];
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

export const compose = composeJson as unknown as { project: string; groups: string[]; services: ComposeService[]; volumes: string[] };

export function service(name: string): ComposeService | undefined {
  return compose.services.find((s) => s.name === name);
}
