// @vitest-environment jsdom
import "./setup-dom.ts";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { LabProvider } from "../src/lib/lab.tsx";
import { Play } from "../src/pages/Play.tsx";
import { ANSWER_OPTIONS, MOCK_REVEAL, MockLab, NOT_OFFERED } from "./mock-lab.ts";
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

const SCENARIO_TEXT = [MOCK_REVEAL.id, MOCK_REVEAL.title, MOCK_REVEAL.explanation, ...MOCK_REVEAL.steps, ...MOCK_REVEAL.evidence, ...MOCK_REVEAL.rule_outs.map((r) => r.check)];

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

  it("goes back to the start when the fault was already cleared in the terminal", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    state.lab.handle("POST", "/api/exercise/reveal", JSON_POST, {});
    renderPlay();
    const clearButton = await screen.findByRole("button", { name: "Clear the fault" });
    state.lab.handle("POST", "/api/exercise/clear", JSON_POST, {});

    await user.click(clearButton);

    expect(await screen.findByRole("button", { name: "Start the exercise" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
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

  it("refuses an answer for an exercise replaced in the terminal, and shows the new one", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByLabelText("Mock nature: the system itself"));
    await user.click(screen.getByLabelText("Mock location: before the app"));
    await user.click(screen.getByLabelText("Mock signal: the gap between two layers"));
    await user.click(screen.getByLabelText("Mock cause: a queue in front of the app"));
    state.lab.handle("POST", "/api/exercises", JSON_POST, { force: true });

    await user.click(screen.getByRole("button", { name: "Submit the answer" }));

    expect(await screen.findByText(/Ticket number 2/)).toBeTruthy();
    expect(screen.getByText("Mock: the exercise on this page is no longer the open one.")).toBeTruthy();
    expect(state.lab.history).toEqual([]);
    expect(state.lab.active?.answered).toBe(false);
    const answer = state.calls.find((c) => c.path === "/api/exercise/answer");
    expect(answer).toBeTruthy();
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

  it("offers only the options the ticket shows", async () => {
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await screen.findByText(/a mock teammate reports/);
    const form = screen.getByRole("form", { name: "Your answer" });
    const offered = [...ANSWER_OPTIONS.natures, ...ANSWER_OPTIONS.locations, ...ANSWER_OPTIONS.signals, ...ANSWER_OPTIONS.causes];
    expect(within(form).getAllByRole("radio")).toHaveLength(offered.length);
    for (const o of offered) expect(within(form).getByLabelText(o.label)).toBeTruthy();
    expect(within(form).queryByText(NOT_OFFERED.signal.label)).toBeNull();
    expect(within(form).queryByText(NOT_OFFERED.cause.label)).toBeNull();
    expect(within(form).getByRole("group", { name: "Mock layer two" })).toBeTruthy();

    const refused = state.lab.handle("POST", "/api/exercise/answer", JSON_POST, { ...ANSWER, cause: NOT_OFFERED.cause.key });
    expect(refused.status).toBe(400);
    expect((refused.body as { error: string }).error).toBe("unknown_key");
  });

  it("lists every offered cause with its check after the answer, and marks a wrong pick", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByLabelText("Mock nature: the system itself"));
    await user.click(screen.getByLabelText("Mock location: before the app"));
    await user.click(screen.getByLabelText("Mock signal: the gap between two layers"));
    await user.click(screen.getByLabelText("Mock cause: one hot core"));
    expect(screen.queryByRole("region", { name: "Rule out the look-alikes" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "Submit the answer" }));

    const section = await screen.findByRole("region", { name: "Rule out the look-alikes" });
    const items = within(section).getAllByRole("listitem");
    expect(items.map((li) => li.querySelector(".ruleout-label")?.textContent)).toEqual(ANSWER_OPTIONS.causes.map((c) => c.label));
    for (const r of MOCK_REVEAL.rule_outs) expect(within(section).getByText(r.check)).toBeTruthy();

    const [queue, retry, cpu] = items;
    expect(within(queue).getByText("The cause")).toBeTruthy();
    expect(within(retry).getByText("1 point")).toBeTruthy();
    expect(within(cpu).getByText("Your pick")).toBeTruthy();
    expect(cpu.classList.contains("is-wrong-pick")).toBe(true);
    expect(within(section).getAllByText("Your pick")).toHaveLength(1);
    expect(within(section).getAllByText("The cause")).toHaveLength(1);
  });

  it("marks a partial cause pick as partial, not as wrong", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByLabelText("Mock nature: the system itself"));
    await user.click(screen.getByLabelText("Mock location: before the app"));
    await user.click(screen.getByLabelText("Mock signal: the gap between two layers"));
    await user.click(screen.getByLabelText("Mock cause: retries"));
    await user.click(screen.getByRole("button", { name: "Submit the answer" }));

    const section = await screen.findByRole("region", { name: "Rule out the look-alikes" });
    const retry = within(section).getAllByRole("listitem")[1];
    const pick = within(retry).getByText("Your pick");
    expect(pick.classList.contains("verdict-partial")).toBe(true);
    expect(pick.classList.contains("verdict-wrong")).toBe(false);
    expect(retry.classList.contains("is-wrong-pick")).toBe(false);
    expect(retry.classList.contains("is-partial-pick")).toBe(true);
    expect(section.querySelector(".is-wrong-pick")).toBeNull();
  });

  it("shows the picks and the rule-outs again after a reload of an answered exercise", async () => {
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    state.lab.handle("POST", "/api/exercise/answer", JSON_POST, { ...ANSWER, signal: "mock_rate", cause: "mock_cpu" });
    renderPlay();

    expect(await screen.findByLabelText("Score 2 out of 5")).toBeTruthy();
    expect(screen.getByRole("columnheader", { name: "You said" })).toBeTruthy();
    const signalRow = screen.getByRole("rowheader", { name: "Signal" }).closest("tr")!;
    expect(within(signalRow).getByText("Mock signal: rate by route")).toBeTruthy();
    const causeRow = screen.getByRole("rowheader", { name: "Cause" }).closest("tr")!;
    expect(within(causeRow).getByText("Mock cause: one hot core")).toBeTruthy();

    const section = screen.getByRole("region", { name: "Rule out the look-alikes" });
    expect(within(section).getAllByRole("listitem")).toHaveLength(MOCK_REVEAL.rule_outs.length);
    const cpu = within(section).getAllByRole("listitem")[2];
    expect(within(cpu).getByText("Your pick")).toBeTruthy();
    expect(cpu.classList.contains("is-wrong-pick")).toBe(true);
  });

  it("shows the rule-outs after a reveal, with no pick to mark", async () => {
    const user = userEvent.setup();
    state.lab.handle("POST", "/api/exercises", JSON_POST, {});
    renderPlay();
    await user.click(await screen.findByRole("button", { name: "Show the answer without a score" }));
    await user.click(within(screen.getByRole("dialog", { name: "Show the answer?" })).getByRole("button", { name: "Show it, no score" }));
    const section = await screen.findByRole("region", { name: "Rule out the look-alikes" });
    expect(within(section).getAllByRole("listitem")).toHaveLength(MOCK_REVEAL.rule_outs.length);
    expect(within(section).queryByText("Your pick")).toBeNull();
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
