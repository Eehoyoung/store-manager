/**
 * 셀렉터 점검 하네스 — 저장한 네이버 리뷰 페이지 HTML 에 셀렉터 스펙을 돌려본다.
 *
 * ★ 왜 필요한가 — docs/naver/06-roadmap.md Phase 0 의 통과 조건이 "셀렉터 추출이
 *   실제로 되는지 수동으로 확인" 이다. 지금 배포되는 스펙은 문서 예시 기반 초안이라
 *   실제 페이지에서는 0건이 잡힌다. 확장을 크롬에 올려서 확인하려면 서버 기동·페어링·
 *   구독까지 다 필요한데, 정작 확인하려는 것(DOM 에서 리뷰가 뽑히는가)은 그 전 단계다.
 *   이 스크립트는 그 한 단계만 떼어내 브라우저도 서버도 네이버 접속도 없이 확인한다.
 *
 * ★ 네이버에 요청을 보내지 않는다. 이미 저장된 파일만 읽는다(절대 규칙 1·3).
 *
 * 사용법:
 *   npm run check-selectors -- <page.html> [spec.json]
 *
 *   ★ node 로 직접 부르지 마라 — src/*.ts 를 임포트하므로 --experimental-strip-types
 *     가 필요하고, 그 플래그는 package.json 의 이 스크립트에만 붙어 있다.
 *
 *   page.html  크롬 개발자도구에서 리뷰 목록을 감싸는 요소를 우클릭 → Copy → Copy outerHTML
 *              해서 붙여넣은 파일. 페이지 전체를 Ctrl+S 로 저장한 것도 된다.
 *   spec.json  생략하면 서버가 배포하는 api-spring/.../naver/selector-spec.json 을 쓴다.
 *
 * 출력은 두 가지다.
 *   1) 추출 결과 — 컨테이너·항목이 잡혔는가, 필드별로 몇 건이 비었는가
 *   2) 서버로 나갈 payload — 브라우저 로컬 마스킹을 실제로 통과시킨 모습.
 *      원문이 아니라 "전송되는 것" 을 보여준다. 마스킹이 새는지 여기서 바로 보인다.
 */
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";

import { extractReviews } from "../src/selector/runtime.ts";
import { maskReviewBody, hashAuthor, reviewHash } from "../src/masking/mask.ts";
import { parseReviewDates, reviewIdentity } from "../src/selector/identity.ts";
import { deriveHasReply } from "../src/selector/replyState.ts";
import { validateSelectorSpec } from "../../packages/selector-spec/index.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_SPEC = resolve(
  HERE,
  "../../api-spring/src/main/resources/naver/selector-spec.json",
);

const [htmlPath, specPath = DEFAULT_SPEC] = process.argv.slice(2);
if (!htmlPath) {
  console.error("사용법: npm run check-selectors -- <page.html> [spec.json]");
  process.exit(2);
}

const raw = JSON.parse(readFileSync(resolve(specPath), "utf-8"));
const checked = validateSelectorSpec(raw);
if (!checked.ok) {
  console.error("✗ 스펙이 유효하지 않다:");
  for (const e of checked.errors) console.error("   -", e);
  process.exit(1);
}
const spec = checked.spec;

const dom = new JSDOM(readFileSync(resolve(htmlPath), "utf-8"));
// crypto.subtle 은 마스킹(SHA-256)에 필요하다. jsdom 에는 없으므로 Node 것을 빌려준다.
globalThis.crypto ??= (await import("node:crypto")).webcrypto;

console.log(`스펙   : ${specPath}`);
console.log(`HTML   : ${htmlPath}`);
console.log(`버전   : ${spec.version} (minExtensionVersion ${spec.minExtensionVersion})\n`);

