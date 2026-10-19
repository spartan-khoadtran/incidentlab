// @vitest-environment jsdom
import "./setup-dom.ts";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PageBoundary } from "../src/components/PageBoundary.tsx";

function Broken(): never {
  throw new Error("Failed to fetch dynamically imported module");
}

describe("PageBoundary", () => {
  it("shows a reload button instead of a blank page when a page fails to load", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <PageBoundary>
        <Broken />
      </PageBoundary>,
    );
    expect(screen.getByRole("alert").textContent).toContain("did not load");
    expect(screen.getByRole("button", { name: "Reload the page" })).toBeTruthy();
  });
});
