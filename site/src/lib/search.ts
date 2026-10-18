export interface SearchEntry {
  kind: "guide" | "scenario" | "command" | "page";
  title: string;
  context: string;
  body: string;
  href: string;
}

export interface SearchHit {
  entry: SearchEntry;
  score: number;
}

export function normalize(text: string): string {
  return text
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9%_.]+/g, " ")
    .trim();
}

function wordsOf(text: string): string[] {
  return normalize(text).split(" ").filter(Boolean);
}

function termScore(term: string, title: string[], context: string[], body: string[]): number {
  const hit = (words: string[], weightExact: number, weightPrefix: number, weightInside: number) => {
    let best = 0;
    for (const w of words) {
      if (w === term) best = Math.max(best, weightExact);
      else if (w.startsWith(term)) best = Math.max(best, weightPrefix);
      else if (term.length >= 3 && w.includes(term)) best = Math.max(best, weightInside);
    }
    return best;
  };
  return Math.max(hit(title, 10, 7, 3), hit(context, 4, 3, 1), hit(body, 3, 2, 1));
}

const KIND_BONUS: Record<SearchEntry["kind"], number> = { page: 2, command: 1, scenario: 0.5, guide: 0 };

/** Every term must match a word of the entry (whole, prefix, or inside a long enough word). */
export function search(entries: SearchEntry[], query: string, limit = 30): SearchHit[] {
  const terms = wordsOf(query);
  if (terms.length === 0) return [];
  const phrase = normalize(query);
  const hits: SearchHit[] = [];
  for (const entry of entries) {
    const title = wordsOf(entry.title);
    const context = wordsOf(entry.context);
    const body = wordsOf(entry.body);
    let score = 0;
    let all = true;
    for (const term of terms) {
      const s = termScore(term, title, context, body);
      if (s === 0) {
        all = false;
        break;
      }
      score += s;
    }
    if (!all) continue;
    if (normalize(entry.title).includes(phrase)) score += 8;
    hits.push({ entry, score: score + KIND_BONUS[entry.kind] });
  }
  return hits.sort((a, b) => b.score - a.score || a.entry.title.length - b.entry.title.length).slice(0, limit);
}
