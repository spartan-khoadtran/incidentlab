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
  { id: "loadgen", x: 86, y: 200, role: "users" },
  { id: "envoy", x: 285, y: 200, role: "load balancer" },
  { id: "api", x: 484, y: 200, role: "checkout" },
  { id: "redis", x: 718, y: 42, role: "product cache" },
  { id: "pricing", x: 718, y: 124, role: "downstream" },
  { id: "postgres", x: 912, y: 200, role: "database" },
  { id: "kafka", x: 680, y: 338, role: "topic orders" },
  { id: "worker", x: 912, y: 338, role: "consumer" },
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

export const NODE_W = 152;
export const NODE_H = 52;
export const VIEW_W = 1000;
export const VIEW_H = 380;
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

export function labelPoint(edge: TopoEdge): { x: number; y: number } {
  const a = node(edge.from);
  const b = node(edge.to);
  if (a.x === b.x) return { x: a.x + 12, y: (a.y + b.y) / 2 + 4 };
  const x1 = a.x + NODE_W / 2;
  const x2 = b.x - NODE_W / 2;
  return { x: (x1 + x2) / 2, y: (a.y + b.y) / 2 - 7 };
}
