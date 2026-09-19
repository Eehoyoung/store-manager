import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { PublicOnlyRoute } from "./auth/PublicOnlyRoute";
import { ToastProvider } from "./components/Toast";
import { AppShell } from "./layout/AppShell";
import { LoginPage } from "./pages/LoginPage";
import { SignupPage } from "./pages/SignupPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { StoresPage } from "./pages/StoresPage";
import { ReviewsPage } from "./pages/ReviewsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { PersonaPage } from "./pages/PersonaPage";
import { BillingPage } from "./pages/BillingPage";
import { HqBrandsPage } from "./pages/hq/HqBrandsPage";
import { HqStoresPage } from "./pages/hq/HqStoresPage";
import { HqAnalyticsPage } from "./pages/hq/HqAnalyticsPage";
import { PlatformAccountsPage } from "./pages/PlatformAccountsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { AdminPage } from "./pages/AdminPage";
import { AdminSubscriptions } from "./pages/AdminSubscriptions";
import { AdminFailures } from "./pages/AdminFailures";
import { LegalDocumentPage } from "./pages/LegalDocumentPage";
import { IntroPage } from "./pages/IntroPage";

// 라우트 표 (문서 14 §2). 동의 전문은 가입 전에 확인할 수 있도록 공개한다.
// /admin·/hq 는 권한이 있을 때만 메뉴에 노출한다(AppShell) — 라우트 자체는 등록해 두고
// 서버가 403/404 로 막는다. 링크를 보여주고 클릭 후 거절하는 흐름을 만들지 말 것.
function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
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
                <Route path="/admin" element={<AdminPage />} />
                <Route path="/admin/subscriptions" element={<AdminSubscriptions />} />
                <Route path="/admin/failures" element={<AdminFailures />} />

                {/* 가맹본부 — 조회 전용(문서 14 §11). 쓰기 라우트를 추가하지 말 것. */}
                {/* ★ WP-01(2026-08-28) — 개별 리뷰 조회(/hq/brands/:brand/reviews) 라우트 제거.
                    hq-data-sharing.md 가 본부에 개별 리뷰를 제공하지 않는다고 명시했다. */}
                <Route path="/hq/brands" element={<HqBrandsPage />} />
                <Route path="/hq/brands/:brand/stores" element={<HqStoresPage />} />
                <Route path="/hq/brands/:brand/analytics" element={<HqAnalyticsPage />} />
              </Route>
            </Route>

            <Route path="*" element={<Navigate to="/stores" replace />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </AuthProvider>
  );
}

export default App;
