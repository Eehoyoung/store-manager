import { useEffect, useState } from "react";
import { Card } from "./Card";
import { Button } from "./Button";
import { billingApi, billingErrorMessage, type BillingResponse } from "../api/billing";

interface BillingCheckoutProps {
  storeId: string;
  billing: BillingResponse;
  onSuccess: (next: BillingResponse) => void;
}

function consentKey(storeId: string) {
  return `billing-consent:${storeId}`;
}

function krw(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}

function fmtDate(d: Date | string): string {
  return (typeof d === "string" ? new Date(d) : d).toLocaleDateString("ko-KR");
}

function addDays(days: number): Date {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d;
}

/** 상태별 청구 시점 안내 — 카드를 등록하면 정확히 언제·얼마가 결제되는지 먼저 말한다. */
function stateNotice(billing: BillingResponse): string {
  if (billing.trialPending) {
    return `카드를 등록하면 오늘부터 ${billing.trialDays}일 무료체험이 시작되고, 체험이 끝나는 날 ${krw(billing.amountKrw)}이 결제돼요. 체험 중에는 요금이 청구되지 않아요.`;
  }
  if (billing.serviceState === "GRACE") {
    const lastDay = billing.restrictedFrom
      ? new Date(new Date(billing.restrictedFrom).getTime() - 1).toLocaleDateString("ko-KR")
      : "곧";
    return `결제가 확인되지 않았어요. 결제하지 않으면 ${lastDay}까지만 이용할 수 있어요.`;
  }
  return `지금 ${krw(billing.chargeNowKrw)} 결제 후 바로 시작돼요.`;
}

/**
 * 결제수단(카드) 등록 위젯 — 온보딩·결제 화면에서 공용으로 쓴다.
 * 카드정보는 이 컴포넌트를 포함해 소담리뷰 어디에도 들어오지 않는다(절대규칙 7).
 * 모바일 결제창은 팝업이 아니라 페이지를 떠났다가 redirectUrl로 돌아온다 — 돌아온 주소의
 * billingKey로 결제를 마저 한다. 새로고침으로 두 번 청구되지 않게 주소부터 지운다(서버에도 잠금이 있다).
 */
export function BillingCheckout({ storeId, billing, onSuccess }: BillingCheckoutProps) {
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  // 약관 제9조의4 제3항 — 자동화된 결제 시작에 앞서 기준·시점·금액·해지 방법을 동의 전에 명시한다.
  const trialEndDate = billing.trialPending ? addDays(billing.trialDays) : billing.trialEndsAt ? new Date(billing.trialEndsAt) : null;
  const firstBillingDate = billing.nextBillingAt ? new Date(billing.nextBillingAt) : trialEndDate;

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const billingKey = params.get("billingKey");
    const code = params.get("code");
    if (!billingKey && !code) return;
    window.history.replaceState(null, "", window.location.pathname);
    if (code) {
      setMessage(params.get("message") || "결제수단을 등록하지 못했어요.");
      return;
    }
    const raw = sessionStorage.getItem(consentKey(storeId));
    sessionStorage.removeItem(consentKey(storeId));
    const consentVersion = raw ? (JSON.parse(raw) as { version: string }).version : null;
    if (!billingKey || !consentVersion) {
      setMessage("자동결제 동의를 다시 확인해 주세요.");
      return;
    }
    setBusy(true);
    billingApi
      .checkout(storeId, { billingKey, billingConsentAgreed: true, billingConsentVersion: consentVersion })
      .then((next) => {
        onSuccess(next);
        setMessage("결제수단이 등록되었습니다.");
      })
      .catch((e) => setMessage(billingErrorMessage(e)))
      .finally(() => setBusy(false));
    // 리다이렉트 복귀는 페이지 로드당 한 번뿐이라 마운트 시점 값으로 충분하다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function register() {
    if (!agreed) {
      setMessage("자동결제 동의 항목을 확인해 주세요.");
      return;
    }
    setBusy(true);
    setMessage("");
    try {
      sessionStorage.setItem(consentKey(storeId), JSON.stringify({ version: billing.consentVersion }));
      const PortOne = await import("@portone/browser-sdk/v2");
      const result = await PortOne.requestIssueBillingKey({
        storeId: billing.portoneStoreId,
        channelKey: billing.channelKey,
        billingKeyMethod: "CARD",
        issueId: `bk${storeId}t${Date.now()}`,
        issueName: "소담리뷰 월 정기결제",
        displayAmount: billing.chargeNowKrw,
        currency: "KRW",
        // KG이니시스 모바일은 제공 기간이, PC는 이름·연락처·이메일이 없으면 결제창이 열리지 않는다.
        offerPeriod: { interval: "1m" },
        customer: {
          customerId: billing.customer.customerId,
          fullName: billing.customer.fullName,
          phoneNumber: billing.customer.phoneNumber || undefined,
          email: billing.customer.email,
        },
        redirectUrl: window.location.href.split("?")[0],
      });
      if (!result) return; // 모바일: 페이지를 떠났다가 위 useEffect로 돌아온다.
      sessionStorage.removeItem(consentKey(storeId));
      if (result.code !== undefined) {
        setMessage(result.message || "결제수단을 등록하지 못했어요.");
        return;
      }
      const next = await billingApi.checkout(storeId, {
        billingKey: result.billingKey,
        billingConsentAgreed: true,
        billingConsentVersion: billing.consentVersion,
      });
      onSuccess(next);
      setMessage("결제수단이 등록되었습니다.");
    } catch (e) {
      setMessage(billingErrorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="billing-checkout">
      <p className="billing-page__eyebrow">KG이니시스 · 포트원 결제창</p>
      <p className="billing-checkout__notice">{stateNotice(billing)}</p>
      <p className="billing-checkout__price">
        월 {krw(billing.amountKrw)} <small>(VAT 포함)</small>
      </p>
      <p className="field__hint">
        카드번호·유효기간·CVC는 결제대행사 화면에서만 입력합니다. 소담리뷰는 이를 받거나 저장하지 않습니다.
      </p>
      <div className="consent-table-wrap">
        <table>
          <tbody>
            {trialEndDate ? (
              <tr>
                <th>체험 종료일</th>
                <td>{fmtDate(trialEndDate)}</td>
              </tr>
            ) : null}
            <tr>
              <th>{trialEndDate ? "첫 결제예정일" : "결제일"}</th>
              <td>{firstBillingDate ? fmtDate(firstBillingDate) : "결제 즉시"}</td>
            </tr>
            <tr>
              <th>결제금액</th>
              <td>월 {krw(billing.amountKrw)} (VAT 포함)</td>
            </tr>
            <tr>
              <th>결제방법</th>
              <td>등록한 카드로 매월 자동결제</td>
            </tr>
            <tr>
              <th>해지방법</th>
              <td>결제 화면에서 언제든 자동결제 중단 가능</td>
            </tr>
          </tbody>
        </table>
      </div>
      <label className="consent-check">
        <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
        무료체험 종료 또는 결제주기 도래 시 등록한 카드로 월 이용료가 자동결제되는 데 동의합니다. (필수)
      </label>
      <Button type="button" disabled={busy || !agreed} loading={busy} onClick={register}>
        {billing.hasCard ? "카드 다시 등록하기" : "카드 등록하고 시작하기"}
      </Button>
      {message && (
        <p role="status" className="field__hint">
          {message}
        </p>
      )}
    </Card>
  );
}
