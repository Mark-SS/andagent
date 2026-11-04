import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react()],
  build: {
    outDir: "../app/src/main/assets/web",
    emptyOutDir: true,
    target: "es2020",
    chunkSizeWarningLimit: 8000,
  },
});
