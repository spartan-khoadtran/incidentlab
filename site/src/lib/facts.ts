import { FACTS_MAX } from "./topology";

interface ServiceFacts {
  port: string | null;
  ports: { container: string }[];
  launcher: string | null;
  image: string | null;
  cpus: string | null;
  memLimit: string | null;
}

/** One short line per diagram box: port or image, CPU limit, memory limit, cut to what fits. */
export function facts(s: ServiceFacts | undefined): string {
  if (!s) return "";
  const port = s.port ?? s.ports[0]?.container ?? null;
  const image = s.launcher ? null : (s.image?.split("/").pop() ?? null);
  const parts = [port ? `:${port}` : image, s.cpus ? `${s.cpus} cpu` : null, s.memLimit].filter((p): p is string => Boolean(p));
  while (parts.length > 1 && parts.join(" · ").length > FACTS_MAX) parts.pop();
  const line = parts.join(" · ");
  return line.length > FACTS_MAX ? `${line.slice(0, FACTS_MAX - 1)}…` : line;
}
