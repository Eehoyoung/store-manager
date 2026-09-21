import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import { crx } from "@crxjs/vite-plugin";
import manifest from "./manifest.json" with { type: "json" };

// https://vite.dev/config/
export function manifestForMode(mode: string) {
  if (mode !== "production") return manifest;
  return {
    ...manifest,
    // 개발 편의를 위한 localhost 권한을 Chrome Web Store 배포물에는 싣지 않는다.
    host_permissions: manifest.host_permissions.filter((origin) => !origin.startsWith("http://localhost")),
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [crx({ manifest: manifestForMode(mode) })],
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
}));
