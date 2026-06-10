import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": {
        target: "http://100.109.237.31:8000",
        changeOrigin: true,
        secure: false,
      },
      // 추가: MJPEG 스트리밍
      "/video_feed": {
        target: "http://100.109.237.31:8000",
        changeOrigin: true,
        secure: false,
      },
      // 추가: WebSocket (앱 카메라 연결)
      "/ws": {
        target: "ws://100.109.237.31:8000",
        ws: true,
        changeOrigin: true,
        secure: false,
      },
    },
  },
});