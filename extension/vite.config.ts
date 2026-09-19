import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import { crx } from "@crxjs/vite-plugin";
import manifest from "./manifest.json" with { type: "json" };

// https://vite.dev/config/
export default defineConfig({
  plugins: [crx({ manifest })],
  resolve: {
    alias: {
      "@selector-spec": fileURLToPath(new URL("../packages/selector-spec/index.ts", import.meta.url)),
    },
  },
  server: {
    // packages/selector-spec 는 extension/ 바깥(모노레포 형제 디렉터리)이라
    // 기본 fs.allow(워크스페이스 루트 추정)가 못 잡을 수 있어 명시한다.
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "../packages/selector-spec/**/*.test.ts"],
  },
});
