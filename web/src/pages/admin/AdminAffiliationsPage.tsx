import { useEffect, useState } from "react";
import { adminApi, type HqWithdrawalRequest } from "../../api/admin";
import type { AdminAffiliationRow, AffiliationStatus } from "../../api/types";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { ReasonModal } from "../../components/ReasonModal";
import { Skeleton } from "../../components/Skeleton";

const TABS: { key: AffiliationStatus; label: string }[] = [
  { key: "PENDING", label: "승인 대기" },
  { key: "APPROVED", label: "승인됨" },
  { key: "REJECTED", label: "거절됨" },
  { key: "RELEASE_REQUESTED", label: "해제 요청" },
  { key: "RELEASED", label: "해제 완료" },
];

function fmt(iso: string | null): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleString("ko-KR");
}

// 문서 26 §5.5 — 가맹점 소속 관리. 승인·거절·해제 전부 사유 입력을 필수로 한다.
export function AdminAffiliationsPage() {
  const [status, setStatus] = useState<AffiliationStatus>("PENDING");
  const [items, setItems] = useState<AdminAffiliationRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [withdrawals, setWithdrawals] = useState<HqWithdrawalRequest[]>([]);
  const [action, setAction] = useState<{ item: AdminAffiliationRow; kind: "APPROVE" | "REJECT" | "RELEASE" } | null>(null);

  const load = () =>
    adminApi
      .affiliations(status)
      .then(setItems)
      .catch((e) => setError(e instanceof ApiError ? e.message : "소속 신청 목록을 불러오지 못했습니다."));

  useEffect(() => {
    setItems(null);
    setError(null);
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  useEffect(() => {
    adminApi.hqWithdrawalRequests().then(setWithdrawals).catch(() => setWithdrawals([]));
  }, []);

  const runAction = async (reason: string) => {
    if (!action) return;
    const { item, kind } = action;
    if (kind === "RELEASE") await adminApi.release(item.id, reason);
    else await adminApi.decide(item.id, kind, reason);
    setDone(
      kind === "APPROVE"
        ? `${item.storeName} 을(를) ${item.brandName} 본부에 연결했습니다.`
        : kind === "REJECT"
        ? `${item.storeName} 의 ${item.brandName} 소속 신청을 거절했습니다.`
        : `${item.storeName} 의 ${item.brandName} 소속을 해제했습니다.`,
    );
    setAction(null);
    await load();
  };

  return (
    <div className="admin-page">
      <div className="stores-page__header">
        <div>
          <h1>가맹점 소속 관리</h1>
          <p>가맹코드를 입력한 매장의 소속 신청을 승인·거절하고, 승인된 매장의 소속을 해제합니다.</p>
        </div>
      </div>

      <div className="settings-page__tabs" role="tablist" aria-label="소속 상태">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={status === t.key}
            className={`settings-page__tab${status === t.key ? " settings-page__tab--active" : ""}`}
            onClick={() => setStatus(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
      {done ? <p className="admin-page__done" role="status">{done}</p> : null}

      {items === null && !error ? <Skeleton height={140} /> : null}
      {items?.length === 0 ? <EmptyState title="해당 상태의 신청이 없습니다" /> : null}

      <ul className="admin-request-list">
        {items?.map((item) => (
          <li key={item.id}>
            <Card className="admin-request">
              <div className="admin-request__head">
                <span className="label-etched">{TABS.find((t) => t.key === item.status)?.label ?? item.status}</span>
                <h2 className="admin-request__brand">{item.brandName}</h2>
              </div>
              <dl className="admin-request__facts">
                <div><dt>매장</dt><dd>{item.storeName}</dd></div>
                <div><dt>주소</dt><dd>{item.storeAddress ?? "입력 없음"}</dd></div>
                <div><dt>신청자</dt><dd>{item.requesterName}</dd></div>
                <div><dt>이메일</dt><dd className="admin-request__mono">{item.requesterEmail}</dd></div>
                <div><dt>신청일</dt><dd>{fmt(item.requestedAt)}</dd></div>
                {item.decidedAt ? <div><dt>처리일</dt><dd>{fmt(item.decidedAt)}</dd></div> : null}
                {item.reason ? <div><dt>사유</dt><dd>{item.reason}</dd></div> : null}
                <div>
                  <dt>개별 리뷰 제공 동의</dt>
                  <dd>
                    {item.reviewSharingAgreed == null ? (
                      "해당 없음"
                    ) : (
                      <Badge tone={item.reviewSharingAgreed ? "success" : "neutral"}>
                        {item.reviewSharingAgreed ? "동의" : "미동의"}
                      </Badge>
                    )}
                  </dd>
                </div>
                {item.hqConsentVersion ? (
                  <div><dt>본부 제공 동의</dt><dd>{item.hqConsentVersion} · {fmt(item.hqConsentAt)}</dd></div>
                ) : null}
              </dl>

              {item.status === "PENDING" ? (
                <div className="admin-request__actions">
                  <Button type="button" onClick={() => setAction({ item, kind: "APPROVE" })}>
                    승인하고 본부에 연결
                  </Button>
                  <Button type="button" variant="secondary" onClick={() => setAction({ item, kind: "REJECT" })}>
                    거절
                  </Button>
                </div>
              ) : null}
              {(item.status === "APPROVED" || item.status === "RELEASE_REQUESTED") ? (
                <div className="admin-request__actions">
                  <Button type="button" variant="danger" onClick={() => setAction({ item, kind: "RELEASE" })}>
                    소속 해제
                  </Button>
                </div>
              ) : null}
            </Card>
          </li>
        ))}
      </ul>

      <h2>가맹본부 소속 해제 요청 (레거시)</h2>
      {withdrawals.length === 0 ? (
        <p>접수된 요청이 없습니다.</p>
      ) : (
        <ul>
          {withdrawals.map((item) => (
            <li key={item.id}>{item.requesterName} · {item.requesterEmail} · {fmt(item.requestedAt)}</li>
          ))}
        </ul>
      )}

      <ReasonModal
        open={action !== null}
        title={
          action?.kind === "APPROVE" ? "소속 승인" : action?.kind === "REJECT" ? "소속 거절" : "소속 해제"
        }
        description={
          action?.kind === "APPROVE"
            ? `승인하면 ${action.item.brandName} 본부가 ${action.item.storeName} 의 리뷰·분석을 조회하게 됩니다.`
            : action?.kind === "REJECT"
            ? `${action.item.storeName} 의 소속 신청을 거절합니다.`
            : `${action?.item.storeName} 의 ${action?.item.brandName} 소속을 해제합니다. 해제 즉시 본부 집계·개별 리뷰 조회에서 제외됩니다.`
        }
        confirmLabel={action?.kind === "APPROVE" ? "승인" : action?.kind === "REJECT" ? "거절" : "해제"}
        danger={action?.kind !== "APPROVE"}
        onCancel={() => setAction(null)}
        onConfirm={runAction}
      />
    </div>
  );
}
