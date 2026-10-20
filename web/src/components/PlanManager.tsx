import { useState } from "react";
import { Card } from "./Card";
import { Button } from "./Button";
import { Modal } from "./Modal";
import { PlanPicker } from "./PlanPicker";
import { billingApi, billingErrorMessage, type BillingResponse, type TieredPlan } from "../api/billing";
import { ApiError } from "../api/client";
import { EMPTY_SELECTION, planLabel, selectedPlatforms, type PlanSelection } from "../lib/tieredPlan";

interface PlanManagerProps {
  storeId: string;
  tiered: TieredPlan;
  onChanged: (next: BillingResponse) => void;
}

function krw(n: number): string {
  return `${n.toLocaleString("ko-KR")}원`;
}

/**
 * 결제관리의 '이용 상품' 카드 — 현재 상품과 다음 결제부터 적용될 상품을 따로 보여주고,
 * 셀프 변경(월 1회)과 변경 예약 취소를 한다. 금액은 서버 응답 값만 쓴다(클라이언트 금액 불신).
 */
export function PlanManager({ storeId, tiered, onChanged }: PlanManagerProps) {
  const [editing, setEditing] = useState(false);
  const [selection, setSelection] = useState<PlanSelection>(EMPTY_SELECTION);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const { current, next, changedThisMonth } = tiered;
  const picked = selectedPlatforms(selection);

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
      setMessage(e instanceof ApiError && e.code === "VALIDATION_FAILED" ? "선택한 상품을 다시 확인해 주세요." : billingErrorMessage(e));
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
          <PlanPicker value={selection} onChange={setSelection} disabled={busy} name={`plan-change-${storeId}`} />
          <div className="billing-page__actions">
            <Button type="button" variant="secondary" disabled={busy} onClick={() => setEditing(false)}>
              취소
            </Button>
            <Button type="button" disabled={busy || !picked} onClick={() => setConfirm(true)}>
              다음 결제부터 이 상품으로 바꾸기
            </Button>
          </div>
        </>
      ) : (
        <div className="billing-page__actions">
          <Button type="button" variant="secondary" disabled={busy || changedThisMonth} onClick={() => setEditing(true)}>
            상품 변경
          </Button>
          {next ? (
            <Button type="button" variant="secondary" disabled={busy} loading={busy} onClick={() => void send(current.deliveryPlatforms, "상품 변경 예약을 취소했어요.")}>
              변경 예약 취소
            </Button>
          ) : null}
        </div>
      )}
      <p className="field__hint">
        {changedThisMonth
          ? "이번 달에는 이미 상품을 바꿨어요. 상품은 한 달에 한 번만 바꿀 수 있어서 다음 달에 다시 바꿀 수 있어요."
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
            <Button type="button" onClick={() => picked && void send(picked, "다음 결제부터 적용되도록 예약했어요.")} disabled={busy || !picked} loading={busy}>
              바꾸기
            </Button>
          </>
        }
      >
        <p>변경한 상품은 다음 결제일부터 적용됩니다. 이미 결제한 이용 기간의 요금은 환불되지 않으며, 상품은 한 달에 한 번만 바꿀 수 있습니다.</p>
      </Modal>
    </Card>
  );
}
