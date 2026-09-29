import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Card } from "../components/Card";
import { Badge, type BadgeTone } from "../components/Badge";
import { Modal } from "../components/Modal";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { BillingCheckout } from "../components/BillingCheckout";
import { billingApi, billingErrorMessage, type BillingResponse, type BillingServiceState } from "../api/billing";
import { useShellStore } from "../layout/AppShell";

const STATE_LABEL: Record<BillingServiceState, string> = {
  UNPAID: "결제 전",
  TRIAL: "무료체험 중",
  ACTIVE: "이용 중",
  GRACE: "결제 확인 대기",
  RESTRICTED: "이용 중지",
  SUSPENDED: "이용 중지",
  CANCELED: "해지됨",
};

const STATE_TONE: Record<BillingServiceState, BadgeTone> = {
  UNPAID: "neutral",
  TRIAL: "info",
  ACTIVE: "success",
  GRACE: "warning",
  RESTRICTED: "danger",
  SUSPENDED: "danger",
  CANCELED: "neutral",
};

const PAYMENT_STATUS_LABEL: Record<string, string> = {
  PAID: "결제 완료",
  FAILED: "결제 실패",
  PENDING: "처리 중",
};

function fmtDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString("ko-KR") : "-";
}

export function BillingPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();
  const [billing, setBilling] = useState<BillingResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [showCardForm, setShowCardForm] = useState(false);
  const [confirmStop, setConfirmStop] = useState(false);
  const [retryTick, setRetryTick] = useState(0);
  const [confirmResume, setConfirmResume] = useState(false);
  const [resumeBusy, setResumeBusy] = useState(false);
  const [resumeMessage, setResumeMessage] = useState("");

  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  useEffect(() => {
    if (!storeId) return;
    setBilling(null);
    setError(null);
    billingApi
      .get(storeId)
      .then(setBilling)
      .catch((e) => setError(billingErrorMessage(e)));
  }, [storeId, retryTick]);

  async function setAutoRenew(on: boolean) {
    setBusy(true);
    setMessage("");
    try {
      const next = await billingApi.setAutoRenew(storeId, on);
      setBilling(next);
      setMessage(on ? "자동결제를 다시 켰습니다." : "자동결제를 중단했습니다. 현재 이용 기간까지는 계속 사용할 수 있습니다.");
    } catch (e) {
      setMessage(billingErrorMessage(e));
    } finally {
      setBusy(false);
      setConfirmStop(false);
    }
  }

  async function resumeAll() {
    setResumeBusy(true);
    setResumeMessage("");
    try {
      const { resumed, view } = await billingApi.resumeHeldReplies(storeId);
      setBilling(view);
      setResumeMessage(resumed > 0 ? `${resumed}건을 게시 예약했습니다.` : "게시할 보류 답글이 없습니다.");
    } catch (e) {
      setResumeMessage(billingErrorMessage(e));
    } finally {
      setResumeBusy(false);
      setConfirmResume(false);
    }
  }

  if (!storeId) return <EmptyState title="매장을 먼저 선택해 주세요" />;

  if (error) {
    return (
      <EmptyState
        title="결제 정보를 불러오지 못했습니다"
        description={error}
        action={
          <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
            다시 시도
          </Button>
        }
      />
    );
  }

  if (!billing) {
    return (
      <div className="billing-page">
        <Skeleton height={120} />
        <Skeleton height={220} />
      </div>
    );
  }

  if (!billing.enabled) {
    return (
      <div className="billing-page">
        <h1>구독 결제</h1>
        <EmptyState title="지금은 온라인 결제를 받지 않아요" description="소담랩스에 문의해 주세요." />
      </div>
    );
  }

  // 카드가 없거나, 지금 당장 결제해야 이용이 재개되는 상태면 등록 화면을 바로 보여준다.
  const mustRegisterNow =
    !billing.hasCard || billing.serviceState === "UNPAID" || billing.serviceState === "RESTRICTED" || billing.serviceState === "SUSPENDED";
  const displayCardForm = showCardForm || mustRegisterNow;
  const canToggleAutoRenew = billing.hasCard && billing.serviceState !== "RESTRICTED" && billing.serviceState !== "SUSPENDED";
  const isServiceable = billing.serviceState === "TRIAL" || billing.serviceState === "ACTIVE" || billing.serviceState === "GRACE";

  return (
    <div className="billing-page">
      <h1>구독 결제</h1>

      <Card className="billing-page__card">
        <Badge tone={STATE_TONE[billing.serviceState]}>{STATE_LABEL[billing.serviceState]}</Badge>
        <p className="billing-page__price">
          월 {billing.amountKrw.toLocaleString("ko-KR")}원 <small>(VAT 포함)</small>
        </p>
        <table>
          <tbody>
            {billing.lastPaidAt ? (
              <tr>
                <th>최근 결제일</th>
                <td>{fmtDate(billing.lastPaidAt)}</td>
              </tr>
            ) : null}
            {billing.trialPending || billing.trialEndsAt ? (
              <tr>
                <th>무료체험 종료</th>
                <td>{fmtDate(billing.trialEndsAt)}</td>
              </tr>
            ) : null}
            {billing.nextBillingAt ? (
              <tr>
                <th>{billing.hasCard && billing.autoRenew ? "다음 결제일" : "결제예정일"}</th>
                <td>{fmtDate(billing.nextBillingAt)}</td>
              </tr>
            ) : null}
            {billing.restrictedFrom && (billing.serviceState === "GRACE" || billing.serviceState === "RESTRICTED") ? (
              <tr>
                <th>{billing.serviceState === "GRACE" ? "이용 중지 예정일" : "이용 중지일"}</th>
                <td>{fmtDate(billing.restrictedFrom)}</td>
              </tr>
            ) : null}
          </tbody>
        </table>
        {billing.serviceState === "GRACE" ? (
          <p role="alert" className="billing-page__warning">
            결제가 확인되지 않았어요. 위 중지 예정일까지 결제하지 않으면 예약 답글 게시를 포함한 서비스 이용이 중지돼요. 데이터는 지워지지 않아요.
          </p>
        ) : null}
        {billing.serviceState === "RESTRICTED" || billing.serviceState === "SUSPENDED" ? (
          <p role="alert" className="billing-page__warning">
            결제가 확인되지 않아 이용이 중지됐어요. 결제하면 바로 다시 시작돼요.
          </p>
        ) : null}
      </Card>

      {billing.heldReplyCount > 0 ? (
        <Card>
          <h2>이용 중지 동안 게시되지 않은 답글 {billing.heldReplyCount}건이 있어요</h2>
          {isServiceable ? (
            <>
              <p>지금 게시를 예약할 수 있어요. 예전 리뷰라 답글 내용을 먼저 확인하시는 걸 권해요.</p>
              <div className="billing-page__actions">
                <Link to={`/stores/${storeId}/reviews`} className="btn btn--secondary">
                  리뷰 화면에서 하나씩 확인
                </Link>
                <Button type="button" variant="primary" onClick={() => setConfirmResume(true)}>
                  모두 게시하기
                </Button>
              </div>
            </>
          ) : (
            <p>결제 후 게시할 수 있어요.</p>
          )}
          {resumeMessage ? (
            <p role="status" className="field__hint">
              {resumeMessage}
            </p>
          ) : null}
        </Card>
      ) : null}

      {displayCardForm ? (
        <BillingCheckout
          storeId={storeId}
          billing={billing}
          onSuccess={(next) => {
            setBilling(next);
            setShowCardForm(false);
          }}
        />
      ) : (
        <Card>
          <h2>등록 카드</h2>
          <p>카드가 등록되어 있습니다. 결제일마다 자동으로 결제됩니다.</p>
          <div className="billing-page__actions">
            <Button type="button" variant="secondary" disabled={busy} onClick={() => setShowCardForm(true)}>
              카드 변경
            </Button>
            {canToggleAutoRenew ? (
              <Button
                type="button"
                variant="secondary"
                disabled={busy}
                onClick={() => (billing.autoRenew ? setConfirmStop(true) : setAutoRenew(true))}
              >
                {billing.autoRenew ? "자동결제 중단" : "자동결제 다시 켜기"}
              </Button>
            ) : null}
          </div>
        </Card>
      )}

      {message ? (
        <p role="status" className="field__hint">
          {message}
        </p>
      ) : null}

      {billing.payments.length > 0 ? (
        <Card>
          <h2>결제 내역</h2>
          <table className="dashboard-table">
            <thead>
              <tr>
                <th>결제일</th>
                <th>금액</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {billing.payments.map((p) => (
                <tr key={p.createdAt}>
                  <td data-label="결제일">{fmtDate(p.paidAt ?? p.createdAt)}</td>
                  <td data-label="금액">{p.amountKrw.toLocaleString("ko-KR")}원</td>
                  <td data-label="상태">
                    {PAYMENT_STATUS_LABEL[p.status] ?? p.status}
                    {p.failureReason ? ` · ${p.failureReason}` : ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      ) : null}

      <Modal
        open={confirmStop}
        title="자동결제를 중단할까요?"
        onClose={() => setConfirmStop(false)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirmStop(false)} disabled={busy}>
              취소
            </Button>
            <Button type="button" variant="danger" onClick={() => setAutoRenew(false)} disabled={busy} loading={busy}>
              중단하기
            </Button>
          </>
        }
      >
        <p>다음 결제부터 자동결제가 중단됩니다. 현재 결제한 이용 기간은 그대로 유지됩니다.</p>
      </Modal>

      <Modal
        open={confirmResume}
        title="보류된 답글을 모두 게시할까요?"
        onClose={() => setConfirmResume(false)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirmResume(false)} disabled={resumeBusy}>
              취소
            </Button>
            <Button type="button" variant="primary" onClick={() => void resumeAll()} disabled={resumeBusy} loading={resumeBusy}>
              모두 게시하기
            </Button>
          </>
        }
      >
        <p>예전 리뷰에 지금 답글이 달립니다. 내용을 확인하셨나요?</p>
      </Modal>
    </div>
  );
}
