import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { MockLab } from "./mock-lab.ts";

/** An msw server whose /api routes go to a fresh MockLab, plus the list of requests it saw. */
export function mockServer() {
  const state = { lab: new MockLab(), calls: [] as { method: string; path: string; headers: Record<string, string | null> }[] };
  const server = setupServer(
    http.all("*/api/*", async ({ request }) => {
      const url = new URL(request.url);
      const headers = { "content-type": request.headers.get("content-type"), "x-lab-client": request.headers.get("x-lab-client") };
      state.calls.push({ method: request.method, path: url.pathname, headers });
      if (!state.lab.online) return HttpResponse.text("<html>502 Bad Gateway</html>", { status: 502 });
      const text = request.method === "POST" ? await request.text() : "";
      const reply = state.lab.handle(request.method, url.pathname, headers, text ? JSON.parse(text) : {});
      return HttpResponse.json(reply.body as Record<string, unknown>, { status: reply.status });
    }),
  );
  return { server, state };
}
