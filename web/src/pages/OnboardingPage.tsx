import { Link } from "react-router-dom";
import { Card } from "../components/Card";
import { Badge } from "../components/Badge";

interface Step {
  title: string;
  description: string;
  state: "done" | "available" | "soon";
  action?: { label: string; to: string };
}

// docs/10 Sprint 5: 가입 → 전자계약 → 결제수단 → 계정 등록 → 백필 → 첫 승인.
// 전자계약·결제수단은 백엔드가 없어 "준비 중". 계정 등록(/platform-accounts)·백필 API 도 아직 없다 —
// 동작하지 않는 폼을 흉내내지 않기 위해 마찬가지로 "준비 중"으로 두고, 실제로 되는 화면(매장 등록·승인 큐)으로만 안내한다.
const STEPS: Step[] = [
  { title: "1. 가입", description: "계정을 만들고 로그인했습니다.", state: "done" },
  { title: "2. 전자계약", description: "가맹 계약 전자서명. 준비 중입니다.", state: "soon" },
  { title: "3. 결제수단 등록", description: "월 구독료 결제수단 등록. 준비 중입니다.", state: "soon" },
  {
    title: "4. 배달앱 계정 등록",
    description: "배민·요기요·쿠팡이츠 계정 연동은 준비 중입니다. 먼저 매장을 등록해 두세요.",
    state: "available",
    action: { label: "매장 등록하러 가기", to: "/stores" },
  },
  { title: "5. 리뷰 백필", description: "최근 90일 리뷰를 가져옵니다. 준비 중입니다.", state: "soon" },
  {
    title: "6. 첫 승인",
    description: "생성된 답글을 승인 큐에서 확인하고 승인합니다.",
    state: "available",
    action: { label: "매장 목록에서 승인 큐로 이동", to: "/stores" },
  },
];

export function OnboardingPage() {
  return (
    <div className="onboarding-page">
      <h1>시작하기</h1>
      <p>아래 순서대로 진행하면 리뷰 답글 자동화를 시작할 수 있습니다.</p>
      <ol className="onboarding-page__steps">
        {STEPS.map((step) => (
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
