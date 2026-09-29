import { useEffect, useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { adminApi } from "../../api/admin";
import type { AdminAuditLogRow, AdminFranchiseDetail, AdminFranchiseMember } from "../../api/types";
import { ApiError } from "../../api/client";
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

type ReasonAction =
  | { type: "franchiseStatus"; next: "ACTIVE" | "SUSPENDED" }
  | { type: "joinCodeStatus"; next: boolean }
  | { type: "joinCodeRotate" }
  | { type: "memberStatus"; member: AdminFranchiseMember; next: "ACTIVE" | "REVOKED" }
  | { type: "memberRevokeSessions"; member: AdminFranchiseMember };

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

  const load = () =>
    Promise.all([adminApi.franchiseDetail(brand), adminApi.auditLogs(brand)])
      .then(([d, l]) => { setDetail(d); setLogs(l); })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "본부 정보를 불러오지 못했습니다.");
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
        setDone(`본부 상태를 ${reasonAction.next === "ACTIVE" ? "활성" : "중지"}로 변경했습니다.`);
        break;
      case "joinCodeStatus":
        await adminApi.setJoinCodeStatus(brand, reasonAction.next, reason);
        setDone(`가맹코드를 ${reasonAction.next ? "재활성화" : "중지"}했습니다.`);
        break;
      case "joinCodeRotate": {
        const res = await adminApi.rotateJoinCode(brand, reason);
        setRevealCode(res.joinCode);
        setDone("가맹코드를 교체했습니다. 기존 승인 매장에는 영향이 없습니다.");
        break;
      }
      case "memberStatus":
        await adminApi.updateMember(brand, reasonAction.member.memberId, { status: reasonAction.next, reason });
        setDone(`${reasonAction.member.name} 담당자를 ${reasonAction.next === "ACTIVE" ? "재활성화" : "중지"}했습니다.`);
        break;
      case "memberRevokeSessions":
        await adminApi.revokeMemberSessions(brand, reasonAction.member.memberId, reason);
        setDone(`${reasonAction.member.name} 담당자의 로그인 세션을 모두 종료했습니다.`);
        break;
    }
    setReasonAction(null);
    await load();
  };

  if (notFound) {
    return <EmptyState title="존재하지 않는 가맹본부입니다" />;
  }

  return (
    <div className="admin-page">
      {error ? (
        <EmptyState title="본부 정보를 불러오지 못했습니다" description={error} action={<Button type="button" onClick={() => setRetryTick((t) => t + 1)}>다시 시도</Button>} />
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
                <p>아직 발급되지 않았습니다.</p>
                <Button type="button" small onClick={() => setReasonAction({ type: "joinCodeRotate" })}>최초 발급</Button>
              </>
            )}
            {revealCode ? (
              <p className="admin-page__done" role="status">
                새 가맹코드: <strong className="admin-request__mono">{revealCode}</strong> — 원문은 지금만 표시됩니다.
              </p>
            ) : null}
          </Card>

          <Card>
            <div className="stores-page__header">
              <h2>담당자</h2>
              <Button type="button" small onClick={() => setAddMemberOpen(true)}>담당자 추가</Button>
            </div>
            {detail.members.length === 0 ? <EmptyState title="등록된 담당자가 없습니다" /> : (
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
            {detail.approvedStores.length === 0 ? <EmptyState title="승인된 매장이 없습니다" /> : (
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
            {logs.length === 0 ? <EmptyState title="기록이 없습니다" /> : (
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
        onAdded={async () => { setAddMemberOpen(false); setDone("담당자를 추가했습니다."); await load(); }}
        brand={brand}
      />
      <EditMemberModal
        member={editMember}
        onClose={() => setEditMember(null)}
        onSaved={async () => { setEditMember(null); setDone("담당자 정보를 수정했습니다."); await load(); }}
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
  }
}

function reasonDescription(action: ReasonAction | null): string {
  if (!action) return "";
  switch (action.type) {
    case "franchiseStatus":
      return action.next === "SUSPENDED"
        ? "중지하면 이 본부의 모든 담당자 세션이 즉시 무효화되고, 다음 요청부터 거절됩니다."
        : "재활성화하면 담당자가 다시 로그인할 수 있습니다.";
    case "joinCodeStatus":
      return action.next ? "가맹코드를 다시 사용할 수 있게 합니다." : "가맹코드를 더 이상 사용할 수 없게 합니다. 기존 승인 매장에는 영향이 없습니다.";
    case "joinCodeRotate":
      return "기존 코드는 즉시 무효가 되고 새 코드가 발급됩니다. 기존 승인 매장에는 영향이 없습니다.";
    case "memberStatus":
      return action.next === "REVOKED"
        ? `${action.member.name} 담당자를 중지하면 즉시 모든 세션이 종료되고 로그인할 수 없습니다.`
        : `${action.member.name} 담당자를 다시 로그인할 수 있게 합니다.`;
    case "memberRevokeSessions":
      return `${action.member.name} 담당자의 모든 로그인 세션을 즉시 종료합니다. 다음 요청부터 거절됩니다.`;
  }
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
      setError(err instanceof ApiError ? err.message : "담당자를 추가하지 못했습니다.");
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
      setError(err instanceof ApiError ? err.message : "담당자 정보를 수정하지 못했습니다.");
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
