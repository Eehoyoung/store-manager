import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { accountApi } from "../api/account";
import { ApiError } from "../api/client";
import type { AccountProfile } from "../api/types";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { formatPhone } from "../lib/format";
import { describeAgreement } from "../lib/labels";
import { Field } from "../components/Field";
import { Modal } from "../components/Modal";
import { Skeleton } from "../components/Skeleton";
import { useToast } from "../components/Toast";
import { useAuth } from "../auth/AuthContext";
import { agreementsApi, type AgreementHistoryRow, type HqWithdrawalStatus } from "../api/agreements";
import { naverExtensionApi } from "../api/naverExtension";

export function SettingsPage() {
  const [profile, setProfile] = useState<AccountProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<"account" | "connections" | "agreements">("account");
  const load = () => accountApi.get().then(setProfile).catch((e) => setError(e instanceof ApiError ? e.message : "계정 정보를 불러오지 못했어요."));
  useEffect(() => { void load(); }, []);

  if (!profile && !error) return <div className="settings-page"><Skeleton height={180} /><Skeleton height={220} /></div>;
  if (error || !profile) return <div className="settings-page"><p className="auth-card__error" role="alert">{error ?? "계정 정보를 불러오지 못했어요."}</p><Button type="button" onClick={() => { setError(null); void load(); }}>다시 시도</Button></div>;

  return (
    <div className="settings-page">
      <h1>설정</h1>
      <p className="settings-page__intro">계정과 연결, 동의 기록을 필요한 항목별로 관리해요.</p>
      <div className="settings-page__tabs" role="tablist" aria-label="설정 항목">
        {([["account", "계정·보안"], ["connections", "서비스 연결"], ["agreements", "동의 기록"]] as const).map(([id, label]) => (
          <button key={id} type="button" role="tab" aria-selected={tab === id} aria-controls={`settings-panel-${id}`}
            className={`settings-page__tab${tab === id ? " settings-page__tab--active" : ""}`} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      <section id="settings-panel-account" role="tabpanel" hidden={tab !== "account"}>
        <ProfileCard profile={profile} onUpdated={setProfile} />
        <PasswordCard />
        <SessionCard />
      </section>
      <section id="settings-panel-connections" role="tabpanel" hidden={tab !== "connections"}>
        <Card className="settings-page__card">
          <h2>배달앱 계정 연결</h2>
          <p>배민·요기요·쿠팡이츠 계정과 매장별 연결 상태를 관리해요.</p>
          <Link to="/platform-accounts" className="btn btn--secondary">배달앱 계정 관리</Link>
        </Card>
        <NaverPairingCard />
        <NaverPinCard />
        <Card className="settings-page__security-note">
          <h2>서비스 보안</h2>
          <p>배달앱 비밀번호는 별도 봉투암호화로 저장되며 이 화면에 표시하지 않아요.</p>
          <p>DataAPI 토큰과 LOGINPWD 공식 규격 확인 전에는 외부 계정 검증을 수행하지 않아요.</p>
        </Card>
      </section>
      <section id="settings-panel-agreements" role="tabpanel" hidden={tab !== "agreements"}>
        <AgreementHistoryCard />
        <HqReviewSharingCard />
      </section>
    </div>
  );
}

function AgreementHistoryCard() {
  const [rows, setRows] = useState<AgreementHistoryRow[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [withdrawal, setWithdrawal] = useState<HqWithdrawalStatus | null>(null);
  const [submitting, setSubmitting] = useState(false);
  useEffect(() => {
    agreementsApi.history().then(setRows).catch(() => setMessage("동의 내역을 불러오지 못했어요."));
    agreementsApi.hqWithdrawalStatus().then(setWithdrawal).catch(() => setMessage("소속 해제 요청 상태를 확인하지 못했어요."));
  }, []);
  const withdraw = async () => {
    if (!withdrawal?.canRequest || withdrawal.requested || submitting) return;
    setSubmitting(true);
    try {
      await agreementsApi.requestHqWithdrawal();
      setWithdrawal({ ...withdrawal, canRequest: false, requested: true });
      setMessage("가맹본부 소속 해제 요청이 접수됐어요. 실제 해제는 운영자가 처리해요.");
      const [historyResult, statusResult] = await Promise.allSettled([agreementsApi.history(), agreementsApi.hqWithdrawalStatus()]);
      if (historyResult.status === "fulfilled") setRows(historyResult.value);
      if (statusResult.status === "fulfilled") setWithdrawal(statusResult.value);
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "해제 요청을 접수하지 못했어요. 다시 확인해 주세요.");
      agreementsApi.hqWithdrawalStatus().then(setWithdrawal).catch(() => undefined);
    } finally {
      setSubmitting(false);
    }
  };
  return <Card className="settings-page__card"><h2>동의 내역</h2>
    <p className="field__hint">동의 여부, 적용한 문서 버전과 시각을 확인할 수 있어요.</p>
    {message ? <p role="status">{message}</p> : null}
    {rows.length ? <ul className="settings-page__agreement-list">{rows.map((row, index) => <li key={`${row.code}-${row.agreedAt}-${index}`}>
      <strong>{describeAgreement(row.code)}</strong><span>{row.agreed ? "동의" : "철회/거부"}</span>
      <time dateTime={row.agreedAt}>{new Date(row.agreedAt).toLocaleString("ko-KR")}</time>
      <span>문서 {row.docVersion}</span><Link to={row.documentUrl}>전문 보기</Link>
    </li>)}</ul> : <p>표시할 동의 내역이 없어요.</p>}
    <p>필수 동의는 이 화면에서 철회할 수 없고 회원 탈퇴로만 철회할 수 있어요.</p>
    {withdrawal?.canRequest && !withdrawal.requested ? <div className="settings-page__withdraw">
      <p>{withdrawal.brandName ? `${withdrawal.brandName} 소속 해제 요청을 접수할 수 있어요. 실제 해제는 운영자가 처리합니다.` : "가맹본부 소속 해제 요청을 접수할 수 있어요."}</p>
      <Button type="button" variant="secondary" loading={submitting} disabled={submitting} onClick={() => void withdraw()}>소속 해제 요청</Button>
    </div> : null}
    {withdrawal?.requested ? <p role="status">가맹본부 소속 해제 요청이 접수돼 처리 중이에요.</p> : null}
    {withdrawal && !withdrawal.canRequest && !withdrawal.requested && !withdrawal.brandName ? <p>현재 가맹본부 소속 매장이 없어 해제 요청을 할 수 없어요.</p> : null}
  </Card>;
}

/**
 * 문서 26 §4.2, 26a decisions.reviewFlag/ownerConsentUi — 개별 리뷰 제공 동의 카드.
 * 기능 플래그(reviewSharingEnabled)가 꺼져 있으면 렌더링 자체를 하지 않는다 — 화면에서
 * "동의할 수 있는데 안 보인다"가 아니라 애초에 기능이 없는 것처럼 보여야 한다.
 */
function HqReviewSharingCard() {
  const [status, setStatus] = useState<HqWithdrawalStatus | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  useEffect(() => {
    agreementsApi.hqWithdrawalStatus().then(setStatus).catch(() => undefined);
  }, []);

  if (!status?.reviewSharingEnabled) return null;

  const agree = async () => {
    setSubmitting(true);
    setMessage(null);
    try {
      await agreementsApi.setHqReviewSharing(true);
      setStatus({ ...status, reviewSharingAgreed: true });
      setMessage("개별 리뷰 제공에 동의했습니다.");
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "동의를 저장하지 못했어요.");
    } finally {
      setSubmitting(false);
    }
  };

  const withdraw = async () => {
    setSubmitting(true);
    setMessage(null);
    try {
      await agreementsApi.setHqReviewSharing(false);
      setStatus({ ...status, reviewSharingAgreed: false });
      setMessage("개별 리뷰 제공 동의를 철회했습니다. 이후 본부는 개별 리뷰를 조회할 수 없습니다.");
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "철회를 저장하지 못했어요.");
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  };

  return (
    <Card className="settings-page__card">
      <div className="settings-page__card-head">
        <h2>개별 리뷰 제공 동의</h2>
        <Badge tone={status.reviewSharingAgreed ? "success" : "neutral"} icon={status.reviewSharingAgreed ? "✓" : "•"}>
          {status.reviewSharingAgreed ? "동의함" : "동의 안 함"}
        </Badge>
      </div>
      <p>
        동의하면 가맹본부 담당자가 이 매장의 개별 리뷰 원문·별점·작성일·분석 결과를 조회할 수 있습니다.
        집계(가맹점 현황·이상징후)는 이 동의와 별개로 이미 제공되고 있습니다.
      </p>
      <Link to="/legal/hq-review-sharing">제공 항목 전문 보기</Link>
      {message ? <p role="status">{message}</p> : null}
      {status.reviewSharingAgreed ? (
        <Button type="button" variant="secondary" onClick={() => setConfirmOpen(true)}>동의 철회</Button>
      ) : (
        <Button type="button" loading={submitting} onClick={() => void agree()}>동의</Button>
      )}
      <Modal
        open={confirmOpen}
        title="개별 리뷰 제공 동의 철회"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirmOpen(false)} disabled={submitting}>취소</Button>
            <Button type="button" variant="danger" loading={submitting} onClick={() => void withdraw()}>철회</Button>
          </>
        }
      >
        <p>철회하면 가맹본부는 이 매장의 개별 리뷰를 더 이상 조회할 수 없습니다. 집계 조회에는 영향이 없습니다.</p>
      </Modal>
    </Card>
  );
}

