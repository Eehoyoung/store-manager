// `vite build` 결과(dist/index.html)를 틀로 삼아 소개 페이지를 정적 HTML(dist/intro.html)로 만든다.
// 네이버 크롤러는 JS 를 늦게 렌더링하므로 본문이 첫 응답 HTML 에 있어야 한다.
// Caddy 가 `/`·`/open30` 을 /intro.html 로 돌린다(deploy/Caddyfile). 나머지 경로는 noindex 인 빈 셸을 받는다.
import { readFile, rm, writeFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

const SITE = "https://review.sodamlabs.kr/";
const OG_IMAGE = `${SITE}og.png`;
const dist = fileURLToPath(new URL("../dist/", import.meta.url));
const ssrDir = fileURLToPath(new URL("../dist-ssr/", import.meta.url));

const { render, INTRO_TITLE, INTRO_DESCRIPTION } = await import(pathToFileURL(`${ssrDir}entry-server.js`).href);

const esc = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const ogDescription = "배달앱 리뷰에 매장 말투로 답글을 준비하고, 민감한 리뷰에서는 자동 게시를 멈춥니다.";

// 가격·평점은 넣지 않는다(프로모션 조건이 바뀌면 구조화 데이터가 먼저 거짓이 된다).
const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      "@id": "https://sodamlabs.kr/#organization",
      name: "소담랩스",
      alternateName: "SODAM LABS",
      url: "https://sodamlabs.kr/",
    },
    {
      "@type": "WebPage",
      "@id": `${SITE}#webpage`,
      url: SITE,
      name: INTRO_TITLE,
      description: INTRO_DESCRIPTION,
      inLanguage: "ko-KR",
      about: { "@id": `${SITE}#service` },
      primaryImageOfPage: OG_IMAGE,
      publisher: { "@id": "https://sodamlabs.kr/#organization" },
    },
    {
      "@type": "Service",
      "@id": `${SITE}#service`,
      name: "소담리뷰",
      serviceType: "배달 리뷰 AI 답글 관리",
      description:
        "배달앱 리뷰 수집과 위험도 분류, 매장 말투를 반영한 AI 답글 초안, 안전 검사를 통과한 답글의 예약 게시, 민감한 리뷰의 자동 게시 차단을 제공합니다.",
      provider: { "@id": "https://sodamlabs.kr/#organization" },
      areaServed: { "@type": "Country", name: "대한민국" },
      audience: { "@type": "BusinessAudience", audienceType: "배달 매장 사장님, 프랜차이즈 가맹점" },
      url: SITE,
    },
  ],
};

const head = [
  `<link rel="canonical" href="${SITE}" />`,
  `<meta property="og:site_name" content="소담리뷰" />`,
  `<meta property="og:locale" content="ko_KR" />`,
  `<meta property="og:type" content="website" />`,
  `<meta property="og:title" content="${esc(INTRO_TITLE)}" />`,
  `<meta property="og:description" content="${esc(ogDescription)}" />`,
  `<meta property="og:url" content="${SITE}" />`,
  `<meta property="og:image" content="${OG_IMAGE}" />`,
  `<meta property="og:image:width" content="1200" />`,
  `<meta property="og:image:height" content="630" />`,
  `<meta property="og:image:alt" content="소담리뷰 - 매장 말투로 답글 초안을 만들고 민감한 리뷰는 자동 게시를 멈춰요" />`,
  `<meta name="twitter:card" content="summary_large_image" />`,
  `<meta name="twitter:title" content="${esc(INTRO_TITLE)}" />`,
  `<meta name="twitter:description" content="${esc(ogDescription)}" />`,
  `<meta name="twitter:image" content="${OG_IMAGE}" />`,
  `<script type="application/ld+json">${JSON.stringify(jsonLd).replace(/</g, "\u003c")}</script>`,
].join("\n    ");

const shell = await readFile(`${dist}index.html`, "utf8");
let html = shell;
// 셸의 값과 정확히 맞아야 치환된다. index.html 을 바꿔 치환이 빠지면 빌드를 실패시킨다.
const swap = (pattern, value, label) => {
  if (!pattern.test(html)) throw new Error(`prerender: index.html 에서 ${label} 를 찾지 못했습니다.`);
  html = html.replace(pattern, value);
};
swap(/<title>[\s\S]*?<\/title>/, `<title>${esc(INTRO_TITLE)}</title>`, "<title>");
swap(/<meta\s+name="description"[^>]*>/, `<meta name="description" content="${esc(INTRO_DESCRIPTION)}" />`, "description");
swap(/<meta\s+name="robots"[^>]*>/, `<meta name="robots" content="index, follow" />\n    ${head}`, "robots meta");
swap(/<div id="root"><\/div>/, () => `<div id="root">${render("/")}</div>`, '<div id="root">');

await writeFile(`${dist}intro.html`, html);
await rm(ssrDir, { recursive: true, force: true });
console.log("prerender: dist/intro.html");
