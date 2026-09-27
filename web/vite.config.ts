import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 운영 web 컨테이너는 `vite preview` 로 dist 를 내보내고 Caddy 가 앞에 선다.
  // Vite 는 모르는 Host 헤더를 403 으로 막으므로 운영 도메인을 허용한다(localhost 는 항상 허용).
  preview: { allowedHosts: ["review.sodamlabs.kr"] },
});