function ProfileCard({ profile, onUpdated }: { profile: AccountProfile; onUpdated: (profile: AccountProfile) => void }) {
  const { updateUser } = useAuth();
  const [name, setName] = useState(profile.name);
  const [phone, setPhone] = useState(profile.phone ?? "");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const toast = useToast();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!name.trim()) return setError("이름을 입력해 주세요.");
    setLoading(true);
    try {
      const updated = await accountApi.update({ name: name.trim(), phone: phone.trim() || undefined });
      onUpdated(updated);
      updateUser({ id: updated.id, name: updated.name, email: updated.email });
      toast.show("계정 정보를 저장했어요.", "success");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "계정 정보 저장에 실패했어요.");
    } finally {
      setLoading(false);
    }
  };
  return (
    <Card className="settings-page__card">
      <div className="settings-page__card-head"><h2>계정 정보</h2><Badge tone={profile.status === "ACTIVE" ? "success" : "warning"} icon={profile.status === "ACTIVE" ? "✓" : "•"}>{profile.status === "ACTIVE" ? "사용 중" : profile.status}</Badge></div>
      <form onSubmit={submit} noValidate>
        <Field label="이메일" type="email" value={profile.email} readOnly hint="로그인 이메일은 설정 화면에서 변경할 수 없어요." />
        <Field label="이름" required value={name} onChange={(e: ChangeEvent<HTMLInputElement>) => setName(e.target.value)} />
        <Field label="휴대폰 번호 (선택)" value={phone} type="tel" inputMode="numeric" autoComplete="tel"
               hint="숫자만 입력하세요. 하이픈은 자동으로 들어가요."
               onChange={(e: ChangeEvent<HTMLInputElement>) => setPhone(formatPhone(e.target.value))} />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
        <Button type="submit" loading={loading}>저장</Button>
      </form>
    </Card>
  );
}

