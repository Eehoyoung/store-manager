import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import {
  validateSelectorSpec,
  isCompatible,
  ROOT_REQUIRED_KEYS,
  PAGE_REQUIRED_KEYS,
} from "./index.ts";

const __dirname = dirname(fileURLToPath(import.meta.url));
const fixturesDir = join(__dirname, "..", "fixtures");
const schemaPath = join(__dirname, "..", "schema", "selector-spec.schema.json");

function readFixture(name: string): unknown {
  return JSON.parse(readFileSync(join(fixturesDir, name), "utf-8"));
}

describe("validateSelectorSpec — 픽스처", () => {
  const files = readdirSync(fixturesDir);
  const validFiles = files.filter((f) => f.startsWith("valid-"));
  const invalidFiles = files.filter((f) => f.startsWith("invalid-"));

  it("valid-*, invalid-* 픽스처가 하나 이상씩 존재한다", () => {
    expect(validFiles.length).toBeGreaterThan(0);
    expect(invalidFiles.length).toBeGreaterThan(0);
  });

  for (const file of validFiles) {
    it(`${file}: ok:true`, () => {
      const result = validateSelectorSpec(readFixture(file));
      expect(result.ok).toBe(true);
    });
  }

  for (const file of invalidFiles) {
    it(`${file}: ok:false`, () => {
      const result = validateSelectorSpec(readFixture(file));
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.errors.length).toBeGreaterThan(0);
      }
    });
  }
});

describe("스키마 ↔ 검증기 required 키 대조", () => {
  const schema = JSON.parse(readFileSync(schemaPath, "utf-8"));

  it("최상위 required 가 ROOT_REQUIRED_KEYS 와 일치한다", () => {
    expect(new Set(schema.required)).toEqual(new Set(ROOT_REQUIRED_KEYS));
  });

  it("$defs.pageSpec.required 가 PAGE_REQUIRED_KEYS 와 일치한다", () => {
    expect(new Set(schema.$defs.pageSpec.required)).toEqual(new Set(PAGE_REQUIRED_KEYS));
  });
});

describe("isCompatible", () => {
  const spec = { minExtensionVersion: "0.2.0" };

  it("확장 버전이 더 높으면 호환", () => {
    expect(isCompatible(spec, "0.3.0")).toBe(true);
  });

  it("확장 버전이 같으면 호환", () => {
    expect(isCompatible(spec, "0.2.0")).toBe(true);
  });

  it("확장 버전이 더 낮으면 비호환", () => {
    expect(isCompatible(spec, "0.1.9")).toBe(false);
  });

  it("patch 버전 차이도 비교한다", () => {
    expect(isCompatible({ minExtensionVersion: "1.2.3" }, "1.2.2")).toBe(false);
    expect(isCompatible({ minExtensionVersion: "1.2.3" }, "1.2.4")).toBe(true);
  });
});
