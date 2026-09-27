import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Card } from "../components/Card";
import { useShellStore } from "../layout/AppShell";
import { apiRequest } from "../api/client";
import { accountApi } from "../api/account";

type Checkout = { paymentId: string; portoneStoreId: string; channelKey: string; amount: number };
type BillingStatus = { autoRenew: boolean; nextBillingAt: string };

export function BillingPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => { if (storeId) setStoreId(storeId); }, [storeId, setStoreId]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const billingKey = params.get("billingKey");
    if (!billingKey || !storeId) return;
    window.history.replaceState(null, "", window.location.pathname);
    if (params.has("code")) {
      setMessage(params.get("message") || "결제가 완료되지 않았습니다.");
      return;
    }
    if (billingKey) {
      setBusy(true);
      apiRequest<BillingStatus>(`/stores/${storeId}/portone/billing-key`, {
        method: "POST", body: { billingKey },
      }).then(() => setMessage("결제수단이 등록되고 첫 달 결제가 완료되었습니다. 다음 달부터 자동결제됩니다."))
        .catch(() => setMessage("결제수단 확인이 지연되고 있습니다. 고객지원에 문의해 주세요."))
        .finally(() => setBusy(false));
      return;
    }
  }, [storeId]);

  async function pay() {
    if (busy) return;
    setBusy(true);
    setMessage("");
    try {
      const customer = await accountApi.get();
      if (!customer.phone) {
        setMessage("결제 전에 설정에서 휴대폰 번호를 등록해 주세요.");
        return;
      }
      const checkout = await apiRequest<Checkout>(`/stores/${storeId}/portone/checkout`, { method: "POST" });
      const PortOne = await import("@portone/browser-sdk/v2");
      const result = await PortOne.requestIssueBillingKey({
        storeId: checkout.portoneStoreId, channelKey: checkout.channelKey,
        billingKeyMethod: "CARD", issueId: checkout.paymentId, issueName: "소담리뷰 월 정기결제",
        displayAmount: checkout.amount, currency: "KRW", offerPeriod: { interval: "1m" },
        customer: { customerId: `store-${storeId}`, fullName: customer.name, phoneNumber: customer.phone, email: customer.email },
        redirectUrl: window.location.href.split("?")[0],
      });
      if (!result) return;
      if (result.code) {
        setMessage(result.message || "결제가 완료되지 않았습니다.");
        return;
      }
      await apiRequest<BillingStatus>(`/stores/${storeId}/portone/billing-key`, {
        method: "POST", body: { billingKey: result.billingKey },
      });
      setMessage("결제수단이 등록되고 첫 달 결제가 완료되었습니다. 다음 달부터 자동결제됩니다.");
    } catch (error) {
      // 포트원 창이 성공했더라도 확인 API가 실패할 수 있으므로 다음 확인 경로를 남긴다.
      setMessage(error instanceof Error ? error.message : "결제를 시작하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  async function stopAutoRenew() {
    if (busy || !window.confirm("다음 결제부터 자동결제를 중단할까요? 현재 이용 기간은 유지됩니다.")) return;
    setBusy(true);
    try {
      await apiRequest<BillingStatus>(`/stores/${storeId}/portone/auto-renew/stop`, { method: "POST" });
      setMessage("자동결제가 중단되었습니다. 현재 이용 기간까지는 계속 사용할 수 있습니다.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "자동결제를 중단하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="billing-page">
      <h1>구독 결제</h1>
      <Card className="billing-page__card">
        <p className="billing-page__eyebrow">KG이니시스 · 포트원 결제창</p>
        <h2>정기결제 카드를 등록합니다.</h2>
        <p>카드 정보는 결제대행사 화면에서 입력합니다. 카드 등록이 완료되면 첫 달 이용료 33,000원이 즉시 결제됩니다. 소담리뷰는 카드번호·유효기간·CVC를 받거나 저장하지 않습니다.</p>
        <p className="billing-page__price">월 33,000원 <small>(VAT 포함)</small></p>
        <table><tbody><tr><th>요금</th><td>매장 1곳당 30,000원 + 부가세 3,000원</td></tr><tr><th>결제 주기</th><td>첫 결제일부터 매월 자동결제</td></tr></tbody></table>
        <button className="btn btn--primary" type="button" disabled={busy || !storeId} onClick={pay}>
          {busy ? "처리 중…" : "카드 등록하고 시작하기"}
        </button>
        {message && <p role="status" className="field__hint">{message}</p>}
      </Card>
      <Card><h2>자동결제와 해지</h2><p>카드번호·유효기간·CVC는 결제창에서만 입력합니다. 소담리뷰에는 결제용 빌링키만 저장되며 매월 같은 날짜에 결제됩니다. 자동결제를 중단해도 이미 결제한 이용 기간은 유지됩니다.</p><button className="btn" type="button" disabled={busy || !storeId} onClick={stopAutoRenew}>자동결제 중단</button></Card>
    </div>
  );
}
