import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  build: { target: "es2022", chunkSizeWarningLimit: 400 },
  server: {
    port: 5173,
    proxy: { "/api": process.env.LAB_API ?? "http://localhost:8090" },
  },
  test: { include: ["test/**/*.test.ts"], environment: "node" },
});
