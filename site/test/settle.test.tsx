// @vitest-environment jsdom
import "./setup-dom.ts";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { LabProvider } from "../src/lib/lab.tsx";
import { exerciseRange } from "../src/lib/grafana.ts";
import { SETTLE_POLL_MS } from "../src/lib/settle.ts";
import { Play } from "../src/pages/Play.tsx";
import { MockLab } from "./mock-lab.ts";
import { mockServer } from "./msw.ts";

const { server, state } = mockServer();

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
beforeEach(() => {
  state.lab = new MockLab();
  state.calls.length = 0;
});
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());

function renderPlay(path = "/play") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LabProvider>
        <Play />
      </LabProvider>
    </MemoryRouter>,
  );
}

describe("settle gate", () => {
  it("waits for the lab to settle, lists the checks, then starts the exercise by itself", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 2;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));

    const card = await screen.findByRole("status", { name: "Waiting for the lab to settle" });
    expect(within(card).getByText("Mock queue lag")).toBeTruthy();
    expect(within(card).getByText("40 s (want under 5 s)")).toBeTruthy();
    expect(within(card).getAllByText("Waiting")).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 3);
    expect(await screen.findByText(/a mock teammate reports/)).toBeTruthy();
    expect(screen.queryByRole("status", { name: "Waiting for the lab to settle" })).toBeNull();
  });

  it("stops waiting on Cancel and starts nothing", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 100;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("status", { name: "Waiting for the lab to settle" });

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByRole("button", { name: "Start the exercise" })).toBeTruthy();
    state.lab.settlePolls = 0;
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 3);
    expect(state.lab.active).toBeNull();
  });

  it("makes the ramp wait the same way", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 1;
    renderPlay("/play?tab=capacity");
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));

    expect(await screen.findByRole("status", { name: "Waiting for the lab to settle" })).toBeTruthy();
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 2);
    expect(state.lab.active?.mode).toBe("ramp");
  });

  it("starts the chart range where the last exercise ended", () => {
    expect(exerciseRange(1_700_000_900, 1_700_000_500).from).toBe("1700000500000");
    expect(exerciseRange(1_700_000_900).from).toBe(String((1_700_000_900 - 900) * 1000));
  });
});