let anyFound = false;
for (const [pageName, page] of Object.entries(spec.pages)) {
  console.log(`── 페이지 "${pageName}" (match: ${page.match}) ─────────────`);
  const { items, misses } = extractReviews(dom.window.document, page);

  if (misses.includes("container")) {
    console.log(`  ✗ container 를 못 찾았다 → ${page.container}`);
    console.log(`    리뷰 목록을 감싸는 요소의 셀렉터를 실제 DOM 에서 다시 떠야 한다.\n`);
    continue;
  }
  if (misses.includes("item")) {
    console.log(`  ✓ container 는 찾았다 → ${page.container}`);
    console.log(`  ✗ item 이 0건이다 → ${page.item}\n`);
    continue;
  }

  anyFound = true;
  console.log(`  ✓ 리뷰 ${items.length}건 추출\n`);

  // 필드별 결손 집계 — 어떤 셀렉터를 고쳐야 하는지 한눈에 보이게 한다.
  const fieldNames = Object.keys(page.fields);
  console.log("  [필드별 결손]");
  for (const f of fieldNames) {
    const empty = items.filter((it) => it[f] === null || it[f] === undefined || it[f] === "").length;
    const mark = empty === 0 ? "✓" : empty === items.length ? "✗" : "△";
    console.log(
      `    ${mark} ${f.padEnd(12)} 빈 값 ${String(empty).padStart(3)}/${items.length}  ${page.fields[f].selector}`,
    );
  }

  // ★ 해시 충돌은 조용한 사고다 — 두 리뷰가 같은 해시를 받으면 두 번째는 중복으로
  //   취급돼 영영 답글을 못 받는다. 전건을 훑어 먼저 확인한다.
  const seen = new Map();
  let unidentified = 0;
  for (const it of items) {
    const { writtenAt, visitedAt } = parseReviewDates(it.dateBlock);
    const identity = reviewIdentity(it.authorRef, writtenAt, visitedAt);
    if (!identity) {
      unidentified += 1;
      continue;
    }
    const h = await reviewHash(identity, "demo-store");
    seen.set(h, (seen.get(h) ?? 0) + 1);
  }
  const collisions = [...seen.values()].filter((n) => n > 1).length;
  const replied = items.filter((it) => deriveHasReply(it.replyWriteButton)).length;
  console.log("\n  [식별자 점검]");
  console.log(`    · 이미 답글 달림 ${replied}/${items.length}건 → 초안 대상은 ${items.length - replied}건`);
  console.log(`    ${unidentified === 0 ? "✓" : "✗"} 식별 불가 ${unidentified}/${items.length}건`
    + (unidentified ? "  ← authorRef 나 작성일을 못 읽었다. 이 건들은 처리되지 않는다." : ""));
  console.log(`    ${collisions === 0 ? "✓" : "✗"} 해시 충돌 ${collisions}건`
    + (collisions ? "  ← 서로 다른 리뷰가 같은 해시다. 두 번째부터 답글을 못 받는다." : ""));

  console.log("\n  [서버로 전송될 payload — 앞 3건]");
  for (const it of items.slice(0, 3)) {
    const body = maskReviewBody(String(it.body ?? ""));
    const author = it.authorName ? await hashAuthor(String(it.authorName), "demo-salt") : null;
    const { visitedAt, writtenAt } = parseReviewDates(it.dateBlock);
    const identity = reviewIdentity(it.authorRef, writtenAt, visitedAt);
    const hash = identity ? await reviewHash(identity, "demo-store") : null;
    console.log(`    reviewHash : ${hash ? hash.slice(0, 16) + "…" : "(식별 불가 — 건너뜀)"}`);
    console.log(`    rating     : ${it.rating ?? "(없음)"}`);
    console.log(`    writtenAt  : ${writtenAt ?? "(없음)"}   방문일 ${visitedAt ?? "(없음)"}`);
    console.log(`    hasReply   : ${deriveHasReply(it.replyWriteButton)}   ← "답글 쓰기" 버튼의 부재로 판정`);
    console.log(`    authorHash : ${author ?? "(없음)"}   ← 원본 닉네임은 전송되지 않는다`);
    console.log(`    body       : ${body.slice(0, 80)}${body.length > 80 ? "…" : ""}`);
    console.log("");
  }
}

if (!anyFound) {
  console.log("아무것도 추출되지 않았다. 셀렉터를 실제 DOM 기준으로 갱신해야 한다.");
  console.log("개발자도구 Elements 에서 리뷰 목록 컨테이너와 리뷰 한 건의 셀렉터를 확인할 것.");
  process.exit(1);
}
