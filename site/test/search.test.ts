import { describe, expect, it } from "vitest";
import { search, type SearchEntry } from "../src/lib/search.ts";

const entries: SearchEntry[] = [
  { kind: "guide", title: "Little's Law", context: "Linking RED and USE", body: "L = λ × W", href: "/a" },
  { kind: "command", title: "make quiz", context: "exercises", body: "Theory drills", href: "/b" },
  { kind: "scenario", title: "Retry storm", context: "Level 2", body: "retry_storm", href: "/c" },
];

describe("search", () => {
  it("needs every term to match, by prefix", () => {
    expect(search(entries, "littl law").map((h) => h.entry.href)).toEqual(["/a"]);
    expect(search(entries, "quiz drills").map((h) => h.entry.href)).toEqual(["/b"]);
    expect(search(entries, "retry nothing")).toEqual([]);
  });

  it("ranks a title hit above a body hit", () => {
    const more = [...entries, { kind: "guide" as const, title: "Causal chain", context: "Linking", body: "clients retry", href: "/d" }];
    expect(search(more, "retry")[0].entry.href).toBe("/c");
  });
});
