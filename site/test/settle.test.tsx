// @vitest-environment jsdom
import "./setup-dom.ts";
import { render, screen, waitFor, within } from "@testing-library/react";
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
  state.holds.clear();
});
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());

const WEB = { "content-type": "application/json", "x-lab-client": "web" };
const WAITING = { name: "Waiting for the lab to settle" };
const starts = () => state.calls.filter((c) => c.method === "POST" && c.path === "/api/exercises");

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

    const card = await screen.findByRole("region", WAITING);
    expect(within(card).getByText("Mock queue lag")).toBeTruthy();
    expect(within(card).getByText("Now 40 s (normal 1 s). Want at most 5 s for 60 s.")).toBeTruthy();
    expect(within(card).getAllByText("Waiting")).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 3);
    expect(await screen.findByText(/a mock teammate reports/)).toBeTruthy();
    expect(screen.queryByRole("region", WAITING)).toBeNull();
    expect(starts()).toHaveLength(2);
  });

  it("stops waiting on Cancel and starts nothing", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 100;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("region", WAITING);

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByRole("button", { name: "Start the exercise" })).toBeTruthy();
    state.lab.settlePolls = 0;
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 3);
    expect(state.lab.active).toBeNull();
    expect(starts()).toHaveLength(1);
  });

  it("posts again without force after a forced start, and drops the old result while it waits", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.handle("POST", "/api/exercises", WEB, {});
    state.lab.handle("POST", "/api/exercise/reveal", WEB, { key: "mock-key-1" });
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "New exercise" }));
    expect(screen.getByRole("button", { name: "Back to the result" })).toBeTruthy();

    state.lab.settlePolls = 2;
    await user.click(screen.getByRole("button", { name: "Start a new exercise" }));
    await user.click(await screen.findByRole("button", { name: "Drop it and start" }));
    await screen.findByRole("region", WAITING);
    expect(screen.queryByRole("button", { name: "Back to the result" })).toBeNull();

    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 3);
    expect(await screen.findByText(/Ticket number 2/)).toBeTruthy();
    expect(starts().map((c) => c.body.force)).toEqual([undefined, true, undefined]);
  });

  it("never wipes an exercise started in the terminal during the wait", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 100;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("region", WAITING);

    state.lab.settlePolls = 0;
    state.lab.handle("POST", "/api/exercises", WEB, {});
    const terminal = state.lab.active!.key;
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 2);
    await waitFor(() => expect(screen.queryByRole("region", WAITING)).toBeNull());
    expect(state.lab.active?.key).toBe(terminal);
    expect(starts().every((c) => c.body.force === undefined)).toBe(true);
  });

  it("keeps waiting when one settle poll fails", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 2;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("region", WAITING);

    state.lab.failNext.set("GET /settle", { status: 503, error: "unavailable", message: "Mock: the lab is busy." });
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS);
    expect(screen.getByRole("region", WAITING)).toBeTruthy();
    expect(screen.queryByText("Mock: the lab is busy.")).toBeNull();

    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 3);
    expect(await screen.findByText(/a mock teammate reports/)).toBeTruthy();
    expect(starts()).toHaveLength(2);
  });

  it("stops waiting after three failed polls in a row", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 100;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("region", WAITING);

    for (let i = 0; i < 3; i += 1) {
      state.lab.failNext.set("GET /settle", { status: 503, error: "unavailable", message: "Mock: the lab is busy." });
      await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS);
    }
    expect(await screen.findByText("Mock: the lab is busy.")).toBeTruthy();
    expect(screen.queryByRole("region", WAITING)).toBeNull();
    expect(starts()).toHaveLength(1);
  });

  it("shows the exercise when Cancel is pressed while the start post runs", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 1;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("region", WAITING);

    let release = () => {};
    state.holds.set("POST /api/exercises", new Promise<void>((resolve) => (release = resolve)));
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 2);
    expect(await screen.findByText("Starting...")).toBeTruthy();
    const cancel = screen.getByRole("button", { name: "Cancel" });
    expect((cancel as HTMLButtonElement).disabled).toBe(true);
    await user.click(cancel);

    release();
    expect(await screen.findByText(/a mock teammate reports/)).toBeTruthy();
    expect(starts()).toHaveLength(2);
  });

  it("warns when the exercise started before every check was back to normal", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 100;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByRole("region", WAITING);

    state.lab.gaveUp = true;
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS);
    expect(await screen.findByText(/not fully back to normal \(Mock queue lag\)/)).toBeTruthy();
  });

  it("starts the Investigate links where the last exercise ended", async () => {
    const user = userEvent.setup();
    state.lab.lastEnded = Math.floor(Date.now() / 1000) - 60;
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));

    const link = await screen.findByRole("link", { name: /^Explore: Prometheus/ });
    const panes = JSON.parse(new URL(link.getAttribute("href")!).searchParams.get("panes")!);
    expect(panes.a.range.from).toBe(String(state.lab.lastEnded * 1000));
    expect(screen.queryByText(/not fully back to normal/)).toBeNull();
  });

  it("makes the ramp wait the same way", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.settlePolls = 1;
    renderPlay("/play?tab=capacity");
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));

    expect(await screen.findByRole("region", WAITING)).toBeTruthy();
    await vi.advanceTimersByTimeAsync(SETTLE_POLL_MS * 2);
    expect(state.lab.active?.mode).toBe("ramp");
  });

  it("starts the chart range where the last exercise ended", () => {
    expect(exerciseRange(1_700_000_900, 1_700_000_500).from).toBe("1700000500000");
    expect(exerciseRange(1_700_000_900).from).toBe(String((1_700_000_900 - 900) * 1000));
  });
});
