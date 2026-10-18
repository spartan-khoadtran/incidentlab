import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { SITE } from "./paths.ts";

const BANNED = [
  "delve", "dive into", "crucial", "vital", "robust", "comprehensive", "seamless", "streamline", "optimal", "holistic", "empower",
  "paradigm", "synergy", "leverage", "utilize", "facilitate", "mitigate", "elevate", "foster", "underscore", "landscape", "realm", "journey",
];

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? (name === "generated" ? [] : files(path)) : [path];
  });
}

describe("UI text", () => {
  const sources = files(join(SITE, "src")).filter((f) => /\.(ts|tsx)$/.test(f));

  it("uses no long dash as punctuation", () => {
    const hits = sources.filter((f) => /[—–]/.test(readFileSync(f, "utf8")));
    expect(hits).toEqual([]);
  });

  it("uses none of the banned words", () => {
    const hits = sources.flatMap((f) => {
      const text = readFileSync(f, "utf8").toLowerCase();
      return BANNED.filter((w) => new RegExp(`\\b${w}\\b`).test(text)).map((w) => `${f}: ${w}`);
    });
    expect(hits).toEqual([]);
  });
});
