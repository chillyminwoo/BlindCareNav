import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "https://resisting-espionage-stallion.ngrok-free.dev",
        changeOrigin: true,
        secure: false,
        headers: { "ngrok-skip-browser-warning": "true" },
      },
      "/video_feed": {
        target: "https://resisting-espionage-stallion.ngrok-free.dev",
        changeOrigin: true,
        secure: false,
        headers: { "ngrok-skip-browser-warning": "true" },
      },
    },
  },
});