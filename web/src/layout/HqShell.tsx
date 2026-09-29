import { useState } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import { hqAuthApi } from "../api/hq";
import { Button } from "../components/Button";
import { getHqIdentity, setHqIdentity } from "../auth/HqSession";
import { setAccessToken } from "../auth/tokenStore";

// 가맹본부 담당자 전용 셸 — 사장님 AppShell 과 분리한다(docs/26a web.shells).
// 브랜드 하위 탭(가맹점 현황·집계·비교·개별 리뷰·보고서)은 각 화면의 HqNav 가 맡는다.
export function HqShell() {
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);
  const identity = getHqIdentity();

  const handleLogout = async () => {
    setLoggingOut(true);
    try {
      await hqAuthApi.logout();
    } catch {
      // 서버 호출이 실패해도 로컬 세션 정리는 반드시 진행한다.
    }
    setAccessToken(null);
    setHqIdentity(null);
    navigate("/hq/login", { replace: true });
  };

  return (
    <div className="shell">
      <header className="shell__header">
        <span className="shell__brand">가맹본부</span>
        <div className="shell__user">
          {identity ? <span>{identity.name} ({identity.email})</span> : null}
          <Button type="button" variant="secondary" small loading={loggingOut} onClick={() => void handleLogout()}>
            로그아웃
          </Button>
        </div>
      </header>
      <div className="shell__body">
        <main className="shell__main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
