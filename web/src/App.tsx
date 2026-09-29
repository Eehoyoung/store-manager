import type { ReactNode } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { PublicOnlyRoute } from "./auth/PublicOnlyRoute";
import { AdminProtectedRoute } from "./auth/AdminSession";
import { HqProtectedRoute } from "./auth/HqSession";
import { ToastProvider } from "./components/Toast";
import { AppShell } from "./layout/AppShell";
import { AdminShell } from "./layout/AdminShell";
import { HqShell } from "./layout/HqShell";
import { LoginPage } from "./pages/LoginPage";
import { SignupPage } from "./pages/SignupPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { StoresPage } from "./pages/StoresPage";
import { ReviewsPage } from "./pages/ReviewsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { PersonaPage } from "./pages/PersonaPage";
import { BillingPage } from "./pages/BillingPage";
import { PlatformAccountsPage } from "./pages/PlatformAccountsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { AdminLoginPage } from "./pages/admin/AdminLoginPage";
import { AdminFranchisesPage } from "./pages/admin/AdminFranchisesPage";
import { AdminFranchiseDetailPage } from "./pages/admin/AdminFranchiseDetailPage";
import { AdminAffiliationsPage } from "./pages/admin/AdminAffiliationsPage";
import { AdminSubscriptions } from "./pages/AdminSubscriptions";
import { AdminFailures } from "./pages/AdminFailures";
import { HqLoginPage } from "./pages/hq/HqLoginPage";
import { HqBrandsPage } from "./pages/hq/HqBrandsPage";
import { HqOverviewPage } from "./pages/hq/HqOverviewPage";
import { HqStoresPage } from "./pages/hq/HqStoresPage";
import { HqAnalyticsPage } from "./pages/hq/HqAnalyticsPage";
import { HqComparePage } from "./pages/hq/HqComparePage";
import { HqReviewsPage } from "./pages/hq/HqReviewsPage";
import { HqReportPage } from "./pages/hq/HqReportPage";
import { LegalDocumentPage } from "./pages/LegalDocumentPage";
import { IntroPage } from "./pages/IntroPage";

// 라우트 표 (문서 14 §2, 문서 26). 동의 전문은 가입 전에 확인할 수 있도록 공개한다.
// ★ 시스템 관리자(/admin/**)·가맹본부(/hq/**)는 사장님 세션(USER)과 완전히 분리된 이메일 OTP
//   세션이다(docs/26a auth). 각자 전용 로그인·셸·가드를 쓴다 — 사장님 ProtectedRoute 를 타지 않는다.
function App() {
  return (
    <AppProviders>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AppProviders>
  );
}

// ★ entry-server.tsx 가 `/` 를 빌드 시 정적 HTML 로 미리 렌더링한다(intro.html).
//   하이드레이션이 어긋나지 않도록 서버도 이 두 컴포넌트를 그대로 쓴다 — 라우터만 StaticRouter 로 바뀐다.
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <AuthProvider>
      <ToastProvider>{children}</ToastProvider>
    </AuthProvider>
  );
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<IntroPage />} />
      <Route path="/open30" element={<IntroPage />} />
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
      </Route>
      <Route path="/legal/:slug" element={<LegalDocumentPage />} />

      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route path="/onboarding" element={<OnboardingPage />} />
          <Route path="/stores" element={<StoresPage />} />
          <Route path="/platform-accounts" element={<PlatformAccountsPage />} />
          <Route path="/stores/:storeId/reviews" element={<ReviewsPage />} />
          <Route path="/stores/:storeId/dashboard" element={<DashboardPage />} />
          <Route path="/stores/:storeId/persona" element={<PersonaPage />} />
          <Route path="/stores/:storeId/billing" element={<BillingPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
      </Route>

      {/* 시스템 콘솔 — 이메일 OTP 전용, 사장님 세션으로 접근 불가(문서 26a auth.jwtClaim). */}
      <Route path="/admin/login" element={<AdminLoginPage />} />
      <Route element={<AdminProtectedRoute />}>
        <Route element={<AdminShell />}>
          <Route path="/admin" element={<Navigate to="/admin/franchises" replace />} />
          <Route path="/admin/franchises" element={<AdminFranchisesPage />} />
          <Route path="/admin/franchises/:brand" element={<AdminFranchiseDetailPage />} />
          <Route path="/admin/affiliations" element={<AdminAffiliationsPage />} />
          <Route path="/admin/subscriptions" element={<AdminSubscriptions />} />
          <Route path="/admin/failures" element={<AdminFailures />} />
        </Route>
      </Route>

      {/* 가맹본부 — 조회 전용, 이메일 OTP 전용(문서 14 §11, 문서 26). 쓰기 라우트를 추가하지 말 것. */}
      <Route path="/hq/login" element={<HqLoginPage />} />
      <Route element={<HqProtectedRoute />}>
        <Route element={<HqShell />}>
          <Route path="/hq" element={<Navigate to="/hq/brands" replace />} />
          <Route path="/hq/brands" element={<HqBrandsPage />} />
          <Route path="/hq/brands/:brand" element={<HqOverviewPage />} />
          <Route path="/hq/brands/:brand/stores" element={<HqStoresPage />} />
          <Route path="/hq/brands/:brand/analytics" element={<HqAnalyticsPage />} />
          <Route path="/hq/brands/:brand/compare" element={<HqComparePage />} />
          <Route path="/hq/brands/:brand/reviews" element={<HqReviewsPage />} />
          <Route path="/hq/brands/:brand/report" element={<HqReportPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/stores" replace />} />
    </Routes>
  );
}

export default App;
