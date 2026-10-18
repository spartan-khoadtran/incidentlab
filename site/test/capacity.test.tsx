// @vitest-environment jsdom
import "./setup-dom.ts";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { LabProvider } from "../src/lib/lab.tsx";
import { Play } from "../src/pages/Play.tsx";
import { MockLab } from "./mock-lab.ts";
import { mockServer } from "./msw.ts";

const { server, state } = mockServer();
const JSON_POST = { "content-type": "application/json", "x-lab-client": "web" };
const AFTER_THE_LAST_STEP = 600;
const STATUS_POLL_MS = 15_000;

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
beforeEach(() => {
  state.lab = new MockLab();
  state.calls.length = 0;
  sessionStorage.clear();
});
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());

function renderCapacity() {
  return render(
    <MemoryRouter initialEntries={["/play?tab=capacity"]}>
      <LabProvider>
        <Play />
      </LabProvider>
    </MemoryRouter>,
  );
}

describe("Load and capacity", () => {
  it("sets the load with the rps it gives", async () => {
    const user = userEvent.setup();
    renderCapacity();
    const slider = (await screen.findByRole("slider", { name: "Load multiplier" })) as HTMLInputElement;
    expect(slider.min).toBe("0.1");
    expect(slider.max).toBe("6");
    fireEvent.change(slider, { target: { value: "1.5" } });
    expect(screen.getByText("75 rps")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Apply" }));
    expect(await screen.findByText(/Now 1.5×, about 75 rps/)).toBeTruthy();
    expect(state.lab.load).toBe(1.5);
  });

  it("refuses a load change while a ramp step runs, and says why", async () => {
    const user = userEvent.setup();
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));
    await screen.findByRole("listitem", { current: "step" });
    fireEvent.change(screen.getByRole("slider", { name: "Load multiplier" }), { target: { value: "1.5" } });
    await user.click(screen.getByRole("button", { name: "Apply" }));
    expect(await screen.findByText(/Mock: a ramp is running for \d+ s more./)).toBeTruthy();
    expect(state.lab.load).toBe(1);
  });

  it("follows a ramp started, answered and cleared in the terminal", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    state.lab.rampStartedAgo = AFTER_THE_LAST_STEP;
    renderCapacity();
    expect(await screen.findByRole("button", { name: "Start the ramp" })).toBeTruthy();

    state.lab.handle("POST", "/api/ramp", JSON_POST, {});
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(await screen.findByText("The ramp is over. Read the charts, then answer.")).toBeTruthy();

    state.lab.handle("POST", "/api/ramp/answer", JSON_POST, { knee_rps: 75, max_rps: 75, resource: "mock_pool" });
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(await screen.findByText("Mock ramp explanation.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Submit" })).toBeNull();

    state.lab.handle("POST", "/api/exercise/clear", JSON_POST, {});
    state.lab.handle("POST", "/api/bench", JSON_POST, {});
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(await screen.findByText("Mock bench explanation.")).toBeTruthy();
  });

  it("sends the ramp's key, so an answer after the ramp was replaced is refused", async () => {
    const user = userEvent.setup();
    state.lab.rampStartedAgo = AFTER_THE_LAST_STEP;
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));
    await user.type(await screen.findByRole("textbox", { name: /^Knee/ }), "75");
    await user.type(screen.getByRole("textbox", { name: "Highest throughput (rps)" }), "75");
    await user.selectOptions(screen.getByRole("combobox", { name: "Resource that filled first" }), "mock_pool");
    state.lab.handle("POST", "/api/exercise/clear", JSON_POST, {});
    state.lab.handle("POST", "/api/ramp", JSON_POST, {});

    await user.click(screen.getByRole("button", { name: "Submit" }));

    expect(await screen.findByText("Mock: the exercise on this page is no longer the open one.")).toBeTruthy();
    expect(state.lab.history).toEqual([]);
    expect(state.lab.active?.answered).toBe(false);
  });

  it("runs a ramp: the current step, and no answer form before the last step", async () => {
    const user = userEvent.setup();
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));
    const now = await screen.findByRole("listitem", { current: "step" });
    expect(now.textContent).toBe("75");
    expect(screen.getByText(/You answer after the last step/)).toBeTruthy();
    expect(screen.queryByRole("textbox", { name: /^Knee/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Submit" })).toBeNull();
    expect(screen.getByRole("button", { name: "Show the result without a score" })).toBeTruthy();
  });

  it("answers a finished ramp and shows the scored table", async () => {
    const user = userEvent.setup();
    state.lab.rampStartedAgo = AFTER_THE_LAST_STEP;
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));
    expect(await screen.findByText("The ramp is over. Read the charts, then answer.")).toBeTruthy();
    await user.type(screen.getByRole("textbox", { name: /^Knee/ }), "75");
    await user.type(screen.getByRole("textbox", { name: "Highest throughput (rps)" }), "75");
    await user.selectOptions(screen.getByRole("combobox", { name: "Resource that filled first" }), "mock_pool");
    await user.type(screen.getByRole("textbox", { name: /how you read the knee/ }), "p99 bent at step 2");
    await user.click(screen.getByRole("button", { name: "Submit" }));
    expect(await screen.findByText("Mock ramp explanation.")).toBeTruthy();
    expect(state.lab.rampNote).toBe("p99 bent at step 2");
    expect(screen.getByText("310 ms")).toBeTruthy();
    expect(screen.getByText("0.4%")).toBeTruthy();
    expect(screen.getByText(/Mock resource: the pool \(missed\)/)).toBeTruthy();
  });

  it("offers only the result for a ramp revealed in the terminal", async () => {
    const user = userEvent.setup();
    state.lab.rampStartedAgo = AFTER_THE_LAST_STEP;
    state.lab.handle("POST", "/api/ramp", JSON_POST, {});
    state.lab.handle("POST", "/api/ramp/reveal", JSON_POST, {});
    renderCapacity();
    const show = await screen.findByRole("button", { name: "Show the result" });
    expect(screen.queryByRole("button", { name: "Submit" })).toBeNull();
    expect(screen.queryByRole("textbox", { name: /^Knee/ })).toBeNull();
    await user.click(show);
    expect(await screen.findByText("revealed, not scored")).toBeTruthy();
  });

  it("runs a bench and shows the minutes before against the bench", async () => {
    const user = userEvent.setup();
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the bench" }));
    expect(await screen.findByText("Mock bench explanation.")).toBeTruthy();
    expect(screen.getByText("Clients: p99")).toBeTruthy();
    expect(screen.getByText("120 ms")).toBeTruthy();
    await userEvent.setup().click(screen.getByRole("button", { name: "Save to history and go back" }));
    expect(await screen.findByRole("button", { name: "Start the bench" })).toBeTruthy();
    expect(state.lab.history.map((e) => e.mode)).toEqual(["bench"]);
    expect(state.calls.some((c) => c.path === "/api/exercise/clear")).toBe(false);
  });

  it("keeps the bench report when the save fails, and saves on a retry", async () => {
    const user = userEvent.setup();
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the bench" }));
    await screen.findByText("Mock bench explanation.");
    state.lab.failNext.set("POST /bench/report", { status: 503, error: "prometheus_down", message: "Mock: Prometheus did not answer." });
    await user.click(screen.getByRole("button", { name: "Save to history and go back" }));
    expect(await screen.findByText("Mock: Prometheus did not answer.")).toBeTruthy();
    expect(screen.getByText("Mock bench explanation.")).toBeTruthy();
    expect(state.lab.history).toEqual([]);
    expect(state.calls.some((c) => c.path === "/api/exercise/clear")).toBe(false);
    await user.click(screen.getByRole("button", { name: "Try to save again" }));
    expect(await screen.findByRole("button", { name: "Start the bench" })).toBeTruthy();
    expect(state.lab.history.map((e) => e.mode)).toEqual(["bench"]);
  });

  it("tries the bench report again after an error", async () => {
    const user = userEvent.setup();
    state.lab.failNext.set("GET /bench/report", { status: 503, error: "prometheus_down", message: "Mock: Prometheus did not answer." });
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the bench" }));
    expect(await screen.findByText("Mock: Prometheus did not answer.")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Try again now" }));
    expect(await screen.findByText("Mock bench explanation.")).toBeTruthy();
  });
});
