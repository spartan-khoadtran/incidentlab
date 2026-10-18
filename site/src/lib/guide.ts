import guideJson from "../generated/guide.json";

export interface Heading {
  depth: number;
  raw: string;
  text: string;
  id: string;
  section: string | null;
}

export interface GuideDoc {
  slug: string;
  file: string;
  group: string;
  order: number;
  title: string;
  lede: string;
  note: string | null;
  words: number;
  headings: Heading[];
}

export const guide = guideJson as unknown as GuideDoc[];

export function docBySlug(slug: string): GuideDoc | undefined {
  return guide.find((d) => d.slug === slug);
}

export function docByFile(file: string | null): GuideDoc | undefined {
  return file ? guide.find((d) => d.file === file) : undefined;
}
