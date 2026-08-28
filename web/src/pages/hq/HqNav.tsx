import { Link, NavLink } from "react-router-dom";

function tabClass({ isActive }: { isActive: boolean }) {
  return ["hq-tabs__link", isActive ? "hq-tabs__link--active" : ""].filter(Boolean).join(" ");
}

// 브랜드 하위 2개 화면(가맹점 현황·집계) 공용 탭 네비게이션.
// ★ WP-01(2026-08-28) — "리뷰 통합 조회" 탭을 제거했다. hq-data-sharing.md 가 본부에
// 개별 리뷰를 제공하지 않는다고 명시해 서버 엔드포인트(/hq/brands/{brand}/reviews)를
// 없앴다 — 남겨두면 이 탭이 깨진 링크가 된다.
export function HqNav({ brand }: { brand: string }) {
  const encoded = encodeURIComponent(brand);
  return (
    <nav className="hq-tabs" aria-label="가맹본부 하위 메뉴">
      <span className="hq-tabs__brand">{brand}</span>
      <NavLink to={`/hq/brands/${encoded}/stores`} className={tabClass}>
        가맹점 현황
      </NavLink>
      <NavLink to={`/hq/brands/${encoded}/analytics`} className={tabClass}>
        브랜드 집계
      </NavLink>
      <Link to="/hq/brands" className="hq-tabs__switch">
        브랜드 변경
      </Link>
    </nav>
  );
}
