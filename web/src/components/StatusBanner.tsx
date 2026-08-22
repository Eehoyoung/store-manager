import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { analyticsApi } from "../api/analytics";

/**
 * 운항 상태 배너 — 이 제품의 시그니처 요소.
 *
 * ★ 왜 항상 보이는가
 * 사장님이 앱을 열 때 묻는 질문은 하나다. "지금 잘 돌고 있나, 내가 봐야 할 게 있나."
 * 그 답이 대시보드 안쪽에 묻혀 있으면 제품의 약속("안전한 건 자동으로, 위험한 건 멈춤")이
 * 화면에서 보이지 않는다. 그래서 셸에 고정한다.
 *
 * ★ 기간 필터를 걸지 않는다
 * pending/blocked/highRisk 는 CLAUDE.md 대시보드 집계 기준상 '현재 상태 지표'다.
 * 40일 전 리뷰가 아직 미처리라면 그건 지금 해야 할 일이므로 기간으로 자르지 않는다.
 *
 * ★ 색만으로 상태를 말하지 않는다
 * 램프(색) + 상태 문구 + 건수를 항상 함께 낸다. 색각 이상에서도 읽혀야 한다.
 */

type Phase = "loading" | "engaged" | "attention" | "unknown";

interface Counts {
  blocked: number;
  highRisk: number;
  pending: number;
}

export function StatusBanner({ storeId }: { storeId: string | null }) {
  const [phase, setPhase] = useState<Phase>("loading");
  const [counts, setCounts] = useState<Counts | null>(null);

  useEffect(() => {
    if (!storeId) {
      setPhase("unknown");
      return;
    }
    let alive = true;
    setPhase("loading");
    analyticsApi
      .summary(storeId)
      .then((s) => {
        if (!alive) return;
        const next = {
          blocked: s.blockedCount ?? 0,
          highRisk: s.highRiskCount ?? 0,
          pending: s.pendingCount ?? 0,
        };
        setCounts(next);
        setPhase(next.blocked + next.highRisk > 0 ? "attention" : "engaged");
      })
      // 상태를 모르는 것을 '정상'으로 보이게 하지 않는다 — 안전측으로 '확인 필요'로 둔다.
      .catch(() => {
        if (alive) setPhase("unknown");
      });
    return () => {
      alive = false;
    };
  }, [storeId]);

  const needsAttention = counts ? counts.blocked + counts.highRisk : 0;

  return (
    <div className={`annunciator annunciator--${phase}`} role="status" aria-live="polite">
      <span className="annunciator__lamp" aria-hidden="true" />
      <span className="annunciator__state">{stateLabel(phase)}</span>
      <span className="annunciator__detail">{detailText(phase, counts)}</span>
      {phase === "attention" && storeId ? (
        <Link className="annunciator__action" to={`/stores/${storeId}/reviews?riskLevel=3`}>
          확인하러 가기
          <span aria-hidden="true"> →</span>
        </Link>
      ) : null}
      {phase === "attention" ? (
        <span className="annunciator__count readout">{needsAttention}건</span>
      ) : null}
    </div>
  );
}

function stateLabel(phase: Phase): string {
  switch (phase) {
    case "engaged":
      return "자동 운항 중";
    case "attention":
      return "멈춤 — 사람 확인 필요";
    case "loading":
      return "상태 확인 중";
    default:
      return "상태 확인 불가";
  }
}

function detailText(phase: Phase, counts: Counts | null): string {
  switch (phase) {
    case "engaged":
      return counts && counts.pending > 0
        ? `안전한 답글은 자동으로 올라갑니다. 게시 예정 ${counts.pending}건`
        : "안전한 답글은 자동으로 올라갑니다.";
    case "attention":
      return "위험하거나 확인이 필요한 리뷰가 있어 자동 게시를 멈췄습니다.";
    case "loading":
      return "매장 상태를 불러오는 중입니다.";
    default:
      return "매장을 선택하면 운항 상태를 표시합니다.";
  }
}
