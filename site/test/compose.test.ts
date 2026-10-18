import { describe, expect, it } from "vitest";
import { parseCompose, portOf } from "../scripts/lib/compose.ts";
import { EDGES, NODES } from "../src/lib/topology.ts";
import { repoFile } from "./paths.ts";

const SAMPLE = `name: demo
x-svc: &svc
  image: demo-svc:latest
  environment: &env
    OTLP: \${OTLP:-http://tempo:4318}

services:
  # ---------------------------------------------- the system under test
  api:
    <<: *svc
    environment:
      <<: *env
      SERVICE_LAUNCHER: /run/app/api/bin/api
      PORT: "8000"
    cpus: "2.0"
    mem_limit: 512m
    volumes:
      - ./config/api.yaml:/etc/api.yaml:ro

  # A batch job.
  # It wakes up on demand.
  noisy:
    <<: *svc
    cpu_shares: 65536

  # ---------------------------------------------- observability
  grafana:
    image: grafana/grafana:11.6.0
    ports:
      - "3000:3000"

volumes:
  data:
`;

describe("parseCompose", () => {
  const data = parseCompose(SAMPLE);
  const byName = Object.fromEntries(data.services.map((s) => [s.name, s]));

  it("resolves the merge keys and the default of a variable", () => {
    expect(byName.api.image).toBe("demo-svc:latest");
    expect(byName.api.launcher).toBe("/run/app/api/bin/api");
    expect(byName.api.env.OTLP).toBe("http://tempo:4318");
    expect(byName.api.port).toBe("8000");
  });

  it("reads limits, ports and config mounts", () => {
    expect(byName.api.cpus).toBe("2.0");
    expect(byName.api.memLimit).toBe("512m");
    expect(byName.api.configMounts).toEqual([{ source: "config/api.yaml", target: "/etc/api.yaml" }]);
    expect(byName.noisy.cpuShares).toBe(65536);
    expect(byName.grafana.ports).toEqual([{ host: "3000", container: "3000", ip: null }]);
  });

  it("reads every port form compose takes", () => {
    expect(portOf("3000")).toEqual({ host: "3000", container: "3000", ip: null });
    expect(portOf("4000:80")).toEqual({ host: "4000", container: "80", ip: null });
    expect(portOf("127.0.0.1:4000:80")).toEqual({ host: "4000", container: "80", ip: "127.0.0.1" });
    expect(portOf("[::1]:9090:9090/tcp")).toEqual({ host: "9090", container: "9090", ip: "[::1]" });
    expect(portOf({ target: 80, published: "4000", host_ip: "127.0.0.1" })).toEqual({ host: "4000", container: "80", ip: "127.0.0.1" });
  });

  it("reads the real host ports, bound to 127.0.0.1", () => {
    const real = parseCompose(repoFile("docker-compose.yml"));
    const site = real.services.find((s) => s.name === "site");
    expect(site?.ports).toEqual([{ host: "4000", container: "80", ip: "127.0.0.1" }]);
    const published = real.services.flatMap((s) => s.ports);
    expect(published.length).toBeGreaterThan(0);
    expect(published.filter((p) => p.ip !== "127.0.0.1")).toEqual([]);
  });

  it("keeps the group comments and the comment above a service", () => {
    expect(data.groups).toEqual(["the system under test", "observability"]);
    expect(byName.noisy.group).toBe("the system under test");
    expect(byName.noisy.note).toBe("A batch job. It wakes up on demand.");
    expect(byName.grafana.group).toBe("observability");
    expect(data.volumes).toEqual(["data"]);
  });

  it("finds every node of the request path diagram in the real docker-compose.yml", () => {
    const real = parseCompose(repoFile("docker-compose.yml"));
    const names = new Set(real.services.map((s) => s.name));
    expect(NODES.filter((n) => !names.has(n.id))).toEqual([]);
  });

  it("draws only hops that docker-compose.yml and envoy.yaml agree with", () => {
    const real = parseCompose(repoFile("docker-compose.yml"));
    for (const edge of EDGES.filter((e) => e.evidence)) {
      const svc = real.services.find((s) => s.name === edge.evidence!.service);
      expect(svc?.env[edge.evidence!.env], `${edge.from} -> ${edge.to}`).toContain(edge.to);
    }
    expect(repoFile("config/envoy.yaml")).toMatch(/address: api, port_value: 8000/);
  });
});
