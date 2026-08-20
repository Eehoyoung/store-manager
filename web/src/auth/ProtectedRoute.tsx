import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext";

/** 로그인하지 않은 사용자를 /login 으로 보낸다. 원래 가려던 경로는 state.from 으로 넘겨 로그인 후 복귀시킨다. */
export function ProtectedRoute() {
  const { user, status } = useAuth();
  const location = useLocation();

  if (status === "checking") {
    return (
      <div className="page-loading" role="status">
        불러오는 중입니다…
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}
