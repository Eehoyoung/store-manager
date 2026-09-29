import { useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { adminAuthApi } from "../api/admin";
import { Button } from "../components/Button";
import { getAdminEmail, setAdminEmail } from "../auth/AdminSession";
import { setAccessToken } from "../auth/tokenStore";

function navLinkClass({ isActive }: { isActive: boolean }) {
  return ["shell__nav-link", isActive ? "shell__nav-link--active" : ""].filter(Boolean).join(" ");
}

// 시스템 관리자 전용 셸 — 사장님 AppShell 과 분리한다(docs/26a web.shells).
// 관리자는 답글·매장 화면에 접근할 일이 없으므로 메뉴도 가맹본부 관리로만 좁힌다.
export function AdminShell() {
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);

  const handleLogout = async () => {
    setLoggingOut(true);
    try {
      await adminAuthApi.logout();
    } catch {
      // 서버 호출이 실패해도 로컬 세션 정리는 반드시 진행한다.
    }
    setAccessToken(null);
    setAdminEmail(null);
    navigate("/admin/login", { replace: true });
  };

  return (
    <div className="shell">
      <header className="shell__header">
        <span className="shell__brand">시스템 콘솔</span>
        <div className="shell__user">
          {getAdminEmail() ? <span>{getAdminEmail()}</span> : null}
          <Button type="button" variant="secondary" small loading={loggingOut} onClick={() => void handleLogout()}>
            로그아웃
          </Button>
        </div>
      </header>
      <div className="shell__body">
        <nav className="shell__nav" aria-label="시스템 콘솔 메뉴">
          <div className="shell__nav-group">
            <span className="shell__nav-group-title">가맹본부</span>
            <NavLink to="/admin/franchises" className={navLinkClass}>본부 관리</NavLink>
            <NavLink to="/admin/affiliations" className={navLinkClass}>소속 승인·해제</NavLink>
          </div>
          <div className="shell__nav-group">
            <span className="shell__nav-group-title">운영</span>
            <NavLink to="/admin/subscriptions" className={navLinkClass}>서비스 상태</NavLink>
            <NavLink to="/admin/failures" className={navLinkClass}>실패 건</NavLink>
          </div>
        </nav>
        <main className="shell__main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
