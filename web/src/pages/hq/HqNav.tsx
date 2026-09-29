import { useEffect, useState } from "react";
import { Link, NavLink } from "react-router-dom";
import { hqApi } from "../../api/hq";

function tabClass({ isActive }: { isActive: boolean }) {
  return ["hq-tabs__link", isActive ? "hq-tabs__link--active" : ""].filter(Boolean).join(" ");
}

// 브랜드 하위 화면(홈·가맹점 현황·집계·비교·개별 리뷰·보고서) 공용 탭 네비게이션.
// ★ 개별 리뷰 탭은 HQ_REVIEW_ACCESS_ENABLED 가 꺼져 있으면 숨긴다(docs/26a decisions.reviewFlag).
export function HqNav({ brand }: { brand: string }) {
  const encoded = encodeURIComponent(brand);
  const [reviewAccessEnabled, setReviewAccessEnabled] = useState(false);

  useEffect(() => {
    let alive = true;
    hqApi
      .features()
      .then((f) => {
        if (alive) setReviewAccessEnabled(f.reviewAccessEnabled);
      })
      .catch(() => {
        if (alive) setReviewAccessEnabled(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <nav className="hq-tabs" aria-label="가맹본부 하위 메뉴">
      <span className="hq-tabs__brand">{brand}</span>
      <NavLink to={`/hq/brands/${encoded}`} end className={tabClass}>
        홈
      </NavLink>
      <NavLink to={`/hq/brands/${encoded}/stores`} className={tabClass}>
        가맹점 현황
      </NavLink>
      <NavLink to={`/hq/brands/${encoded}/analytics`} className={tabClass}>
        브랜드 집계
      </NavLink>
      <NavLink to={`/hq/brands/${encoded}/compare`} className={tabClass}>
        매장 비교
      </NavLink>
      {reviewAccessEnabled ? (
        <NavLink to={`/hq/brands/${encoded}/reviews`} className={tabClass}>
          개별 리뷰
        </NavLink>
      ) : null}
      <NavLink to={`/hq/brands/${encoded}/report`} className={tabClass}>
        보고서
      </NavLink>
      <Link to="/hq/brands" className="hq-tabs__switch">
        브랜드 변경
      </Link>
    </nav>
  );
}
