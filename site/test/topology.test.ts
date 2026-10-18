import { describe, expect, it } from "vitest";
import { parseCompose } from "../scripts/lib/compose.ts";
import { facts } from "../src/lib/facts.ts";
import { EDGES, edgePoints, FACTS_CHAR_W, FACTS_MAX, labelBox, NODE_H, NODE_PAD, NODE_W, NODES, VIEW_H, VIEW_W } from "../src/lib/topology.ts";
import { repoFile } from "./paths.ts";

type Box = { x1: number; y1: number; x2: number; y2: number };

const MARGIN = 2;
const overlaps = (a: Box, b: Box) => a.x1 < b.x2 + MARGIN && b.x1 < a.x2 + MARGIN && a.y1 < b.y2 + MARGIN && b.y1 < a.y2 + MARGIN;
const inside = (p: { x: number; y: number }, b: Box) => p.x > b.x1 - MARGIN && p.x < b.x2 + MARGIN && p.y > b.y1 - MARGIN && p.y < b.y2 + MARGIN;
const nodeBox = (n: (typeof NODES)[number]): Box => ({ x1: n.x - NODE_W / 2, y1: n.y - NODE_H / 2, x2: n.x + NODE_W / 2, y2: n.y + NODE_H / 2 });

describe("request path diagram", () => {
  const compose = parseCompose(repoFile("docker-compose.yml"));

  it("fits every facts line of docker-compose.yml inside its box", () => {
    for (const n of NODES) {
      const line = facts(compose.services.find((s) => s.name === n.id));
      expect(line.length, `${n.id}: ${line}`).toBeLessThanOrEqual(FACTS_MAX);
      expect(NODE_PAD + line.length * FACTS_CHAR_W, n.id).toBeLessThan(NODE_W - NODE_PAD / 2);
    }
  });

  it("keeps every box and label inside the view", () => {
    const boxes = [...NODES.map(nodeBox), ...EDGES.map(labelBox)];
    for (const b of boxes) {
      expect(b.x1).toBeGreaterThanOrEqual(0);
      expect(b.y1).toBeGreaterThanOrEqual(0);
      expect(b.x2).toBeLessThanOrEqual(VIEW_W);
      expect(b.y2).toBeLessThanOrEqual(VIEW_H);
    }
  });

  it("puts no label on a line, a box or another label", () => {
    const problems: string[] = [];
    EDGES.forEach((e, i) => {
      const box = labelBox(e);
      for (const n of NODES) if (overlaps(box, nodeBox(n))) problems.push(`${e.label} on box ${n.id}`);
      EDGES.forEach((other, j) => {
        if (j > i && overlaps(box, labelBox(other))) problems.push(`${e.label} on label ${other.label}`);
        if (edgePoints(other).some((p) => inside(p, box))) problems.push(`${e.label} on line ${other.from}->${other.to}`);
      });
    });
    expect(problems).toEqual([]);
  });

  it("draws no two boxes on top of each other", () => {
    const boxes = NODES.map(nodeBox);
    const hits = boxes.flatMap((a, i) => boxes.slice(i + 1).filter((b) => overlaps(a, b)).map(() => NODES[i].id));
    expect(hits).toEqual([]);
  });
});
