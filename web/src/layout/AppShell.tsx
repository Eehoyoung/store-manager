import { useEffect, useState } from "react";
import { LegalFooter } from "./LegalFooter";
import { NavLink, Outlet, useNavigate, useOutletContext } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { storesApi } from "../api/stores";
import { Button } from "../components/Button";
import { StatusBanner } from "../components/StatusBanner";

const CURRENT_STORE_KEY = "sm.currentStoreId";

interface ShellContext {
  storeId: string | null;
  setStoreId: (id: string) => void;
}

export function useShellStore(): ShellContext {
  return useOutletContext<ShellContext>();
}

function navLinkClass({ isActive }: { isActive: boolean }) {
  return ["shell__nav-link", isActive ? "shell__nav-link--active" : ""].filter(Boolean).join(" ");
}

// 항목 5개 이하의 단순 네비게이션(F5). 모바일은 하단 탭, 데스크톱은 사이드바 — 마크업은 하나, CSS 미디어쿼리로 배치만 바꾼다.
export function AppShell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [storeId, setStoreIdState] = useState<string | null>(() => localStorage.getItem(CURRENT_STORE_KEY));

  const setStoreId = (id: string) => {
    localStorage.setItem(CURRENT_STORE_KEY, id);
    setStoreIdState(id);
  };

  useEffect(() => {
    if (storeId) return;
    // 마지막으로 보던 매장이 없으면(최초 로그인 등) 첫 매장을 기본값으로 삼아 네비게이션 링크를 채운다.
    storesApi
      .list()
      .then((stores) => {
        if (stores[0]) setStoreId(stores[0].id);
      })
      .catch(() => {
        // 무시 — 매장 목록 화면에서 다시 시도하면 된다.
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeId]);

  const reviewsPath = storeId ? `/stores/${storeId}/reviews` : "/stores";
  const dashboardPath = storeId ? `/stores/${storeId}/dashboard` : "/stores";
  const personaPath = storeId ? `/stores/${storeId}/persona` : "/stores";
  const billingPath = storeId ? `/stores/${storeId}/billing` : "/stores";

  const handleLogout = async () => {
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="shell">
      <header className="shell__header">
        <span className="shell__brand">
          <img src="/sodam-review-icon.png" alt="" aria-hidden="true" />
          소담리뷰
        </span>
        {user ? (
          <div className="shell__user">
            <span>{user.name} 사장님</span>
            <Button type="button" variant="secondary" small onClick={handleLogout}>
              로그아웃
            </Button>
          </div>
        ) : null}
      </header>
      {/* 운항 상태 배너 — 셸에 고정한다. 제품의 약속이 항상 보여야 한다. */}
      <StatusBanner storeId={storeId} />
      <div className="shell__body">
        <nav className="shell__nav" aria-label="주 메뉴">
          <div className="shell__nav-group">
            <span className="shell__nav-group-title">매장 운영</span>
            <NavLink to="/stores" className={navLinkClass}>매장</NavLink>
            <NavLink to={reviewsPath} className={navLinkClass}>리뷰</NavLink>
            <NavLink to={dashboardPath} className={navLinkClass}>대시보드</NavLink>
          </div>
          <div className="shell__nav-group">
            <span className="shell__nav-group-title">답글 관리</span>
            <NavLink to={personaPath} className={navLinkClass}>페르소나</NavLink>
            <NavLink to="/platform-accounts" className={navLinkClass}>배달앱 연동</NavLink>
          </div>
          <div className="shell__nav-group shell__nav-group--account">
            <span className="shell__nav-group-title">계정</span>
            <NavLink to={billingPath} className={navLinkClass}>결제</NavLink>
            <NavLink to="/settings" className={navLinkClass}>설정</NavLink>
          </div>
        </nav>
        <main className="shell__main">
          <Outlet context={{ storeId, setStoreId } satisfies ShellContext} />
        </main>
      </div>
      <LegalFooter />
    </div>
  );
}
