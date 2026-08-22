import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { apiRequest } from "../api/client";
import { onForceLogout, setAccessToken } from "./tokenStore";
import type { AuthResponse, UserSummary } from "../api/types";

export interface SignupPayload {
  email: string;
  password: string;
  name: string;
  phone?: string;
  franchiseCode?: string;
  storeName: string;
  storeAddress: string;
}

interface AuthContextValue {
  user: UserSummary | null;
  /** "checking" 인 동안은 새로고침 복구(refresh) 가 진행 중이라 로그인 여부를 아직 모른다. */
  status: "checking" | "ready";
  login: (email: string, password: string) => Promise<void>;
  signup: (payload: SignupPayload) => Promise<void>;
  updateUser: (user: UserSummary) => void;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null);
  const [status, setStatus] = useState<"checking" | "ready">("checking");

  // refresh 가 결국 실패해 강제 로그아웃된 경우(api/client.ts) 화면 상태도 비운다.
  useEffect(() => onForceLogout(() => setUser(null)), []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        // accessToken 은 메모리에만 있어 새로고침하면 사라진다 — HttpOnly refresh 쿠키로 세션을 되살린다.
        const res = await apiRequest<AuthResponse>("/auth/refresh", { method: "POST" });
        if (!cancelled) {
          setAccessToken(res.accessToken);
          setUser(res.user);
        }
      } catch {
        // 로그인 이력이 없거나 쿠키가 만료된 정상적인 경우. 조용히 로그아웃 상태로 둔다.
      } finally {
        if (!cancelled) setStatus("ready");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = async (email: string, password: string) => {
    const res = await apiRequest<AuthResponse>("/auth/login", { method: "POST", body: { email, password } });
    setAccessToken(res.accessToken);
    setUser(res.user);
  };

  const signup = async (payload: SignupPayload) => {
    const res = await apiRequest<AuthResponse>("/auth/signup", { method: "POST", body: payload });
    setAccessToken(res.accessToken);
    setUser(res.user);
  };

  const updateUser = (nextUser: UserSummary) => setUser(nextUser);

  const logout = async () => {
    try {
      await apiRequest<void>("/auth/logout", { method: "POST" });
    } catch {
      // 서버 호출이 실패해도 로컬 세션 정리는 반드시 진행한다.
    }
    setAccessToken(null);
    setUser(null);
  };

  const value = useMemo(() => ({ user, status, login, signup, updateUser, logout }), [user, status]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth 는 AuthProvider 내부에서만 사용할 수 있습니다.");
  return ctx;
}
