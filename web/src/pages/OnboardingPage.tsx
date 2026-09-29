import { useEffect, useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { Card } from "../components/Card";
import { Badge } from "../components/Badge";
import { useShellStore } from "../layout/AppShell";
import { billingApi, billingErrorMessage, type BillingResponse } from "../api/billing";
import { BillingCheckout } from "../components/BillingCheckout";

interface Step {
  title: string;
  description: string;
  state: "done" | "available" | "soon";
  action?: { label: string; to: string };
  content?: ReactNode;
}

function isServiceable(billing: BillingResponse): boolean {
  return billing.serviceState === "TRIAL" || billing.serviceState === "ACTIVE" || billing.serviceState === "GRACE";
}

/** 결제(3단계) 안내 문구 — 카드 등록 전에는 그 사실을 4단계에도 알려 다음 단계로 헤매지 않게 한다. */
function billingStep(storeId: string, billing: BillingResponse | null, billingError: string | null, onBillingChange: (next: BillingResponse) => void): Step {
  if (billingError) {
    return { title: "3. 구독 결제", description: billingError, state: "available" };
  }
  if (!billing) {
    return { title: "3. 구독 결제", description: "결제 정보를 불러오는 중입니다.", state: "available" };
  }
  if (!billing.enabled) {
    return {
      title: "3. 구독 결제",
      description: "지금은 온라인 결제를 받지 않아요. 정식 출시 때 결제대행사(KG이니시스) 결제창이 열립니다.",
      state: "soon",
    };
  }
  if (isServiceable(billing)) {
    const description =
      billing.serviceState === "TRIAL"
        ? "무료체험이 시작되었습니다. 체험이 끝나는 날 등록한 카드로 자동 결제됩니다."
        : billing.serviceState === "GRACE"
          ? "결제 확인 대기 중입니다. 곧 다시 확인해 주세요."
          : "결제가 완료되어 서비스를 이용 중입니다.";
    return { title: "3. 구독 결제", description, state: "done" };
  }
  return {
    title: "3. 구독 결제",
    description: "서비스를 이용하려면 결제수단(카드)을 등록해야 합니다. 카드 등록 전까지는 리뷰 수집이 시작되지 않습니다.",
    state: "available",
    content: <BillingCheckout storeId={storeId} billing={billing} onSuccess={onBillingChange} />,
  };
}

// 가입·서비스 이용 동의 → 결제(KG이니시스·포트원) → 계정 등록 → 백필 → 자동 운영.
// DataAPI 검증/백필은 외부 규격 대기 상태다.
function steps(billingStepEntry: Step, serviceable: boolean): Step[] {
  return [
    { title: "1. 가입", description: "계정을 만들고 로그인했습니다.", state: "done" },
    { title: "2. 서비스 이용 동의", description: "가입할 때 이용약관과 개인정보 수집·이용을 확인했습니다.", state: "done" },
    billingStepEntry,
    {
      title: "4. 배달앱 계정 등록",
      description:
        "배민·요기요·쿠팡이츠 아이디와 비밀번호를 봉투암호화로 저장하고 매장을 매핑합니다. DataAPI 검증은 보류 중입니다." +
        (serviceable ? "" : " 결제 후 리뷰 수집이 시작돼요."),
      state: "available",
      action: { label: "배달앱 계정 등록", to: "/platform-accounts" },
    },
    { title: "5. 리뷰 백필", description: "최근 90일 리뷰를 가져옵니다. 준비 중입니다.", state: "soon" },
    { title: "6. 자동 운영", description: "안전 검사를 통과한 답글은 자동으로 게시됩니다.", state: "done" },
  ];
}

export function OnboardingPage() {
  const { storeId } = useShellStore();
  const location = useLocation();
  const signupState = location.state as { affiliationRequested?: boolean; franchiseCodeEntered?: boolean; promotionApplied?: boolean } | null;

  const [billing, setBilling] = useState<BillingResponse | null>(null);
  const [billingError, setBillingError] = useState<string | null>(null);

  useEffect(() => {
    if (!storeId) return;
    setBilling(null);
    setBillingError(null);
    billingApi
      .get(storeId)
      .then(setBilling)
      .catch((e) => setBillingError(billingErrorMessage(e)));
  }, [storeId]);

  const billingStepEntry = billingStep(storeId ?? "", billing, billingError, setBilling);
  const serviceable = billing != null && isServiceable(billing);

  return (
    <div className="onboarding-page">
      <h1>시작하기</h1>
      <p>아래 순서대로 진행하면 리뷰 답글 자동화를 시작할 수 있습니다.</p>
      {signupState?.affiliationRequested ? (
        <p role="status">
          <strong>가맹본부 소속 신청이 접수되었습니다.</strong> 저희가 확인한 뒤 승인되면 본부에서 매장 리뷰를 볼 수 있게 됩니다.{" "}
          <strong>승인 전까지는 본부에 아무 정보도 제공되지 않습니다.</strong>
        </p>
      ) : null}
      {signupState?.franchiseCodeEntered && !signupState.affiliationRequested ? (
        <p role="status">제3자 제공에 동의하지 않아 가맹코드가 적용되지 않았습니다. 서비스는 그대로 이용할 수 있습니다.</p>
      ) : null}
      <ol className="onboarding-page__steps">
        {steps(billingStepEntry, serviceable).map((step) => (
          <li key={step.title}>
            <Card className="onboarding-step">
              <div className="onboarding-step__head">
                <p className="onboarding-step__title">{step.title}</p>
                {step.state === "done" ? (
                  <Badge tone="success" icon="✓">
                    완료
                  </Badge>
                ) : null}
                {step.state === "soon" ? (
                  <Badge tone="neutral" icon="•">
                    준비 중
                  </Badge>
                ) : null}
                {step.state === "available" ? (
                  <Badge tone="info" icon="▷">
                    진행 가능
                  </Badge>
                ) : null}
              </div>
              <p className="onboarding-step__desc">{step.description}</p>
              {step.action ? (
                <Link to={step.action.to} className="btn btn--secondary">
                  {step.action.label}
                </Link>
              ) : null}
              {step.content}
            </Card>
          </li>
        ))}
      </ol>
    </div>
  );
}
