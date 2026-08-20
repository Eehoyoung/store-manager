import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext";

/** 로그인/회원가입 화면. 이미 로그인된 사용자는 매장 목록으로 보낸다. */
export function PublicOnlyRoute() {
  const { user, status } = useAuth();
  if (status === "checking") {
    return (
      <div className="page-loading" role="status">
        불러오는 중입니다…
      </div>
    );
  }
  if (user) {
    return <Navigate to="/stores" replace />;
  }
  return <Outlet />;
}
