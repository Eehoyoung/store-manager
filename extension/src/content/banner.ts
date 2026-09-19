/**
 * 세션 만료 / 점검 중 배너. shadow DOM 으로 격리해 네이버 DOM 개입을 최소화한다.
 */
const BANNER_HOST_ID = "review-pilot-banner-host";

export function showBanner(message: string): void {
  let host = document.getElementById(BANNER_HOST_ID);
  if (!host) {
    host = document.createElement("div");
    host.id = BANNER_HOST_ID;
    document.body.appendChild(host);
  }

  const root = host.shadowRoot ?? host.attachShadow({ mode: "open" });
  root.innerHTML = `
    <style>
      div {
        position: fixed; top: 12px; right: 12px; z-index: 2147483647;
        background: #fff3cd; color: #664d03; border: 1px solid #ffe69c;
        border-radius: 8px; padding: 12px 16px; font-size: 14px;
        box-shadow: 0 2px 8px rgba(0,0,0,0.15); max-width: 320px;
      }
    </style>
    <div>${message}</div>
  `;
}

export function hideBanner(): void {
  document.getElementById(BANNER_HOST_ID)?.remove();
}
