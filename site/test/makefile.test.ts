import { describe, expect, it } from "vitest";
import { parseMakefile } from "../scripts/lib/makefile.ts";
import { repoFile } from "./paths.ts";

const SAMPLE = [
  "help: ## List the commands",
  "\t@echo hi",
  "",
  "# ----------------------------------------------------------------------------- lifecycle",
  "up: check ## Build and start the whole lab",
  "\t$(COMPOSE) up -d --build",
  "",
  "wait:",
  '\t@echo "  Grafana     http://localhost:3000"',
  '\t@echo ""',
  "",
  "# ----------------------------------------------------------------------------- exercises",
  "new: ## New exercise. Options: LEVEL=1|2|3 TOPIC=async FORCE=1",
  "\t@$(CTL) new $(if $(LEVEL),--level $(LEVEL)) $(if $(TOPIC),--topic $(TOPIC)) $(if $(filter 1 yes true,$(FORCE)),--force)",
  "toolbox: ## CLI only. Options: LEVEL TOPIC FORCE",
  "\t@$(CTL) new --mode toolbox $(if $(LEVEL),--level $(LEVEL)) $(if $(TOPIC),--topic $(TOPIC)) $(if $(filter 1 yes true,$(FORCE)),--force)",
  "logs: ## Tail logs: S=worker",
  "\t$(COMPOSE) logs -f --tail=100 $(S)",
  "",
  "# ----------------------------------------------------------------------------- development (needs a JDK)",
  "check:",
  "\t@command -v docker",
].join("\n");

describe("parseMakefile", () => {
  const data = parseMakefile(SAMPLE);

  it("groups the documented targets by the section comments", () => {
    expect(data.groups.map((g) => [g.id, g.targets.map((t) => t.name)])).toEqual([
      ["general", ["help"]],
      ["lifecycle", ["up"]],
      ["exercises", ["new", "toolbox", "logs"]],
    ]);
  });

  it("keeps a section note and skips targets with no help line", () => {
    const all = data.groups.flatMap((g) => g.targets.map((t) => t.name));
    expect(all).not.toContain("wait");
    expect(all).not.toContain("check");
  });

  it("reads the options a recipe passes, with examples from the help text or a sibling target", () => {
    const targets = Object.fromEntries(data.groups.flatMap((g) => g.targets).map((t) => [t.name, t]));
    expect(targets.new.options).toEqual([
      { name: "LEVEL", example: "1|2|3" },
      { name: "TOPIC", example: "async" },
      { name: "FORCE", example: "1" },
    ]);
    expect(targets.toolbox.options).toEqual(targets.new.options);
    expect(targets.logs.options).toEqual([{ name: "S", example: "worker" }]);
    expect(targets.up.options).toEqual([]);
  });

  it("reads the URLs the wait target prints", () => {
    expect(data.links).toEqual([{ label: "Grafana", value: "http://localhost:3000" }]);
  });

  it("finds every documented target of the real Makefile", () => {
    const real = repoFile("Makefile");
    const documented = [...real.matchAll(/^([a-zA-Z_-]+):.*?## /gm)].map((m) => m[1]);
    const parsed = parseMakefile(real).groups.flatMap((g) => g.targets.map((t) => t.name));
    expect(parsed.sort()).toEqual(documented.sort());
    expect(parsed).toEqual(expect.arrayContaining(["up", "new", "answer", "quiz", "toolbox", "ramp", "bench", "site"]));
  });
});
