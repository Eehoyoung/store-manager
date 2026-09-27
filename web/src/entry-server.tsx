// 빌드 시 `/`(소개 페이지)를 정적 HTML 로 렌더링한다 — scripts/prerender.mjs 가 호출한다.
// ★ 트리는 main.tsx 와 같아야 한다(BrowserRouter → StaticRouter 만 다르다). 어긋나면 하이드레이션이 깨진다.
import { StrictMode } from "react";
import { renderToString } from "react-dom/server";
import { StaticRouter } from "react-router-dom/server";
import { AppProviders, AppRoutes } from "./App";

export { INTRO_DESCRIPTION, INTRO_TITLE } from "./pages/IntroPage";

export function render(url: string): string {
  return renderToString(
    <StrictMode>
      <AppProviders>
        <StaticRouter location={url}>
          <AppRoutes />
        </StaticRouter>
      </AppProviders>
    </StrictMode>,
  );
}
