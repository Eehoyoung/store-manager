import { useEffect } from "react";
import { useParams } from "react-router-dom";
import { Card } from "../components/Card";
import { useShellStore } from "../layout/AppShell";

const groblePaymentUrl = import.meta.env.VITE_GROBLE_PAYMENT_URL as string | undefined;

function checkoutUrl() {
  if (!groblePaymentUrl) return null;
  try {
    const url = new URL(groblePaymentUrl);
    const grobleHost = url.hostname === "groble.im" || url.hostname.endsWith(".groble.im");
    return url.protocol === "https:" && grobleHost ? url.toString() : null;
  } catch {
    return null;
  }
}

export function BillingPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();

  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  const paymentUrl = checkoutUrl();

  return (
    <div className="billing-page">
      <h1>구독 결제</h1>
      <Card className="billing-page__card">
        <p className="billing-page__eyebrow">외부 결제 · 우리 서비스는 결제정보를 저장하지 않음</p>
        <h2>매장 매니저 월 구독</h2>
        <p className="billing-page__price">월 33,000원 <small>(VAT 포함)</small></p>
        <ul className="billing-page__methods" aria-label="지원 결제수단">
          <li>앱카드·카드 할부</li>
          <li>카카오페이</li>
          <li>네이버페이</li>
        </ul>
        {paymentUrl ? (
          <a className="btn btn--primary" href={paymentUrl} target="_blank" rel="noreferrer">
            Groble에서 결제하기
          </a>
        ) : (
          <p className="field__error" role="alert">결제 링크를 준비 중입니다.</p>
        )}
      </Card>
      <Card className="billing-page__status" role="status">
        <h2>결제 상태 안내</h2>
        <ol>
          <li>버튼을 누르면 Groble 결제창이 새 탭에서 열립니다.</li>
          <li>결제 완료 후에도 이 화면이 즉시 구독 완료로 바뀌지는 않습니다.</li>
          <li>서명 검증된 공식 결제 결과 연동 전까지 구독 활성화는 운영 확인 상태로 유지됩니다.</li>
        </ol>
      </Card>
      <p className="field__hint">결제정보는 Groble에서 처리되며 매장 매니저는 카드번호나 CVC를 저장하지 않습니다.</p>
    </div>
  );
}
