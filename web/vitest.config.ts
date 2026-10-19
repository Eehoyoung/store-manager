import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// 단위 테스트 전용 설정 — 빌드용 vite.config.ts 와 분리해 vite 버전 타입 충돌을 피한다.
export default defineConfig({ plugins: [react()], test: { environment: "jsdom" } });
