import { readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { DOC_GROUPS } from "../scripts/lib/groups.ts";
import { ROOT } from "./paths.ts";

describe("guide groups", () => {
  it("puts every docs file in a named group, and names no missing file", () => {
    const files = readdirSync(join(ROOT, "docs"))
      .filter((f) => f.endsWith(".md"))
      .map((f) => f.replace(/\.md$/, ""))
      .sort();
    const grouped = DOC_GROUPS.flatMap((g) => g.files).sort();
    expect(grouped).toEqual(files);
  });
});