function PasswordCard() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const toast = useToast();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (newPassword.length < 8) return setError("새 비밀번호는 8자 이상이어야 해요.");
    if (newPassword !== confirm) return setError("새 비밀번호가 일치하지 않아요.");
    setLoading(true);
    try {
      await accountApi.changePassword({ currentPassword, newPassword });
      setCurrentPassword(""); setNewPassword(""); setConfirm("");
      toast.show("비밀번호를 변경했어요.", "success");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "비밀번호 변경에 실패했어요.");
    } finally {
      setLoading(false);
    }
  };
  return (
    <Card className="settings-page__card">
      <h2>비밀번호 변경</h2>
      <form onSubmit={submit} noValidate>
        <Field label="현재 비밀번호" required type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} autoComplete="current-password" />
        <Field label="새 비밀번호" required type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} autoComplete="new-password" hint="8자 이상 입력해 주세요." />
        <Field label="새 비밀번호 확인" required type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
        <Button type="submit" loading={loading}>비밀번호 변경</Button>
      </form>
    </Card>
  );
}

function formatMmSs(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const m = Math.floor(clamped / 60);
  const s = clamped % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function NaverPairingCard() {
  const [pairing, setPairing] = useState<{ code: string; expiresAt: number } | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copyMessage, setCopyMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!pairing) return;
    const tick = () => setRemaining(Math.round((pairing.expiresAt - Date.now()) / 1000));
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [pairing]);

  const issue = async () => {
    setLoading(true);
    setError(null);
    setCopyMessage(null);
    try {
      const res = await naverExtensionApi.issuePairingCode();
      setPairing({ code: res.code, expiresAt: Date.now() + res.expiresInSeconds * 1000 });
    } catch (e) {
      setPairing(null);
      setError(e instanceof ApiError ? e.message : "페어링 코드 발급에 실패했어요.");
    } finally {
      setLoading(false);
    }
  };

  const copy = async () => {
    if (!pairing) return;
    try {
      await navigator.clipboard.writeText(pairing.code);
      setCopyMessage("코드를 복사했어요.");
    } catch {
      setCopyMessage("복사에 실패했어요. 코드를 직접 선택해 복사해 주세요.");
    }
  };

  const expired = pairing !== null && remaining <= 0;

  return (
    <Card className="settings-page__card">
      <h2>네이버 확장 연동</h2>
      <p className="settings-page__naver-honesty">
        네이버 리뷰는 자동으로 게시되지 않아요. 확장이 답글을 입력창에 채워 드리면 등록 버튼은 사장님이 직접 누르셔야 해요.
      </p>
      <ol className="settings-page__naver-steps">
        <li>크롬에 확장 프로그램을 설치해요.</li>
        <li>확장의 사이드패널을 열어요.</li>
        <li>아래 코드를 사이드패널에 입력해요.</li>
      </ol>
      {!pairing || expired ? (
        <>
          {expired ? <p role="status">코드가 만료됐어요. 다시 발급해 주세요.</p> : null}
          <Button type="button" loading={loading} onClick={() => void issue()}>
            {expired ? "다시 발급" : "확장 연결하기"}
          </Button>
        </>
      ) : (
        <div className="settings-page__naver-code">
          <p className="settings-page__naver-code-value" aria-live="polite">{pairing.code}</p>
          <p className="settings-page__naver-code-timer" role="status">남은 시간 {formatMmSs(remaining)}</p>
          <div className="settings-page__naver-code-actions">
            <Button type="button" variant="secondary" onClick={() => void copy()}>복사</Button>
          </div>
          {copyMessage ? <p role="status">{copyMessage}</p> : null}
        </div>
      )}
      {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
    </Card>
  );
}

