// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { api, ApiError, parseNumber, rampResources } from "../src/lib/api.ts";
import { dashboardUrl, exerciseRange, promqlUrl } from "../src/lib/grafana.ts";
import { MockLab } from "./mock-lab.ts";
import { mockServer } from "./msw.ts";

const { server, state } = mockServer();

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
beforeEach(() => {
  state.lab = new MockLab();
  state.calls.length = 0;
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("api client", () => {
  it("sends Content-Type and X-Lab-Client on every POST, and neither on a GET", async () => {
    await api.health();
    await api.start({ mode: "incident" });
    await api.hint();
    const get = state.calls.find((c) => c.method === "GET")!;
    expect(get.headers["x-lab-client"]).toBeNull();
    for (const c of state.calls.filter((c) => c.method === "POST")) {
      expect(c.headers).toEqual({ "content-type": "application/json", "x-lab-client": "web" });
    }
  });

  it("turns an error body into an ApiError with its code and message", async () => {
    await api.start({});
    await expect(api.start({})).rejects.toMatchObject({ status: 409, code: "exercise_open" });
    const view = await api.start({ force: true });
    expect(view.n).toBe(2);
  });

  it("reports the lab as offline when the proxy answers with HTML", async () => {
    state.lab.online = false;
    const error = await api.health().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("offline");
  });

  it("keeps the ActiveView free of the scenario until the answer", async () => {
    const view = await api.start({});
    expect(JSON.stringify(view)).not.toContain("mock_fault");
    const result = await api.answer({ nature: "mock_arch", location: "mock_lb", signal: "mock_gap", cause: "mock_retry" });
    expect(result.verdicts.cause).toBe("partial");
    expect(result.score).toBe(4);
    expect((await api.exercise()).reveal?.id).toBe("mock_fault");
  });
});

describe("helpers", () => {
  it("reads a number with a comma or a dot, and refuses words", () => {
    expect(parseNumber("12,5")).toBe(12.5);
    expect(parseNumber(" 0.25 ")).toBe(0.25);
    expect(parseNumber("1e3")).toBe(1000);
    expect(parseNumber("ten")).toBeNull();
    expect(parseNumber("")).toBeNull();
  });

  it("accepts the ramp resources as a list or wrapped in an object", () => {
    const o = [{ key: "pool", label: "Pool" }];
    expect(rampResources(o)).toEqual(o);
    expect(rampResources({ resources: o })).toEqual(o);
  });

  it("builds a Grafana 11 Explore link with the Prometheus datasource and the exercise range", () => {
    const range = exerciseRange(1_700_000_900);
    expect(range).toEqual({ from: "1700000000000", to: "now" });
    const url = new URL(promqlUrl("http://localhost:3000/", "sum(rate(x_total[1m]))", range)!);
    expect(url.pathname).toBe("/explore");
    expect(url.searchParams.get("schemaVersion")).toBe("1");
    const panes = JSON.parse(url.searchParams.get("panes")!);
    const pane = Object.values(panes)[0] as { datasource: string; queries: { expr: string; datasource: { uid: string; type: string } }[]; range: unknown };
    expect(pane.datasource).toBe("prometheus");
    expect(pane.queries[0]).toMatchObject({ refId: "A", expr: "sum(rate(x_total[1m]))", datasource: { uid: "prometheus", type: "prometheus" } });
    expect(pane.range).toEqual(range);
    expect(dashboardUrl("http://localhost:3000", "red-layers", range)).toBe("http://localhost:3000/d/red-layers?orgId=1&from=1700000000000&to=now");
  });
});
