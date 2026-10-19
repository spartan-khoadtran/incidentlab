/** Where each service of the request path sits in the diagram. The facts on each node come from docker-compose.yml. */
export interface TopoNode {
  id: string;
  x: number;
  y: number;
  role: string;
}

export interface TopoEdge {
  from: string;
  to: string;
  label: string;
  stage: number;
  /** The compose variable that names this hop, when there is one. */
  evidence?: { service: string; env: string };
}

export const NODES: TopoNode[] = [
  { id: "loadgen", x: 98, y: 214, role: "users" },
  { id: "envoy", x: 316, y: 214, role: "load balancer" },
  { id: "api", x: 534, y: 214, role: "checkout" },
  { id: "redis", x: 872, y: 44, role: "product cache" },
  { id: "pricing", x: 872, y: 128, role: "downstream" },
  { id: "postgres", x: 1100, y: 214, role: "database" },
  { id: "kafka", x: 872, y: 350, role: "topic orders" },
  { id: "worker", x: 1100, y: 350, role: "consumer" },
];

export const EDGES: TopoEdge[] = [
  { from: "loadgen", to: "envoy", label: "HTTP", stage: 0, evidence: { service: "loadgen", env: "TARGET_URL" } },
  { from: "envoy", to: "api", label: "proxy", stage: 1 },
  { from: "api", to: "redis", label: "cache", stage: 2, evidence: { service: "api", env: "REDIS_HOST" } },
  { from: "api", to: "pricing", label: "/price", stage: 2, evidence: { service: "api", env: "PRICING_URL" } },
  { from: "api", to: "postgres", label: "pool", stage: 2 },
  { from: "api", to: "kafka", label: "produce", stage: 2 },
  { from: "kafka", to: "worker", label: "consume", stage: 3 },
  { from: "worker", to: "postgres", label: "mark done", stage: 4 },
];

/** The facts line is cut to this many characters, so a fixed box width always holds it. */
export const FACTS_MAX = 22;
/** JetBrains Mono advances 0.6 em; the facts line is 10.5 px. */
export const FACTS_CHAR_W = 6.3;
export const LABEL_CHAR_W = 6.6;
export const NODE_PAD = 12;
export const NODE_W = Math.ceil(2 * NODE_PAD + FACTS_MAX * FACTS_CHAR_W) + 8;
export const NODE_H = 52;
export const VIEW_W = 1192;
export const VIEW_H = 392;
export const STAGES = 5;

export function node(id: string): TopoNode {
  const n = NODES.find((x) => x.id === id);
  if (!n) throw new Error(`no node ${id}`);
  return n;
}

export function edgePath(edge: TopoEdge): string {
  const a = node(edge.from);
  const b = node(edge.to);
  if (a.x === b.x) {
    const up = b.y < a.y;
    const y1 = a.y + (up ? -NODE_H / 2 : NODE_H / 2);
    const y2 = b.y + (up ? NODE_H / 2 : -NODE_H / 2);
    return `M ${a.x} ${y1} L ${b.x} ${y2}`;
  }
  const x1 = a.x + NODE_W / 2;
  const x2 = b.x - NODE_W / 2;
  const mid = (x1 + x2) / 2;
  return `M ${x1} ${a.y} C ${mid} ${a.y}, ${mid} ${b.y}, ${x2} ${b.y}`;
}

export interface LabelPlace {
  x: number;
  y: number;
  anchor: "start" | "end";
}

/**
 * A label sits just before the arrow head, where every curve has gone flat, on the side the line
 * does not come from: below a line that comes down, above any other.
 */
export function labelPoint(edge: TopoEdge): LabelPlace {
  const a = node(edge.from);
  const b = node(edge.to);
  if (a.x === b.x) return { x: a.x + 8, y: (a.y + b.y) / 2 + 4, anchor: "start" };
  return { x: b.x - NODE_W / 2 - 6, y: b.y > a.y ? b.y + 15 : b.y - 7, anchor: "end" };
}

/** The box a label takes, for 11 px mono text with the baseline at y. */
export function labelBox(edge: TopoEdge): { x1: number; y1: number; x2: number; y2: number } {
  const p = labelPoint(edge);
  const w = edge.label.length * LABEL_CHAR_W;
  const x1 = p.anchor === "end" ? p.x - w : p.x;
  return { x1, y1: p.y - 9, x2: x1 + w, y2: p.y + 2 };
}

/** Points along an edge, for the overlap test. */
export function edgePoints(edge: TopoEdge, steps = 200): { x: number; y: number }[] {
  const nums = edgePath(edge).match(/-?\d+(\.\d+)?/g)!.map(Number);
  if (nums.length === 4) {
    const [x1, y1, x2, y2] = nums;
    return Array.from({ length: steps + 1 }, (_, i) => ({ x: x1 + ((x2 - x1) * i) / steps, y: y1 + ((y2 - y1) * i) / steps }));
  }
  const [x0, y0, cx1, cy1, cx2, cy2, x3, y3] = nums;
  return Array.from({ length: steps + 1 }, (_, i) => {
    const t = i / steps;
    const u = 1 - t;
    return {
      x: u * u * u * x0 + 3 * u * u * t * cx1 + 3 * u * t * t * cx2 + t * t * t * x3,
      y: u * u * u * y0 + 3 * u * u * t * cy1 + 3 * u * t * t * cy2 + t * t * t * y3,
    };
  });
}
