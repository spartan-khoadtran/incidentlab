import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

/** `LAB_MOCK=1 npm run dev` serves a fake lab API, to work on the Play pages without Docker. */
function mockLab(): Plugin {
  return {
    name: "mock-lab",
    apply: "serve",
    async configureServer(server) {
      const { MockLab } = await import("./test/mock-lab.ts");
      const lab = new MockLab();
      server.middlewares.use("/api", (req, res) => {
        let raw = "";
        req.on("data", (chunk) => (raw += chunk));
        req.on("end", () => {
          const headers = { "content-type": req.headers["content-type"] ?? null, "x-lab-client": (req.headers["x-lab-client"] as string | undefined) ?? null };
          const reply = lab.handle(req.method ?? "GET", (req.url ?? "/").split("?")[0], headers, raw ? JSON.parse(raw) : {});
          res.statusCode = reply.status;
          res.setHeader("Content-Type", "application/json");
          res.end(JSON.stringify(reply.body));
        });
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), ...(process.env.LAB_MOCK ? [mockLab()] : [])],
  build: { target: "es2022", chunkSizeWarningLimit: 400 },
  server: {
    port: 5173,
    proxy: process.env.LAB_MOCK ? undefined : { "/api": { target: process.env.LAB_API ?? "http://localhost:8090", changeOrigin: false } },
  },
  preview: {
    proxy: { "/api": { target: process.env.LAB_API ?? "http://localhost:8090", changeOrigin: false } },
  },
  test: { include: ["test/**/*.test.{ts,tsx}"], environment: "node" },
});
