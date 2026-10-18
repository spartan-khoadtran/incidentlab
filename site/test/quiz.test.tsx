// @vitest-environment jsdom
import "./setup-dom.ts";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { LabProvider } from "../src/lib/lab.tsx";
import { QuizRunner } from "../src/pages/quiz/QuizRunner.tsx";
import { MockLab } from "./mock-lab.ts";
import { mockServer } from "./msw.ts";

const { server, state } = mockServer();

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
beforeEach(() => {
  state.lab = new MockLab();
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("QuizRunner", () => {
  it("asks one question at a time, scores it, and lists the misses at the end", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LabProvider>
          <QuizRunner />
        </LabProvider>
      </MemoryRouter>,
    );
    await user.clear(screen.getByRole("spinbutton", { name: "Questions" }));
    await user.type(screen.getByRole("spinbutton", { name: "Questions" }), "2");
    await user.click(screen.getByRole("button", { name: "Start the quiz" }));

    expect(await screen.findByText(/How many requests are in flight/)).toBeTruthy();
    await user.type(screen.getByRole("textbox", { name: "Your answer" }), "ten");
    await user.click(screen.getByRole("button", { name: "Check" }));
    expect(screen.getByRole("alert").textContent).toMatch(/comma or a dot/);
    await user.clear(screen.getByRole("textbox", { name: "Your answer" }));
    await user.type(screen.getByRole("textbox", { name: "Your answer" }), "10,0");
    await user.click(screen.getByRole("button", { name: "Check" }));
    expect(await screen.findByText("Right.")).toBeTruthy();
    expect(screen.getByRole("link", { name: /Little's Law/ }).getAttribute("href")).toBe("/guide/investigation-guide#littles-law");

    await user.click(screen.getByRole("button", { name: "Next question" }));
    await user.keyboard("2");
    await user.click(screen.getByRole("button", { name: "Check" }));
    expect(await screen.findByText("Not quite.")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "See the summary" }));
    expect(screen.getByText("/ 2 right")).toBeTruthy();
    expect(screen.getByText(/which layer sees the time/)).toBeTruthy();
  });
});
