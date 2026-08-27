import { Link, useLocation } from "react-router-dom";
import { Card } from "../components/Card";
import { Badge } from "../components/Badge";
import { useShellStore } from "../layout/AppShell";

interface Step {
  title: string;
  description: string;
  state: "done" | "available" | "soon";
  action?: { label: string; to: string };
}

// 가입·서비스 이용 동의 → Groble 결제 → 계정 등록 → 백필 → 자동 운영.
// DataAPI 검증/백필은 외부 규격 대기 상태다.
function steps(storeId: string | null): Step[] {
  return [
  { title: "1. 가입", description: "계정을 만들고 로그인했습니다.", state: "done" },
  { title: "2. 서비스 이용 동의", description: "가입할 때 이용약관과 개인정보 수집·이용을 확인했습니다.", state: "done" },
  {
    title: "3. 구독 결제",
    description: "Groble의 안전한 결제창에서 원하는 결제수단을 선택합니다.",
    state: "available",
    action: { label: "결제하러 가기", to: storeId ? `/stores/${storeId}/billing` : "/stores" },
  },
  {
    title: "4. 배달앱 계정 등록",
    description: "배민·요기요·쿠팡이츠 아이디와 비밀번호를 봉투암호화로 저장하고 매장을 매핑합니다. DataAPI 검증은 보류 중입니다.",
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
  const signupState = location.state as { affiliationRequested?: boolean; franchiseCodeEntered?: boolean } | null;
  return (
    <div className="onboarding-page">
      <h1>시작하기</h1>
      <p>아래 순서대로 진행하면 리뷰 답글 자동화를 시작할 수 있습니다.</p>
      {signupState?.affiliationRequested ? <p role="status"><strong>가맹본부 소속 신청이 접수되었습니다.</strong> 저희가 확인한 뒤 승인되면 본부에서 매장 리뷰를 볼 수 있게 됩니다. <strong>승인 전까지는 본부에 아무 정보도 제공되지 않습니다.</strong></p> : null}
      {signupState?.franchiseCodeEntered && !signupState.affiliationRequested ? <p role="status">제3자 제공에 동의하지 않아 가맹코드가 적용되지 않았습니다. 서비스는 그대로 이용할 수 있습니다.</p> : null}
      <ol className="onboarding-page__steps">
        {steps(storeId).map((step) => (
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
            </Card>
          </li>
        ))}
      </ol>
    </div>
  );
}
