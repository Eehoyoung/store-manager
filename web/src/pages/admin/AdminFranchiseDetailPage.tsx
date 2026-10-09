import { useEffect, useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { adminApi } from "../../api/admin";
import type { AdminAuditLogRow, AdminFranchiseDetail, AdminFranchiseMember, AdminFranchisePricing } from "../../api/types";
import { ApiError } from "../../api/client";
import { describePriceBasis } from "../../lib/labels";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Field } from "../../components/Field";
import { Modal } from "../../components/Modal";
import { ReasonModal } from "../../components/ReasonModal";
import { Skeleton } from "../../components/Skeleton";

function fmt(iso: string | null): string {
  if (!iso) return "없음";
  return new Date(iso).toLocaleString("ko-KR");
}

// 표시용 미리보기 상수 — 실제 단가는 서버(다음 달 확정은 매월 25일 기준)가 계산한다.
const TIER_TABLE: { min: number; max: number | null; priceKrw: number }[] = [
  { min: 1, max: 49, priceKrw: 30000 },
  { min: 50, max: 99, priceKrw: 29000 },
  { min: 100, max: 199, priceKrw: 27000 },
  { min: 200, max: 299, priceKrw: 26000 },
  { min: 300, max: 399, priceKrw: 25000 },
  { min: 400, max: 499, priceKrw: 24000 },
  { min: 500, max: null, priceKrw: 23000 },
];

function previewTierPrice(storeCount: number): number {
  const tier = TIER_TABLE.find((t) => storeCount >= t.min && (t.max == null || storeCount <= t.max));
  return (tier ?? TIER_TABLE[0]).priceKrw;
}

type ReasonAction =
  | { type: "franchiseStatus"; next: "ACTIVE" | "SUSPENDED" }
  | { type: "joinCodeStatus"; next: boolean }
  | { type: "joinCodeRotate" }
  | { type: "memberStatus"; member: AdminFranchiseMember; next: "ACTIVE" | "REVOKED" }
  | { type: "memberRevokeSessions"; member: AdminFranchiseMember }
  | { type: "committedStoreCount"; value: number | null };

