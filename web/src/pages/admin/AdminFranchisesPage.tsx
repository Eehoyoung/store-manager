import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { adminApi } from "../../api/admin";
import type { AdminFranchiseListItem, AdminFranchisePricing, MonthPrice } from "../../api/types";
import { ApiError } from "../../api/client";
import { describePriceBasis } from "../../lib/labels";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Field } from "../../components/Field";
import { Modal } from "../../components/Modal";
import { Skeleton } from "../../components/Skeleton";

function fmt(iso: string | null): string {
  if (!iso) return "없음";
  return new Date(iso).toLocaleString("ko-KR");
}

function MonthPriceCell({ p }: { p: MonthPrice }) {
  return (
    <td>
      <div>
        {p.unitPriceKrw.toLocaleString("ko-KR")}원{" "}
        <Badge tone={p.confirmed ? "success" : "neutral"}>{p.confirmed ? "확정" : "예상"}</Badge>
      </div>
      <small className="field__hint">{describePriceBasis(p)}</small>
    </td>
  );
}

// 문서 26 §5.1·§5.2 — 가맹본부 목록·검색·생성. 시스템 콘솔의 첫 화면이다.
export function AdminFranchisesPage() {
  const [q, setQ] = useState("");
  const [items, setItems] = useState<AdminFranchiseListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [revealCode, setRevealCode] = useState<{ brandName: string; joinCode: string } | null>(null);
  const [retryTick, setRetryTick] = useState(0);
  const [pricing, setPricing] = useState<AdminFranchisePricing[] | null>(null);
  const [pricingError, setPricingError] = useState<string | null>(null);

  useEffect(() => {
    adminApi
      .franchises(q || undefined)
      .then(setItems)
      .catch((e) => setError(e instanceof ApiError ? e.message : "가맹본부 목록을 불러오지 못했습니다."));
  }, [q, retryTick]);

  useEffect(() => {
    adminApi
      .pricing()
      .then(setPricing)
      .catch((e) => setPricingError(e instanceof ApiError ? e.message : "브랜드별 단가를 불러오지 못했습니다."));
  }, [retryTick]);

  return (
    <div className="admin-page">
      <div className="stores-page__header">
        <div>
          <h1>가맹본부 관리</h1>
          <p>가맹본부 생성·담당자·가맹코드를 관리합니다.</p>
        </div>
        <Button type="button" onClick={() => setCreateOpen(true)}>본부 생성</Button>
      </div>

      <Field label="검색" placeholder="브랜드명·담당자 이메일·매장명" value={q} onChange={(e) => setQ(e.target.value)} />

      {error ? (
        <EmptyState
          title="가맹본부 목록을 불러오지 못했습니다"
          description={error}
          action={<Button type="button" onClick={() => setRetryTick((t) => t + 1)}>다시 시도</Button>}
        />
      ) : null}

      {items === null && !error ? <Skeleton height={200} /> : null}
      {items && items.length === 0 ? <EmptyState title="등록된 가맹본부가 없습니다" /> : null}

      {items && items.length > 0 ? (
        <div className="hq-table-wrap">
          <table className="hq-store-table">
            <thead>
              <tr>
                <th>브랜드</th>
                <th>상태</th>
                <th>담당자</th>
                <th>승인 매장</th>
                <th>소속 대기</th>
                <th>가맹코드</th>
                <th>최근 본부 로그인</th>
              </tr>
            </thead>
            <tbody>
              {items.map((f) => (
                <tr key={f.brandName}>
                  <td><Link to={`/admin/franchises/${encodeURIComponent(f.brandName)}`}>{f.brandName}</Link></td>
                  <td>
                    <Badge tone={f.status === "ACTIVE" ? "success" : "danger"} icon={f.status === "ACTIVE" ? "✓" : "⛔"}>
                      {f.status === "ACTIVE" ? "활성" : "중지"}
                    </Badge>
                  </td>
                  <td>{f.activeMemberCount} / {f.memberCount}명</td>
                  <td>{f.approvedStoreCount}개</td>
                  <td>{f.pendingRequestCount > 0 ? <Badge tone="warning">{f.pendingRequestCount}건</Badge> : "0건"}</td>
                  <td>
                    <Badge tone={f.joinCodeActive ? "success" : "neutral"}>{f.joinCodeActive ? "사용 중" : "미발급·중지"}</Badge>
                  </td>
                  <td>{fmt(f.lastHqLoginAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <Card>
        <h2>브랜드별 단가</h2>
        <p className="field__hint">매월 25일 기준 유료 이용 매장 수로 다음 달 단가가 확정됩니다.</p>
        {pricingError ? <EmptyState title="브랜드별 단가를 불러오지 못했습니다" description={pricingError} /> : null}
        {pricing === null && !pricingError ? <Skeleton height={120} /> : null}
        {pricing && pricing.length === 0 ? <EmptyState title="등록된 가맹본부가 없습니다" /> : null}
        {pricing && pricing.length > 0 ? (
          <div className="hq-table-wrap">
            <table className="hq-store-table">
              <thead>
                <tr>
                  <th>브랜드</th>
                  <th>약정 매장</th>
                  <th>현재 유료 이용 매장</th>
                  <th>지난달</th>
                  <th>이번 달</th>
                  <th>다음 달</th>
                </tr>
              </thead>
              <tbody>
                {pricing.map((p) => (
                  <tr key={p.brandName}>
                    <td><Link to={`/admin/franchises/${encodeURIComponent(p.brandName)}`}>{p.brandName}</Link></td>
                    <td>{p.committedStoreCount != null ? `${p.committedStoreCount}곳` : "미설정"}</td>
                    <td>{p.paidStoreCount}곳</td>
                    <MonthPriceCell p={p.lastMonth} />
                    <MonthPriceCell p={p.thisMonth} />
                    <MonthPriceCell p={p.nextMonth} />
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Card>

      <CreateFranchiseModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(brandName, joinCode) => {
          setCreateOpen(false);
          if (joinCode) setRevealCode({ brandName, joinCode });
          setRetryTick((t) => t + 1);
        }}
      />

      {revealCode ? (
        <Modal open title="가맹코드 발급 완료" onClose={() => setRevealCode(null)} footer={<Button type="button" onClick={() => setRevealCode(null)}>닫기</Button>}>
          <p>{revealCode.brandName} 의 가맹코드입니다. <strong>원문은 지금만 표시됩니다</strong> — 반드시 지금 복사해 전달하세요.</p>
          <Card className="admin-request__mono" style={{ fontSize: "1.2em", textAlign: "center" }}>{revealCode.joinCode}</Card>
          <Button
            type="button"
            variant="secondary"
            onClick={() => void navigator.clipboard.writeText(revealCode.joinCode).catch(() => undefined)}
          >
            복사
          </Button>
        </Modal>
      ) : null}
    </div>
  );
}

function CreateFranchiseModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (brandName: string, joinCode: string | null) => void;
}) {
  const [brandName, setBrandName] = useState("");
  const [memberName, setMemberName] = useState("");
  const [memberEmail, setMemberEmail] = useState("");
  const [memberTitle, setMemberTitle] = useState("");
  const [issueJoinCode, setIssueJoinCode] = useState(true);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const reset = () => {
    setBrandName("");
    setMemberName("");
    setMemberEmail("");
    setMemberTitle("");
    setIssueJoinCode(true);
    setReason("");
    setError(null);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!brandName.trim() || !memberName.trim() || !memberEmail.trim() || !reason.trim()) {
      setError("필수 항목을 모두 입력해 주세요.");
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const res = await adminApi.createFranchise({
        brandName: brandName.trim(),
        memberName: memberName.trim(),
        memberEmail: memberEmail.trim(),
        memberTitle: memberTitle.trim() || undefined,
        issueJoinCode,
        reason: reason.trim(),
      });
      reset();
      onCreated(res.brandName, res.joinCode);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "본부를 생성하지 못했습니다.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      title="가맹본부 생성"
      onClose={() => { reset(); onClose(); }}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={() => { reset(); onClose(); }} disabled={loading}>취소</Button>
          <Button type="submit" form="create-franchise-form" loading={loading}>생성</Button>
        </>
      }
    >
      <form id="create-franchise-form" onSubmit={(e) => void submit(e)} noValidate>
        <Field label="브랜드명" required value={brandName} onChange={(e) => setBrandName(e.target.value)} />
        <Field label="최초 담당자 이름" required value={memberName} onChange={(e) => setMemberName(e.target.value)} />
        <Field label="최초 담당자 이메일" type="email" required value={memberEmail} onChange={(e) => setMemberEmail(e.target.value)} />
        <Field label="담당자 직책 (선택)" value={memberTitle} onChange={(e) => setMemberTitle(e.target.value)} />
        <label className="consent-check">
          <input type="checkbox" checked={issueJoinCode} onChange={(e) => setIssueJoinCode(e.target.checked)} />
          가맹코드 즉시 발급
        </label>
        <Field label="생성 사유" required value={reason} onChange={(e) => setReason(e.target.value)} hint="나중에 확인할 수 있는 구체적인 사유를 적어 주세요." />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
      </form>
    </Modal>
  );
}
