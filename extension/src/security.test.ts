/**
 * ★ 이 제품의 법적 방어선. 여기서 잡는 두 가지가 무너지면 "매크로가 아니라 도구"라는
 * 주장 전체가 무너진다 — docs/naver/03-compliance.md 참고.
 *
 * 1) 게시를 자동화할 수 있는 API 가 소스 어디에도 없다.
 * 2) manifest 가 tabs/cookies/webRequest/<all_urls> 를 요청하지 않는다.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { describe, expect, it } from "vitest";

const THIS_DIR = path.dirname(fileURLToPath(import.meta.url));
const EXTENSION_ROOT = path.join(THIS_DIR, "..");
const SRC_ROOT = path.join(EXTENSION_ROOT, "src");

const FORBIDDEN_PATTERNS = [
  /\.click\(/,
  /\.submit\(/,
  /dispatchEvent\(new MouseEvent/,
  /dispatchEvent\(new KeyboardEvent/,
  /\beval\(/,
  /new Function\(/,
];

function listSourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    const s = statSync(full);
    if (s.isDirectory()) {
      out.push(...listSourceFiles(full));
    } else if (/\.(ts|tsx)$/.test(name) && !name.endsWith(".test.ts")) {
      out.push(full);
    }
  }
  return out;
}

describe("금지 API 소스 스캔", () => {
  const files = listSourceFiles(SRC_ROOT);

  it("스캔 대상 파일이 존재한다 (빈 통과 방지)", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it.each(files.map((f) => [path.relative(SRC_ROOT, f), f] as const))(
    "%s 에는 게시 자동화·원격코드실행 API 가 없다",
    (_label, file) => {
      const content = readFileSync(file, "utf-8");
      for (const pattern of FORBIDDEN_PATTERNS) {
        expect(content).not.toMatch(pattern);
      }
    },
  );
});

describe("manifest 권한 스캔", () => {
  const manifest = JSON.parse(readFileSync(path.join(EXTENSION_ROOT, "manifest.json"), "utf-8"));

  it("permissions 에 tabs/cookies/webRequest 가 없다", () => {
    const permissions: string[] = manifest.permissions ?? [];
    expect(permissions).not.toContain("tabs");
    expect(permissions).not.toContain("cookies");
    expect(permissions).not.toContain("webRequest");
  });

  it("host_permissions 에 <all_urls> 가 없고 네이버·자사 도메인만 있다", () => {
    const hostPermissions: string[] = manifest.host_permissions ?? [];
    expect(hostPermissions).not.toContain("<all_urls>");
    expect(hostPermissions.length).toBeGreaterThan(0);
    for (const origin of hostPermissions) {
      expect(origin).not.toBe("*://*/*");
    }
  });

  it("content_scripts 는 네이버 도메인에만 매치된다", () => {
    for (const cs of manifest.content_scripts ?? []) {
      for (const match of cs.matches as string[]) {
        expect(match).toContain("smartplace.naver.com");
      }
    }
  });
});
