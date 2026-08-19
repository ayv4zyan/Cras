/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  envDir: path.resolve(__dirname, ".."),
  envPrefix: ["VITE_", "SUPABASE_"],
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  test: {
    globals: true,
    environment: "happy-dom",
    setupFiles: "./src/test/setup.ts",
  },
});
