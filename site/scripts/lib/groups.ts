/** How the guide nav groups the docs files. A file not named here still shows, under the last group. */
export const DOC_GROUPS: { title: string; files: string[] }[] = [
  { title: "Method", files: ["investigation-guide", "methods"] },
  { title: "Foundations", files: ["foundations", "monitoring", "benchmarking"] },
  { title: "Resources", files: ["cpu", "runtime", "disk", "network"] },
  { title: "Tools", files: ["profiling", "toolbox", "capacity"] },
];

export const FALLBACK_GROUP = "More";

export function groupOf(slug: string): { group: string; order: number } {
  let order = 0;
  for (const g of DOC_GROUPS) {
    const i = g.files.indexOf(slug);
    if (i >= 0) return { group: g.title, order: order + i };
    order += g.files.length;
  }
  return { group: FALLBACK_GROUP, order: 1000 };
}
