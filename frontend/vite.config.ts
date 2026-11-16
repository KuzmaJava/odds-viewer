import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base "./" — сайт открывается из подпапки GitHub Pages (/odds-viewer/), data.json лежит рядом с index.html.
// В режиме разработки /api проксируется на локальный FastAPI (uvicorn app.main:app --port 8077).
export default defineConfig({
  base: "./",
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8077" } },
});
