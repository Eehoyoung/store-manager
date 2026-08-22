import { useEffect, useState } from "react";
import { NavLink, Outlet, useNavigate, useOutletContext } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { storesApi } from "../api/stores";
import { hqApi } from "../api/hq";
import { Button } from "../components/Button";

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
  // 본부 권한이 있을 때만 '가맹본부' 메뉴를 노출한다(U2).
  // ★ 빈 배열은 정상 응답이다 — 권한이 없는 일반 사장님이며 에러가 아니다.
  // 호출이 실패해도 앱이 깨지면 안 되므로 조용히 메뉴만 감춘다.
  const [isHq, setIsHq] = useState(false);

  useEffect(() => {
    let alive = true;
    hqApi
      .brands()
      .then((brands) => {
        if (alive) setIsHq(brands.length > 0);
      })
      .catch(() => {
        if (alive) setIsHq(false);
      });
    return () => {
      alive = false;
    };
  }, []);

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

  const handleLogout = async () => {
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="shell">
      <header className="shell__header">
        <span className="shell__brand">매장 매니저</span>
        {user ? (
          <div className="shell__user">
            <span>{user.name} 사장님</span>
            <Button type="button" variant="secondary" small onClick={handleLogout}>
              로그아웃
            </Button>
          </div>
        ) : null}
      </header>
      <div className="shell__body">
        <nav className="shell__nav" aria-label="주 메뉴">
          <NavLink to="/stores" className={navLinkClass}>
            매장
          </NavLink>
          <NavLink to={reviewsPath} className={navLinkClass}>
            리뷰
          </NavLink>
          <NavLink to={dashboardPath} className={navLinkClass}>
            대시보드
          </NavLink>
          <NavLink to={personaPath} className={navLinkClass}>
            페르소나
          </NavLink>
          <NavLink to="/platform-accounts" className={navLinkClass}>
            배달앱 연동
          </NavLink>
          {isHq ? (
            <NavLink to="/hq/brands" className={navLinkClass}>
              가맹본부
            </NavLink>
          ) : null}
          <NavLink to="/settings" className={navLinkClass}>
            설정
          </NavLink>
        </nav>
        <main className="shell__main">
          <Outlet context={{ storeId, setStoreId } satisfies ShellContext} />
        </main>
      </div>
    </div>
  );
}
