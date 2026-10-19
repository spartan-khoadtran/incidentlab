import makeJson from "../generated/make.json";

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

export const make = makeJson as unknown as { groups: MakeGroup[]; links: { label: string; value: string }[] };

export function target(name: string): MakeTarget | undefined {
  return make.groups.flatMap((g) => g.targets).find((t) => t.name === name);
}
