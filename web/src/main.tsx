import { StrictMode } from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import App from "./App";
import "./index.css";

const root = document.getElementById("root")!;
const app = (
  <StrictMode>
    <App />
  </StrictMode>
);

// ★ `/`·`/open30` 은 빌드 시 미리 렌더링된 intro.html 로 응답한다(scripts/prerender.mjs).
//   그 경우에만 하이드레이션하고, 나머지 경로(빈 index.html)는 평소처럼 새로 그린다.
if (root.hasChildNodes()) hydrateRoot(root, app);
else createRoot(root).render(app);
