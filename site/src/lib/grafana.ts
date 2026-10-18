import grafanaJson from "../generated/grafana.json";

interface GrafanaData {
  dashboards: { uid: string; title: string }[];
  datasources: { name: string; uid: string; type: string }[];
}

export const grafana = grafanaJson as GrafanaData;

export interface TimeRange {
  /** Epoch milliseconds, or a Grafana relative time like `now-30m`. */
  from: string;
  to: string;
}

const LEAD_SECONDS = 15 * 60;

/** From 15 minutes before the exercise started until now, so the baseline is on the same chart. */
export function exerciseRange(startedAt: number | null | undefined): TimeRange {
  if (!startedAt) return { from: "now-30m", to: "now" };
  return { from: String(Math.round((startedAt - LEAD_SECONDS) * 1000)), to: "now" };
}

export function datasource(type: string): { uid: string; type: string } | undefined {
  return grafana.datasources.find((d) => d.type === type);
}

function base(url: string): string {
  return url.replace(/\/+$/, "");
}

/** Grafana 11 Explore: `/explore?schemaVersion=1&panes={...}&orgId=1`, one pane with one query. */
export function exploreUrl(grafanaUrl: string, type: string, range: TimeRange, query: Record<string, unknown> = {}): string | null {
  const ds = datasource(type);
  if (!ds) return null;
  const panes = {
    a: {
      datasource: ds.uid,
      queries: [{ refId: "A", datasource: { type: ds.type, uid: ds.uid }, ...query }],
      range,
    },
  };
  return `${base(grafanaUrl)}/explore?schemaVersion=1&panes=${encodeURIComponent(JSON.stringify(panes))}&orgId=1`;
}

export function promqlUrl(grafanaUrl: string, expr: string, range: TimeRange): string | null {
  return exploreUrl(grafanaUrl, "prometheus", range, { expr });
}

export function dashboardUrl(grafanaUrl: string, uid: string, range: TimeRange): string {
  return `${base(grafanaUrl)}/d/${encodeURIComponent(uid)}?orgId=1&from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`;
}
