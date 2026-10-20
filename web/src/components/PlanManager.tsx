import { useState } from "react";
import { Card } from "./Card";
import { Button } from "./Button";
import { Modal } from "./Modal";
import { PlanPicker } from "./PlanPicker";
import { billingApi, billingErrorMessage, type BillingResponse, type BillingServiceState, type TieredPlan } from "../api/billing";
import { ApiError } from "../api/client";
import { EMPTY_SELECTION, fmtDate, kstMonths, optionPrice, PLATFORM_LABEL, planLabel, selectedPlatforms, type PlanSelection } from "../lib/tieredPlan";

interface PlanManagerProps {
  storeId: string;
  tiered: TieredPlan;
  serviceState?: BillingServiceState;
  nextBillingAt?: string | null;
  trialEndsAt?: string | null;
  onChanged: (next: BillingResponse) => void;
}

function krw(n: number): string {
  return `${n.toLocaleString("ko-KR")}원`;
}

/**
 * 결제관리의 '이용 상품' 카드 — 현재 상품과 다음 결제부터 적용될 상품을 따로 보여주고,
 * 셀프 변경(월 1회)과 변경 예약 취소를 한다. 금액은 서버 응답 값만 쓴다(클라이언트 금액 불신).
 */
export function PlanManager({ storeId, tiered, serviceState, nextBillingAt, trialEndsAt, onChanged }: PlanManagerProps) {
  const [editing, setEditing] = useState(false);
  const [selection, setSelection] = useState<PlanSelection>(EMPTY_SELECTION);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const { current, next, changedThisMonth, canChange } = tiered;
  const picked = selectedPlatforms(selection);
  // 체험 중에는 바로 적용되고 횟수 제한도 없다. 유료 이용 중은 다음 결제일부터, 한 달에 한 번.
  const trial = serviceState === "TRIAL";
  const limited = changedThisMonth && !trial;
  const pickedTotal = picked ? optionPrice(tiered.optionTotalsKrw, picked.length as 1 | 2 | 3) : null;
  const removed = picked ? current.deliveryPlatforms.filter((p) => !(picked as string[]).includes(p)) : [];
  const months = kstMonths();

  async function send(platforms: string[], done: string) {
    setBusy(true);
    setMessage("");
    try {
      onChanged(await billingApi.changePlan(storeId, platforms));
      setMessage(done);
      setEditing(false);
      setSelection(EMPTY_SELECTION);
    } catch (e) {
      // VALIDATION_FAILED 문구는 여기서만 - 공용 billingErrorMessage 에 넣으면 꺼짐 화면 문구가 바뀐다.
      if (e instanceof ApiError && e.code === "VALIDATION_FAILED") {
        const reason = e.details?.reason;
        setMessage(typeof reason === "string" && reason ? reason : "선택한 상품을 다시 확인해 주세요.");
      } else {
        setMessage(billingErrorMessage(e));
      }
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  }

  return (
    <Card className="plan-summary">
      <h2>이용 상품</h2>
      <p>
        <strong>현재 상품</strong> · {planLabel(current.deliveryPlatforms)} · 월 {krw(current.totalKrw)} (VAT 포함)
      </p>
      {next ? (
        <p className="plan-summary__next">
          <strong>다음 결제부터</strong> · {planLabel(next.deliveryPlatforms)} · 월 {krw(next.totalKrw)} (VAT 포함)
        </p>
      ) : null}
      <p className="field__hint">네이버 스마트플레이스는 모든 상품에 포함돼요.</p>

      {editing ? (
        <>
          <PlanPicker value={selection} onChange={setSelection} disabled={busy} name={`plan-change-${storeId}`} prices={tiered.optionTotalsKrw} />
          <div className="billing-page__actions">
            <Button type="button" variant="secondary" disabled={busy} onClick={() => setEditing(false)}>
              취소
            </Button>
            <Button type="button" disabled={busy || !picked} onClick={() => setConfirm(true)}>
              {trial ? "이 상품으로 바꾸기" : "다음 결제부터 이 상품으로 바꾸기"}
            </Button>
          </div>
        </>
      ) : (
        <div className="billing-page__actions">
          <Button type="button" variant="secondary" disabled={busy || limited || !canChange} onClick={() => setEditing(true)}>
            상품 변경
          </Button>
          {next ? (
            <Button type="button" variant="secondary" disabled={busy || !canChange} loading={busy} onClick={() => void send(current.deliveryPlatforms, "상품 변경 예약을 취소했어요.")}>
              변경 예약 취소
            </Button>
          ) : null}
        </div>
      )}
      <p className="field__hint">
        {!canChange
          ? "지금은 상품을 바꿀 수 없어요. 자동결제와 결제 상태를 먼저 확인해 주세요."
          : trial
          ? "체험 중에는 상품을 바꾸면 바로 적용되고 횟수 제한도 없어요."
          : changedThisMonth
          ? `이번 달(${months.month}월)에는 이미 상품을 바꿨어요. 상품은 한 달에 한 번 바꿀 수 있어서 ${months.nextMonth}월 1일부터 다시 바꿀 수 있어요.`
          : "상품은 한 달에 한 번 바꿀 수 있고, 다음 결제부터 적용돼요."}
      </p>
      {message ? (
        <p role="status" className="field__hint">
          {message}
        </p>
      ) : null}

      <Modal
        open={confirm}
        title="상품을 바꿀까요?"
        onClose={() => setConfirm(false)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirm(false)} disabled={busy}>
              취소
            </Button>
            <Button type="button" onClick={() => picked && void send(picked, trial ? "상품을 바꿨어요." : "다음 결제부터 적용되도록 예약했어요.")} disabled={busy || !picked} loading={busy}>
              바꾸기
            </Button>
          </>
        }
      >
        {trial ? (
          <p>
            체험 중에는 바로 적용되고 횟수 제한도 없어요.
            {trialEndsAt ? ` 체험이 끝나는 ${fmtDate(trialEndsAt)}에` : " 체험이 끝나면"} 바꾼 상품 기준으로
            {pickedTotal !== null ? ` 월 ${krw(pickedTotal)}(VAT 포함)이` : " 월 이용료가"} 결제돼요.
          </p>
        ) : (
          <>
            <p>
              바꾼 상품은 {nextBillingAt ? `${fmtDate(nextBillingAt)} 결제일부터` : "다음 결제일부터"} 적용돼요. 그날까지는 지금 상품({planLabel(current.deliveryPlatforms)})이 그대로 유지돼요.
              {pickedTotal !== null ? ` 다음 결제 금액은 월 ${krw(pickedTotal)}(VAT 포함)이에요.` : ""} 상품을 바꾼다고 이미 결제한 기간의 요금을 일할로 돌려드리거나 더 받지는 않아요. 상품은 한 달에 한 번 바꿀 수 있어요.
            </p>
            {removed.length > 0 ? (
              <p>
                빠지는 배달앱({removed.map((p) => PLATFORM_LABEL[p] ?? p).join(", ")})의 연동 정보는 {nextBillingAt ? fmtDate(nextBillingAt) : "적용일"}에 삭제되고, 그 앱의 새 리뷰는 더 이상 처리하지 않아요.
              </p>
            ) : null}
          </>
        )}
      </Modal>
    </Card>
  );
}
