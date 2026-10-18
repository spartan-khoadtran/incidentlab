// @vitest-environment jsdom
import "./setup-dom.ts";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { LabProvider } from "../src/lib/lab.tsx";
import { Play } from "../src/pages/Play.tsx";
import { MOCK_REVEAL, MockLab } from "./mock-lab.ts";
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

const JSON_POST = { "content-type": "application/json", "x-lab-client": "web" };
const ANSWER = { nature: "mock_arch", location: "mock_lb", signal: "mock_gap", cause: "mock_queue" };
const STATUS_POLL_MS = 15_000;

const SCENARIO_TEXT = [MOCK_REVEAL.id, MOCK_REVEAL.title, MOCK_REVEAL.explanation, ...MOCK_REVEAL.steps, ...MOCK_REVEAL.evidence];

describe("Play", () => {
  it("says how to start the lab when the API does not answer", async () => {
    state.lab.online = false;
    renderPlay();
    expect(await screen.findByText("The lab is not running")).toBeTruthy();
    expect(screen.getByText("make up")).toBeTruthy();
  });

  it("runs a whole exercise: start, hint, answer, result, clear", async () => {
    const user = userEvent.setup();
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));

    expect(await screen.findByText(/a mock teammate reports/)).toBeTruthy();
    for (const text of SCENARIO_TEXT) expect(document.body.textContent).not.toContain(text);

    await user.click(screen.getByRole("button", { name: /Take hint 1/ }));
    expect(await screen.findByText("Mock hint one, broad.")).toBeTruthy();
    expect(screen.getByText("Penalty so far: −0.5")).toBeTruthy();

    const submit = screen.getByRole("button", { name: "Submit the answer" }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await user.click(screen.getByLabelText("Mock nature: the system itself"));
    await user.click(screen.getByLabelText("Mock location: before the app"));
    await user.click(screen.getByLabelText("Mock signal: the gap between two layers"));
    await user.click(screen.getByLabelText("Mock cause: a queue in front of the app"));
    await user.type(screen.getByRole("textbox", { name: /One line about the evidence/ }), "LB p99 against api p99");
    await user.click(submit);

    expect(await screen.findByLabelText("Score 4.5 out of 5")).toBeTruthy();
    expect(screen.getByText(MOCK_REVEAL.title)).toBeTruthy();
    expect(screen.getAllByText("Right")).toHaveLength(4);
    expect(screen.getByText("Mock step three.")).toBeTruthy();
    const explore = screen.getByRole("link", { name: "Open in Grafana Explore" }) as HTMLAnchorElement;
    expect(explore.href).toContain("/explore?schemaVersion=1");
    const guideLink = screen.getByRole("link", { name: /RED method in detail › Rate/ }) as HTMLAnchorElement;
    expect(guideLink.getAttribute("href")).toBe("/guide/investigation-guide#rate");

    const answer = state.calls.find((c) => c.path === "/api/exercise/answer");
    expect(answer).toBeTruthy();
    expect((state.lab.history[0] as { note: string }).note).toBe("LB p99 against api p99");

    await user.click(screen.getByRole("button", { name: "Clear the fault" }));
    expect(await screen.findByRole("button", { name: "Start the exercise" })).toBeTruthy();
  });

  it("asks before dropping an open exercise", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    state.lab.handle("POST", "/api/exercise/reveal", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "New exercise" }));
    await user.click(screen.getByRole("button", { name: "Start a new exercise" }));
    const dialog = await screen.findByRole("dialog", { name: "An exercise is still open" });
    expect(state.lab.active?.n).toBe(1);
    await user.click(within(dialog).getByRole("button", { name: "Drop it and start" }));
    expect(await screen.findByText(/Ticket number 2/)).toBeTruthy();
    expect(state.lab.active?.n).toBe(2);
  });

  it("asks before a reveal", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Show the answer without a score" }));
    const dialog = screen.getByRole("dialog", { name: "Show the answer?" });
    expect(state.calls.some((c) => c.path === "/api/exercise/reveal")).toBe(false);
    await user.click(within(dialog).getByRole("button", { name: "Show it, no score" }));
    expect(await screen.findByText("revealed, not scored")).toBeTruthy();
  });

  it("shows the result when the answer was given in the terminal", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await screen.findByText(/a mock teammate reports/);
    state.lab.handle("POST", "/api/exercise/answer", JSON_POST, ANSWER);
    await user.click(screen.getByRole("button", { name: /Take hint 1/ }));
    expect(await screen.findByLabelText("Score 5 out of 5")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Submit the answer" })).toBeNull();
  });

  it("follows make clear and make new in a terminal", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    expect(await screen.findByText(/Ticket number 1/)).toBeTruthy();

    state.lab.handle("POST", "/api/exercise/clear", JSON_POST, {});
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(await screen.findByRole("button", { name: "Start the exercise" })).toBeTruthy();

    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(await screen.findByText(/Ticket number 2/)).toBeTruthy();

    state.lab.handle("POST", "/api/exercise/answer", JSON_POST, ANSWER);
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(await screen.findByLabelText("Score 5 out of 5")).toBeTruthy();
  });

  it("keeps the answer picks through one failed poll and through an outage", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByLabelText("Mock nature: the system itself"));
    const picked = () => (screen.getByLabelText("Mock nature: the system itself") as HTMLInputElement).checked;

    state.lab.online = false;
    await vi.advanceTimersByTimeAsync(STATUS_POLL_MS);
    expect(screen.queryByText("The lab stopped answering")).toBeNull();
    expect(screen.getByText("Lab running")).toBeTruthy();

    await vi.advanceTimersByTimeAsync(5_000);
    expect(await screen.findByText("The lab stopped answering")).toBeTruthy();
    expect(screen.getByText(/a mock teammate reports/)).toBeTruthy();
    expect(picked()).toBe(true);

    state.lab.online = true;
    await vi.advanceTimersByTimeAsync(5_000);
    await waitFor(() => expect(screen.queryByText("The lab stopped answering")).toBeNull());
    expect(picked()).toBe(true);
  });

  it("shows hint errors next to the hints", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    state.lab.failNext.set("POST /exercise/hint", { status: 500, error: "internal", message: "Mock hint failure." });
    renderPlay();
    await user.click(await screen.findByRole("button", { name: /Take hint 1/ }));
    const note = await screen.findByText("Mock hint failure.");
    expect(note.closest(".hints")).toBeTruthy();
    expect(note.closest(".answer-form")).toBeNull();
  });

  it("shows the toolbox rules and no Grafana link in toolbox mode", async () => {
    state.lab.handle("POST", "/api/exercises", JSON_POST, { mode: "toolbox" });
    renderPlay();
    expect(await screen.findByText(/Mock rules: no Grafana/)).toBeTruthy();
    expect(screen.queryByRole("link", { name: /dashboard|Explore/ })).toBeNull();
    expect(screen.getByText("make shell")).toBeTruthy();
  });

  it("never asks the API for the reveal before the learner does", async () => {
    const user = userEvent.setup();
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Start the exercise" }));
    await screen.findByText(/a mock teammate reports/);
    await waitFor(() => expect(state.calls.length).toBeGreaterThan(3));
    expect(state.calls.map((c) => c.path).filter((p) => /reveal|answer/.test(p))).toEqual([]);
  });

  it("lists the history with the average", async () => {
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    state.lab.handle("POST", "/api/exercise/answer", JSON_POST, { nature: "mock_arch", location: "mock_lb", signal: "mock_rate", cause: "mock_cpu", note: "a note" });
    renderPlay("/play?tab=history");
    expect(await screen.findByText("a note")).toBeTruthy();
    expect(screen.getByText("2.00")).toBeTruthy();
    expect(screen.getByText("Mock cause: one hot core")).toBeTruthy();
  });
});
