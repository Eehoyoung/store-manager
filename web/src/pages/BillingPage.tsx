import { useEffect } from "react";
import { useParams } from "react-router-dom";
import { Card } from "../components/Card";
import { useShellStore } from "../layout/AppShell";

const groblePaymentUrl = import.meta.env.VITE_GROBLE_PAYMENT_URL as string | undefined;

function checkoutUrl(storeId: string) {
  if (!groblePaymentUrl || !storeId) return null;
  const url = new URL(groblePaymentUrl);
  url.searchParams.set("ref", storeId);
  return url.toString();
}

export function BillingPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();

  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  const paymentUrl = checkoutUrl(storeId);

  return (
    <div className="billing-page">
      <h1>구독 결제</h1>
      <Card className="billing-page__card">
        <h2>매장 매니저 월 구독</h2>
        <p className="billing-page__price">월 33,000원 <small>(VAT 포함)</small></p>
        <p>Groble의 안전한 결제창에서 카드·간편결제 등 제공되는 결제수단을 선택할 수 있습니다.</p>
        {paymentUrl ? (
          <a className="btn btn--primary" href={paymentUrl} target="_blank" rel="noreferrer">
            Groble에서 결제하기
          </a>
        ) : (
          <p className="field__error" role="alert">결제 링크를 준비 중입니다.</p>
        )}
      </Card>
      <p className="field__hint">결제정보는 Groble에서 처리되며 매장 매니저는 카드번호나 CVC를 저장하지 않습니다.</p>
    </div>
  );
}
