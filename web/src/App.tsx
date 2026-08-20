import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { PublicOnlyRoute } from "./auth/PublicOnlyRoute";
import { ToastProvider } from "./components/Toast";
import { AppShell } from "./layout/AppShell";
import { ComingSoon } from "./layout/ComingSoon";
import { LoginPage } from "./pages/LoginPage";
import { SignupPage } from "./pages/SignupPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { StoresPage } from "./pages/StoresPage";
import { QueuePage } from "./pages/QueuePage";
import { ReviewsPage } from "./pages/ReviewsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { PersonaPage } from "./pages/PersonaPage";
import { HqBrandsPage } from "./pages/hq/HqBrandsPage";
import { HqStoresPage } from "./pages/hq/HqStoresPage";
import { HqReviewsPage } from "./pages/hq/HqReviewsPage";
import { HqAnalyticsPage } from "./pages/hq/HqAnalyticsPage";

// 라우트 표 (F5). 결제·구독, 전자계약 화면은 Sprint 6+ 라 ComingSoon 으로 남겨둔다.
function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<PublicOnlyRoute />}>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/signup" element={<SignupPage />} />
            </Route>

            <Route element={<ProtectedRoute />}>
              <Route element={<AppShell />}>
                <Route path="/onboarding" element={<OnboardingPage />} />
                <Route path="/stores" element={<StoresPage />} />
                <Route path="/stores/:storeId/queue" element={<QueuePage />} />
                <Route path="/stores/:storeId/reviews" element={<ReviewsPage />} />
                <Route path="/stores/:storeId/dashboard" element={<DashboardPage />} />
                <Route path="/stores/:storeId/persona" element={<PersonaPage />} />
                <Route path="/settings" element={<ComingSoon title="설정" />} />

                {/* 가맹본부 — 조회 전용(문서 14 §11). 쓰기 라우트를 추가하지 말 것. */}
                <Route path="/hq/brands" element={<HqBrandsPage />} />
                <Route path="/hq/brands/:brand/stores" element={<HqStoresPage />} />
                <Route path="/hq/brands/:brand/reviews" element={<HqReviewsPage />} />
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