function NaverPinCard() {
  const [pin, setPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!/^\d{4,8}$/.test(pin)) return setError("PIN 은 4~8자리 숫자로 입력해 주세요.");
    if (pin !== confirmPin) return setError("PIN 이 일치하지 않아요.");
    setLoading(true);
    try {
      await naverExtensionApi.setPin(pin);
      setPin("");
      setConfirmPin("");
      toast.show("PIN 설정을 완료했어요.", "success");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "PIN 설정에 실패했어요.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="settings-page__card">
      <h2>일괄 승인 PIN</h2>
      <p>
        PIN 을 설정해야 일괄 승인을 쓸 수 있어요. 공용 포스 PC 에서 직원이 대신 승인하는 것을
        막기 위한 장치예요. 미설정 상태에서는 일괄 승인이 동작하지 않아요.
      </p>
      <form onSubmit={submit} noValidate>
        <Field
          label="PIN (숫자 4~8자리)"
          required
          type="password"
          inputMode="numeric"
          autoComplete="off"
          value={pin}
          maxLength={8}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setPin(e.target.value.replace(/\D/g, ""))}
        />
        <Field
          label="PIN 확인"
          required
          type="password"
          inputMode="numeric"
          autoComplete="off"
          value={confirmPin}
          maxLength={8}
          onChange={(e: ChangeEvent<HTMLInputElement>) => setConfirmPin(e.target.value.replace(/\D/g, ""))}
        />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
        <Button type="submit" loading={loading}>PIN 저장</Button>
      </form>
    </Card>
  );
}

function SessionCard() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const submit = async () => { setLoading(true); await logout(); navigate("/login", { replace: true }); };
  return <Card className="settings-page__session"><h2>세션</h2><p>현재 브라우저의 로그인 세션을 종료해요.</p><Button type="button" variant="danger" loading={loading} onClick={() => void submit()}>로그아웃</Button></Card>;
}
