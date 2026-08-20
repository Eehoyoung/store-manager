import { Link, NavLink } from "react-router-dom";

function tabClass({ isActive }: { isActive: boolean }) {
  return ["hq-tabs__link", isActive ? "hq-tabs__link--active" : ""].filter(Boolean).join(" ");
}

// 브랜드 하위 3개 화면(가맹점 현황·리뷰·집계) 공용 탭 네비게이션.
export function HqNav({ brand }: { brand: string }) {
  const encoded = encodeURIComponent(brand);
  return (
    <nav className="hq-tabs" aria-label="가맹본부 하위 메뉴">
      <span className="hq-tabs__brand">{brand}</span>
      <NavLink to={`/hq/brands/${encoded}/stores`} className={tabClass}>
        가맹점 현황
      </NavLink>
      <NavLink to={`/hq/brands/${encoded}/reviews`} className={tabClass}>
        리뷰 통합 조회
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
