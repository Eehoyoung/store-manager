import { useEffect, useState } from "react";
import { Navigate, Outlet } from "react-router-dom";
import { apiRequest } from "../api/client";
import type { OtpVerifyResponse } from "../api/types";
import { getAccessToken, onForceLogout, setAccessToken } from "./tokenStore";

// verify/refresh 가 줄 때만 채워지는 표시용 신원 정보(문서 26a 응답 user:{name,email}).
let hqIdentity: { name: string; email: string } | null = null;
export function setHqIdentity(identity: { name: string; email: string } | null): void {
  hqIdentity = identity;
}
export function getHqIdentity(): { name: string; email: string } | null {
  return hqIdentity;
}

/** /hq/** 접근 가드. 일반(USER) 세션으로는 통과하지 못한다(서버가 SESSION_HQ 를 요구). */
export function HqProtectedRoute() {
  const [status, setStatus] = useState<"checking" | "ready">(getAccessToken() ? "ready" : "checking");
  const [authed, setAuthed] = useState(Boolean(getAccessToken()));

  useEffect(() => {
    const off = onForceLogout(() => setAuthed(false));
    if (getAccessToken()) {
      setAuthed(true);
      setStatus("ready");
      return off;
    }
    apiRequest<OtpVerifyResponse>("/hq-auth/refresh", { method: "POST" })
      .then((res) => {
        setAccessToken(res.accessToken, "HQ");
        if (res.user) setHqIdentity(res.user);
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
  if (!authed) return <Navigate to="/hq/login" replace />;
  return <Outlet />;
}
