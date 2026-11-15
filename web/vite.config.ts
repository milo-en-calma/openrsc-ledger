import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    // Inside Gradle's build folder, so `./gradlew clean` removes it.
    outDir: "build/dist",
    emptyOutDir: true,
  },
  server: {
    // In development Vite serves the pages and forwards API calls to the ledger,
    // which listens on the port set in ledger.conf (8080 unless changed).
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
});