// 문서 26 §5.2~§5.4 — 본부 상세: 상태·가맹코드·담당자·승인 매장·감사기록. 모든 변경에 사유를 받는다.
export function AdminFranchiseDetailPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [detail, setDetail] = useState<AdminFranchiseDetail | null>(null);
  const [logs, setLogs] = useState<AdminAuditLogRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [reasonAction, setReasonAction] = useState<ReasonAction | null>(null);
  const [addMemberOpen, setAddMemberOpen] = useState(false);
  const [editMember, setEditMember] = useState<AdminFranchiseMember | null>(null);
  const [revealCode, setRevealCode] = useState<string | null>(null);
  const [retryTick, setRetryTick] = useState(0);
  const [pricing, setPricing] = useState<AdminFranchisePricing | null>(null);
  const [committedInput, setCommittedInput] = useState("");

  const load = () =>
    Promise.all([adminApi.franchiseDetail(brand), adminApi.auditLogs(brand), adminApi.pricing()])
      .then(([d, l, p]) => {
        setDetail(d);
        setLogs(l);
        const mine = p.find((row) => row.brandName === brand) ?? null;
        setPricing(mine);
        setCommittedInput(mine?.committedStoreCount != null ? String(mine.committedStoreCount) : "");
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "본부 정보를 불러오지 못했어요.");
      });

  useEffect(() => {
    setDetail(null);
    setError(null);
    setNotFound(false);
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brand, retryTick]);

  const runReasonAction = async (reason: string) => {
    if (!reasonAction) return;
    switch (reasonAction.type) {
      case "franchiseStatus":
        await adminApi.setFranchiseStatus(brand, reasonAction.next, reason);
        setDone(`본부 상태를 ${reasonAction.next === "ACTIVE" ? "활성" : "중지"}로 변경했어요.`);
        break;
      case "joinCodeStatus":
        await adminApi.setJoinCodeStatus(brand, reasonAction.next, reason);
        setDone(`가맹코드를 ${reasonAction.next ? "재활성화" : "중지"}했어요.`);
        break;
      case "joinCodeRotate": {
        const res = await adminApi.rotateJoinCode(brand, reason);
        setRevealCode(res.joinCode);
        setDone("가맹코드를 교체했어요. 기존 승인 매장에는 영향이 없어요.");
        break;
      }
      case "memberStatus":
        await adminApi.updateMember(brand, reasonAction.member.memberId, { status: reasonAction.next, reason });
        setDone(`${reasonAction.member.name} 담당자를 ${reasonAction.next === "ACTIVE" ? "재활성화" : "중지"}했어요.`);
        break;
      case "memberRevokeSessions":
        await adminApi.revokeMemberSessions(brand, reasonAction.member.memberId, reason);
        setDone(`${reasonAction.member.name} 담당자의 로그인 세션을 모두 종료했어요.`);
        break;
      case "committedStoreCount":
        await adminApi.setCommittedStoreCount(brand, reasonAction.value, reason);
        setDone(reasonAction.value != null ? `약정 매장 수를 ${reasonAction.value}곳으로 저장했어요.` : "약정 매장 수를 해제했어요.");
        break;
    }
    setReasonAction(null);
    await load();
  };

  if (notFound) {
    return <EmptyState title="존재하지 않는 가맹본부예요" />;
  }

  return (
    <div className="admin-page">
      {error ? (
        <EmptyState title="본부 정보를 불러오지 못했어요" description={error} action={<Button type="button" onClick={() => setRetryTick((t) => t + 1)}>다시 시도</Button>} />
      ) : null}
      {detail === null && !error ? <Skeleton height={300} /> : null}
      {done ? <p className="admin-page__done" role="status">{done}</p> : null}

      {detail ? (
        <>
          <div className="stores-page__header">
            <div>
              <h1>{detail.brandName}</h1>
              <p>생성 {fmt(detail.createdAt)}</p>
            </div>
            <div className="admin-request__actions">
              <Badge tone={detail.status === "ACTIVE" ? "success" : "danger"} icon={detail.status === "ACTIVE" ? "✓" : "⛔"}>
                {detail.status === "ACTIVE" ? "활성" : "중지"}
              </Badge>
              {detail.status === "ACTIVE" ? (
                <Button type="button" variant="danger" small onClick={() => setReasonAction({ type: "franchiseStatus", next: "SUSPENDED" })}>
                  본부 중지
                </Button>
              ) : (
                <Button type="button" small onClick={() => setReasonAction({ type: "franchiseStatus", next: "ACTIVE" })}>
                  본부 재활성화
                </Button>
              )}
            </div>
          </div>

          <Card>
            <h2>가맹 브랜드 구간 단가</h2>
            {pricing ? (
              <PricingSection
                pricing={pricing}
                committedInput={committedInput}
                onChangeCommittedInput={setCommittedInput}
                onSave={(value) => setReasonAction({ type: "committedStoreCount", value })}
              />
            ) : (
              <Skeleton height={80} />
            )}
          </Card>

          <Card>
            <h2>가맹코드</h2>
            {detail.joinCode ? (
              <>
                <p>
                  <Badge tone={detail.joinCode.active ? "success" : "neutral"}>{detail.joinCode.active ? "사용 중" : "중지됨"}</Badge>{" "}
                  최근 교체 {fmt(detail.joinCode.rotatedAt)}{detail.joinCode.rotatedByRef ? ` · ${detail.joinCode.rotatedByRef}` : ""}
                </p>
                <div className="admin-request__actions">
                  <Button type="button" variant="secondary" small onClick={() => setReasonAction({ type: "joinCodeRotate" })}>코드 교체</Button>
                  {detail.joinCode.active ? (
                    <Button type="button" variant="danger" small onClick={() => setReasonAction({ type: "joinCodeStatus", next: false })}>사용 중지</Button>
                  ) : (
                    <Button type="button" small onClick={() => setReasonAction({ type: "joinCodeStatus", next: true })}>재활성화</Button>
                  )}
                </div>
              </>
            ) : (
              <>
                <p>아직 발급되지 않았어요.</p>
                <Button type="button" small onClick={() => setReasonAction({ type: "joinCodeRotate" })}>최초 발급</Button>
              </>
            )}
            {revealCode ? (
              <p className="admin-page__done" role="status">
                새 가맹코드: <strong className="admin-request__mono">{revealCode}</strong> — 원문은 지금만 표시돼요.
              </p>
            ) : null}
          </Card>

          <Card>
            <div className="stores-page__header">
              <h2>담당자</h2>
              <Button type="button" small onClick={() => setAddMemberOpen(true)}>담당자 추가</Button>
            </div>
            {detail.members.length === 0 ? <EmptyState title="등록된 담당자가 없어요" /> : (
              <div className="hq-table-wrap">
                <table className="hq-store-table">
                  <thead>
                    <tr><th>이름</th><th>이메일</th><th>직책</th><th>상태</th><th>최근 로그인</th><th>등록일</th><th></th></tr>
                  </thead>
                  <tbody>
                    {detail.members.map((m) => (
                      <tr key={m.memberId}>
                        <td>{m.name}</td>
                        <td className="admin-request__mono">{m.email}</td>
                        <td>{m.title ?? "-"}</td>
                        <td><Badge tone={m.status === "ACTIVE" ? "success" : "danger"}>{m.status === "ACTIVE" ? "활성" : "중지"}</Badge></td>
                        <td>{fmt(m.lastLoginAt)}</td>
                        <td>{fmt(m.invitedAt)}</td>
                        <td>
                          <div className="admin-request__actions">
                            <Button type="button" variant="secondary" small onClick={() => setEditMember(m)}>수정</Button>
                            {m.status === "ACTIVE" ? (
                              <Button type="button" variant="danger" small onClick={() => setReasonAction({ type: "memberStatus", member: m, next: "REVOKED" })}>중지</Button>
                            ) : (
                              <Button type="button" small onClick={() => setReasonAction({ type: "memberStatus", member: m, next: "ACTIVE" })}>재활성화</Button>
                            )}
                            <Button type="button" variant="danger" small onClick={() => setReasonAction({ type: "memberRevokeSessions", member: m })}>세션 종료</Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card>
            <h2>승인된 매장 ({detail.approvedStores.length}개)</h2>
            {detail.approvedStores.length === 0 ? <EmptyState title="승인된 매장이 없어요" /> : (
              <div className="hq-table-wrap">
                <table className="hq-store-table">
                  <thead><tr><th>매장</th><th>주소</th><th>승인일</th></tr></thead>
                  <tbody>
                    {detail.approvedStores.map((s) => (
                      <tr key={s.storeId}><td>{s.storeName}</td><td>{s.address ?? "-"}</td><td>{fmt(s.approvedAt)}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card>
            <h2>감사기록</h2>
            {logs.length === 0 ? <EmptyState title="기록이 없어요" /> : (
              <ul className="admin-request-list">
                {logs.map((log, i) => (
                  <li key={i}>
                    <p><strong>{log.action}</strong> · {log.actorType} · {fmt(log.createdAt)}</p>
                    {log.detail ? <p className="admin-request__mono">{JSON.stringify(log.detail)}</p> : null}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </>
      ) : null}

      <ReasonModal
        open={reasonAction !== null}
        title={reasonTitle(reasonAction)}
        description={reasonDescription(reasonAction)}
        danger={reasonDanger(reasonAction)}
        onCancel={() => setReasonAction(null)}
        onConfirm={runReasonAction}
      />

      <AddMemberModal
        open={addMemberOpen}
        onClose={() => setAddMemberOpen(false)}
        onAdded={async () => { setAddMemberOpen(false); setDone("담당자를 추가했어요."); await load(); }}
        brand={brand}
      />
      <EditMemberModal
        member={editMember}
        onClose={() => setEditMember(null)}
        onSaved={async () => { setEditMember(null); setDone("담당자 정보를 수정했어요."); await load(); }}
        brand={brand}
      />
    </div>
  );
}

function reasonDanger(action: ReasonAction | null): boolean {
  if (!action) return false;
  switch (action.type) {
    case "franchiseStatus": return action.next === "SUSPENDED";
    case "joinCodeStatus": return !action.next;
    case "joinCodeRotate": return false;
    case "memberStatus": return action.next === "REVOKED";
    case "memberRevokeSessions": return true;
    case "committedStoreCount": return false;
  }
}

function reasonTitle(action: ReasonAction | null): string {
  if (!action) return "";
  switch (action.type) {
    case "franchiseStatus": return action.next === "SUSPENDED" ? "본부 중지" : "본부 재활성화";
    case "joinCodeStatus": return action.next ? "가맹코드 재활성화" : "가맹코드 사용 중지";
    case "joinCodeRotate": return "가맹코드 교체";
    case "memberStatus": return action.next === "REVOKED" ? "담당자 중지" : "담당자 재활성화";
    case "memberRevokeSessions": return "담당자 세션 강제 종료";
    case "committedStoreCount": return action.value != null ? "약정 매장 수 저장" : "약정 매장 수 해제";
  }
}

function reasonDescription(action: ReasonAction | null): string {
  if (!action) return "";
  switch (action.type) {
    case "franchiseStatus":
      return action.next === "SUSPENDED"
        ? "중지하면 이 본부의 모든 담당자 세션이 즉시 무효화되고, 다음 요청부터 거절돼요."
        : "재활성화하면 담당자가 다시 로그인할 수 있어요.";
    case "joinCodeStatus":
      return action.next ? "가맹코드를 다시 사용할 수 있게 해요." : "가맹코드를 더 이상 사용할 수 없게 해요. 기존 승인 매장에는 영향이 없어요.";
    case "joinCodeRotate":
      return "기존 코드는 즉시 무효가 되고 새 코드가 발급돼요. 기존 승인 매장에는 영향이 없어요.";
    case "memberStatus":
      return action.next === "REVOKED"
        ? `${action.member.name} 담당자를 중지하면 즉시 모든 세션이 종료되고 로그인할 수 없어요.`
        : `${action.member.name} 담당자를 다시 로그인할 수 있게 해요.`;
    case "memberRevokeSessions":
      return `${action.member.name} 담당자의 모든 로그인 세션을 즉시 종료해요. 다음 요청부터 거절돼요.`;
    case "committedStoreCount":
      return action.value != null
        ? `약정 매장 수를 ${action.value}곳으로 저장해요. 다음 달 단가 계산에 반영돼요.`
        : "약정 매장 수를 해제해요. 다음 달부터 실제 유료 이용 매장 수로 단가가 계산돼요.";
  }
}

function PricingSection({
  pricing,
  committedInput,
  onChangeCommittedInput,
  onSave,
}: {
  pricing: AdminFranchisePricing;
  committedInput: string;
  onChangeCommittedInput: (v: string) => void;
  onSave: (value: number | null) => void;
}) {
  const trimmed = committedInput.trim();
  const parsed = trimmed === "" ? null : Number(trimmed);
  const valid = trimmed === "" || (Number.isInteger(parsed) && (parsed as number) > 0);
  const previewCount = parsed ?? pricing.paidStoreCount;

  return (
    <>
      <table>
        <tbody>
          <tr><th>현재 유료 이용 매장</th><td>{pricing.paidStoreCount}곳</td></tr>
          <tr>
            <th>이번 달 단가</th>
            <td>
              {pricing.thisMonth.unitPriceKrw.toLocaleString("ko-KR")}원{" "}
              <Badge tone={pricing.thisMonth.confirmed ? "success" : "neutral"}>{pricing.thisMonth.confirmed ? "확정" : "예상"}</Badge>{" "}
              <small className="field__hint">{describePriceBasis(pricing.thisMonth)}</small>
            </td>
          </tr>
          <tr>
            <th>다음 달 단가</th>
            <td>
              {pricing.nextMonth.unitPriceKrw.toLocaleString("ko-KR")}원{" "}
              <Badge tone={pricing.nextMonth.confirmed ? "success" : "neutral"}>{pricing.nextMonth.confirmed ? "확정" : "예상"}</Badge>{" "}
              <small className="field__hint">{describePriceBasis(pricing.nextMonth)}</small>
            </td>
          </tr>
        </tbody>
      </table>
      <Field
        label="약정 매장 수 (선택)"
        type="number"
        min={1}
        value={committedInput}
        onChange={(e) => onChangeCommittedInput(e.target.value)}
        hint="비우면 약정을 해제하고, 실제 유료 이용 매장 수로 다음 달 단가를 계산해요."
        error={!valid ? "1 이상의 정수를 입력해 주세요." : undefined}
      />
      <p className="field__hint">
        미리보기: {previewCount}곳 기준 적용 단가{" "}
        <strong>{previewTierPrice(previewCount).toLocaleString("ko-KR")}원(VAT 별도)</strong> — 표시용이며 실제 확정은 매월 25일 기준이에요.
      </p>
      <Button type="button" small disabled={!valid} onClick={() => onSave(parsed)}>저장</Button>
    </>
  );
}

function AddMemberModal({ open, onClose, onAdded, brand }: { open: boolean; onClose: () => void; onAdded: () => Promise<void>; brand: string }) {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [title, setTitle] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const reset = () => { setName(""); setEmail(""); setTitle(""); setReason(""); setError(null); };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !email.trim() || !reason.trim()) { setError("필수 항목을 모두 입력해 주세요."); return; }
    setError(null);
    setLoading(true);
    try {
      await adminApi.addMember(brand, { name: name.trim(), email: email.trim(), title: title.trim() || undefined, reason: reason.trim() });
      reset();
      await onAdded();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "담당자를 추가하지 못했어요.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      title="담당자 추가"
      onClose={() => { reset(); onClose(); }}
      footer={<>
        <Button type="button" variant="secondary" onClick={() => { reset(); onClose(); }} disabled={loading}>취소</Button>
        <Button type="submit" form="add-member-form" loading={loading}>추가</Button>
      </>}
    >
      <form id="add-member-form" onSubmit={(e) => void submit(e)} noValidate>
        <Field label="이름" required value={name} onChange={(e) => setName(e.target.value)} />
        <Field label="이메일" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="직책 (선택)" value={title} onChange={(e) => setTitle(e.target.value)} />
        <Field label="추가 사유" required value={reason} onChange={(e) => setReason(e.target.value)} />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
      </form>
    </Modal>
  );
}

function EditMemberModal({ member, onClose, onSaved, brand }: { member: AdminFranchiseMember | null; onClose: () => void; onSaved: () => Promise<void>; brand: string }) {
  const [name, setName] = useState(member?.name ?? "");
  const [title, setTitle] = useState(member?.title ?? "");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setName(member?.name ?? "");
    setTitle(member?.title ?? "");
    setReason("");
    setError(null);
  }, [member]);

  if (!member) return null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !reason.trim()) { setError("이름과 수정 사유를 입력해 주세요."); return; }
    setError(null);
    setLoading(true);
    try {
      await adminApi.updateMember(brand, member.memberId, { name: name.trim(), title: title.trim() || undefined, reason: reason.trim() });
      await onSaved();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "담당자 정보를 수정하지 못했어요.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open
      title={`${member.name} 담당자 수정`}
      onClose={onClose}
      footer={<>
        <Button type="button" variant="secondary" onClick={onClose} disabled={loading}>취소</Button>
        <Button type="submit" form="edit-member-form" loading={loading}>저장</Button>
      </>}
    >
      <form id="edit-member-form" onSubmit={(e) => void submit(e)} noValidate>
        <Field label="이름" required value={name} onChange={(e) => setName(e.target.value)} />
        <Field label="직책" value={title} onChange={(e) => setTitle(e.target.value)} />
        <Field label="수정 사유" required value={reason} onChange={(e) => setReason(e.target.value)} />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
      </form>
    </Modal>
  );
}
