export interface PointerHeading {
  depth: number;
  raw: string;
  id: string;
  section: string | null;
}

export interface PointerDoc {
  slug: string;
  title: string;
  headings: PointerHeading[];
}

export interface PointerPart {
  /** The part as the CLI prints it, without the note. */
  label: string;
  section: string;
  subsection: string | null;
  note: string | null;
  doc: PointerDoc | null;
  href: string | null;
}

const NOTE = /\s*\(([^)]*)\)$/;

/** Splits `Section > Subsection (note); Other > Part` the way GuidePointersTest does, and finds each heading. */
export function resolvePointer(pointer: string, docs: PointerDoc[]): PointerPart[] {
  return pointer.split("; ").map((part) => {
    const note = part.match(NOTE)?.[1] ?? null;
    const label = part.replace(NOTE, "").trim();
    const [section, subsection = null] = label.split(" > ");
    for (const doc of docs) {
      const h2 = doc.headings.find((h) => h.depth === 2 && h.raw === section);
      if (!h2) continue;
      const h3 = subsection ? doc.headings.find((h) => h.depth === 3 && h.section === section && h.raw === subsection) : undefined;
      if (subsection && !h3) return { label, section, subsection, note, doc, href: null };
      return { label, section, subsection, note, doc, href: `/guide/${doc.slug}#${(h3 ?? h2).id}` };
    }
    return { label, section, subsection, note, doc: null, href: null };
  });
}
