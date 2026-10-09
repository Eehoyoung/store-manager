import { useEffect, useState } from "react";
import { Navigate, Outlet } from "react-router-dom";
import { apiRequest } from "../api/client";
import type { OtpVerifyResponse } from "../api/types";
import { getAccessToken, onForceLogout, setAccessToken } from "./tokenStore";

// 관리자 세션은 이메일만 알고 이름이 없다(app_user 가 없다 — docs/26a decisions.admin).
// 로그인·새로고침 복구 시점에 확인한 이메일을 셸 표시용으로 메모리에 들고 있는다.
let adminEmail: string | null = null;
export function setAdminEmail(email: string | null): void {
  adminEmail = email;
}
export function getAdminEmail(): string | null {
  return adminEmail;
}

/** /admin/** 접근 가드. 일반(USER) 세션으로는 통과하지 못한다(서버가 SESSION_ADMIN 을 요구). */
export function AdminProtectedRoute() {
  const [status, setStatus] = useState<"checking" | "ready">(getAccessToken() ? "ready" : "checking");
  const [authed, setAuthed] = useState(Boolean(getAccessToken()) /* sessionKind 는 로그인 시점에만 ADMIN 이 된다 */);

  useEffect(() => {
    const off = onForceLogout(() => setAuthed(false));
    if (getAccessToken()) {
      setAuthed(true);
      setStatus("ready");
      return off;
    }
    apiRequest<OtpVerifyResponse>("/admin-auth/refresh", { method: "POST" })
      .then((res) => {
        setAccessToken(res.accessToken, "ADMIN");
        setAuthed(true);
      })
      .catch(() => setAuthed(false))
      .finally(() => setStatus("ready"));
    return off;
  }, []);

  if (status === "checking") {
    return (
      <div className="page-loading" role="status">
        불러오는 중이에요…
      </div>
    );
  }
  if (!authed) return <Navigate to="/admin/login" replace />;
  return <Outlet />;
}
