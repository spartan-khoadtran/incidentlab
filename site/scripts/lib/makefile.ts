export interface MakeOption {
  name: string;
  example: string | null;
}

export interface MakeTarget {
  name: string;
  help: string;
  options: MakeOption[];
}

export interface MakeGroup {
  id: string;
  title: string;
  note: string | null;
  targets: MakeTarget[];
}

export interface MakeLink {
  label: string;
  value: string;
}

export interface MakefileData {
  groups: MakeGroup[];
  links: MakeLink[];
}

const TARGET = /^([a-zA-Z_-]+):.*?## (.*)$/;
const SECTION = /^# -{5,}\s*(.+)$/;
const RECIPE_VAR = /\$\((?:if|or) (?:\$\(filter [^,]*,)?\$\(([A-Z_]+)\)/g;
const PLAIN_VAR = /\$\(([A-Z_]+)\)/g;
const HELP_OPTION = /\b([A-Z][A-Z_]*)=([^\s,)]+)/g;
const INTERNAL = new Set(["COMPOSE", "LABCTL", "CTL", "GRADLE", "MAKE", "MAKEFILE_LIST", "CURDIR"]);
const BANNER = /^\t@echo " {2}(\S+)\s{2,}(.+)"$/;

function splitTitle(raw: string): { title: string; note: string | null } {
  const match = raw.match(/^(.*?)\s*\((.*)\)\s*$/);
  return match ? { title: match[1], note: match[2] } : { title: raw.trim(), note: null };
}

function slug(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

/**
 * Reads the `target: ## help` lines the Makefile's own `help` target greps, grouped by the
 * `# ----- name` comments, plus the URLs the `wait` target prints when the lab is up.
 */
export function parseMakefile(text: string): MakefileData {
  const lines = text.split("\n");
  const groups: MakeGroup[] = [{ id: "general", title: "general", note: null, targets: [] }];
  const links: MakeLink[] = [];
  let current: MakeTarget | null = null;
  let recipeOf: string | null = null;

  for (const line of lines) {
    const section = line.match(SECTION);
    if (section) {
      const { title, note } = splitTitle(section[1]);
      groups.push({ id: slug(title), title, note, targets: [] });
      current = null;
      recipeOf = null;
      continue;
    }
    const target = line.match(TARGET);
    if (target) {
      current = { name: target[1], help: target[2].trim(), options: [] };
      groups[groups.length - 1].targets.push(current);
      recipeOf = target[1];
      continue;
    }
    if (/^[a-zA-Z_-]+:/.test(line)) {
      current = null;
      recipeOf = line.split(":")[0];
      continue;
    }
    if (!line.startsWith("\t")) {
      if (line.trim() !== "") {
        current = null;
        recipeOf = null;
      }
      continue;
    }
    if (recipeOf === "wait") {
      const banner = line.match(BANNER);
      if (banner) links.push({ label: banner[1], value: banner[2].trim() });
    }
    if (current) addRecipeOptions(current, line);
  }

  const targets = groups.flatMap((g) => g.targets);
  targets.forEach(addHelpExamples);
  for (const target of targets) {
    for (const option of target.options) {
      option.example ??= targets.flatMap((t) => t.options).find((o) => o.name === option.name && o.example)?.example ?? null;
    }
  }
  return { groups: groups.filter((g) => g.targets.length > 0), links };
}

function addRecipeOptions(target: MakeTarget, line: string) {
  const names = new Set<string>();
  for (const m of line.matchAll(RECIPE_VAR)) names.add(m[1]);
  if (names.size === 0 && /\$\(COMPOSE\)|\$\(CTL\)/.test(line)) {
    for (const m of line.matchAll(PLAIN_VAR)) if (!INTERNAL.has(m[1])) names.add(m[1]);
  }
  for (const name of names) {
    if (!target.options.some((o) => o.name === name)) target.options.push({ name, example: null });
  }
}

function addHelpExamples(target: MakeTarget) {
  for (const m of target.help.matchAll(HELP_OPTION)) {
    const option = target.options.find((o) => o.name === m[1]);
    if (option && option.example === null) option.example = m[2].replace(/[.;:]$/, "");
  }
}
