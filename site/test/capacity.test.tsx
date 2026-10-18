// @vitest-environment jsdom
import "./setup-dom.ts";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { LabProvider } from "../src/lib/lab.tsx";
import { Play } from "../src/pages/Play.tsx";
import { MockLab } from "./mock-lab.ts";
import { mockServer } from "./msw.ts";

const { server, state } = mockServer();

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
beforeEach(() => {
  state.lab = new MockLab();
  sessionStorage.clear();
});
afterEach(() => server.resetHandlers());
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

  it("runs a ramp: the current step, the answer and the scored table", async () => {
    const user = userEvent.setup();
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the ramp" }));
    const now = await screen.findByRole("listitem", { current: "step" });
    expect(now.textContent).toBe("75");
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

  it("runs a bench and shows the minutes before against the bench", async () => {
    const user = userEvent.setup();
    renderCapacity();
    await user.click(await screen.findByRole("button", { name: "Start the bench" }));
    expect(await screen.findByText("Mock bench explanation.")).toBeTruthy();
    expect(screen.getByText("Clients: p99")).toBeTruthy();
    expect(screen.getByText("120 ms")).toBeTruthy();
    await userEvent.setup().click(screen.getByRole("button", { name: "Save to history and go back" }));
    expect(await screen.findByRole("button", { name: "Start the bench" })).toBeTruthy();
  });
});
