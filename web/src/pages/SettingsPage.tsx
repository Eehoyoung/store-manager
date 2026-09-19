import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { accountApi } from "../api/account";
import { ApiError } from "../api/client";
import type { AccountProfile } from "../api/types";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { formatPhone } from "../lib/format";
import { Field } from "../components/Field";
import { Skeleton } from "../components/Skeleton";
import { useToast } from "../components/Toast";
import { useAuth } from "../auth/AuthContext";
import { agreementsApi, type AgreementHistoryRow } from "../api/agreements";
import { naverExtensionApi } from "../api/naverExtension";

export function SettingsPage() {
  const [profile, setProfile] = useState<AccountProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => accountApi.get().then(setProfile).catch((e) => setError(e instanceof ApiError ? e.message : "계정 정보를 불러오지 못했습니다."));
  useEffect(() => { void load(); }, []);

  if (!profile && !error) return <div className="settings-page"><Skeleton height={180} /><Skeleton height={220} /></div>;
  if (error || !profile) return <div className="settings-page"><p className="auth-card__error" role="alert">{error ?? "계정 정보를 불러오지 못했습니다."}</p><Button type="button" onClick={() => { setError(null); void load(); }}>다시 시도</Button></div>;

  return (
    <div className="settings-page">
      <h1>설정</h1>
      <p className="settings-page__intro">계정 정보와 보안 설정을 관리합니다.</p>
      <ProfileCard profile={profile} onUpdated={setProfile} />
      <PasswordCard />
      <NaverPairingCard />
      <NaverPinCard />
      <AgreementHistoryCard />
      <Card className="settings-page__security-note">
        <h2>서비스 보안</h2>
        <p>배달앱 비밀번호는 별도 봉투암호화로 저장되며 이 화면에 표시하지 않습니다.</p>
        <p>DataAPI 토큰과 LOGINPWD 공식 규격 확인 전에는 외부 계정 검증을 수행하지 않습니다.</p>
      </Card>
      <Card>
        <h2>빠른 이동</h2>
        <div className="settings-page__links">
          <Link to="/platform-accounts" className="btn btn--secondary">배달앱 계정 연동</Link>
          <Link to="/stores" className="btn btn--secondary">매장 관리</Link>
        </div>
      </Card>
      <SessionCard />
    </div>
  );
}

function AgreementHistoryCard() {
  const [rows, setRows] = useState<AgreementHistoryRow[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  useEffect(() => { agreementsApi.history().then(setRows).catch(() => setMessage("동의 내역을 불러오지 못했습니다.")); }, []);
  const withdraw = async () => {
    await agreementsApi.requestHqWithdrawal();
    setMessage("가맹본부 소속 해제 요청이 접수되었습니다. 실제 해제는 운영자가 처리합니다.");
    setRows(await agreementsApi.history());
  };
  return <Card className="settings-page__card"><h2>동의 내역</h2>
    {message ? <p role="status">{message}</p> : null}
    <ul>{rows.map((row, index) => <li key={`${row.code}-${row.agreedAt}-${index}`}><strong>{row.code}</strong> · {row.agreed ? "동의" : "철회/거부"} · {new Date(row.agreedAt).toLocaleString("ko-KR")} · 문서 {row.docVersion} · <Link to={row.documentUrl}>전문 보기</Link></li>)}</ul>
    <p>필수 동의는 이 화면에서 철회할 수 없으며 회원 탈퇴로만 철회할 수 있습니다.</p>
    <Button type="button" variant="secondary" onClick={() => void withdraw()}>가맹본부 소속 해제 요청</Button>
  </Card>;
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
      toast.show("계정 정보를 저장했습니다.", "success");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "계정 정보 저장에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  };
  return (
    <Card className="settings-page__card">
      <div className="settings-page__card-head"><h2>계정 정보</h2><Badge tone={profile.status === "ACTIVE" ? "success" : "warning"} icon={profile.status === "ACTIVE" ? "✓" : "•"}>{profile.status === "ACTIVE" ? "사용 중" : profile.status}</Badge></div>
      <form onSubmit={submit} noValidate>
        <Field label="이메일" type="email" value={profile.email} readOnly hint="로그인 이메일은 설정 화면에서 변경할 수 없습니다." />
        <Field label="이름" required value={name} onChange={(e: ChangeEvent<HTMLInputElement>) => setName(e.target.value)} />
        <Field label="휴대폰 번호 (선택)" value={phone} type="tel" inputMode="numeric" autoComplete="tel"
               hint="숫자만 입력하세요. 하이픈은 자동으로 들어갑니다."
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
    if (newPassword.length < 8) return setError("새 비밀번호는 8자 이상이어야 합니다.");
    if (newPassword !== confirm) return setError("새 비밀번호가 일치하지 않습니다.");
    setLoading(true);
    try {
      await accountApi.changePassword({ currentPassword, newPassword });
      setCurrentPassword(""); setNewPassword(""); setConfirm("");
      toast.show("비밀번호를 변경했습니다.", "success");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "비밀번호 변경에 실패했습니다.");
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
      setError(e instanceof ApiError ? e.message : "페어링 코드 발급에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  };

  const copy = async () => {
    if (!pairing) return;
    try {
      await navigator.clipboard.writeText(pairing.code);
      setCopyMessage("코드를 복사했습니다.");
    } catch {
      setCopyMessage("복사에 실패했습니다. 코드를 직접 선택해 복사해 주세요.");
    }
  };

  const expired = pairing !== null && remaining <= 0;

  return (
    <Card className="settings-page__card">
      <h2>네이버 확장 연동</h2>
      <p className="settings-page__naver-honesty">
        네이버 리뷰는 자동으로 게시되지 않습니다. 확장이 답글을 입력창에 채워 드리면 등록 버튼은 사장님이 직접 누르셔야 합니다.
      </p>
      <ol className="settings-page__naver-steps">
        <li>크롬에 확장 프로그램을 설치합니다.</li>
        <li>확장의 사이드패널을 엽니다.</li>
        <li>아래 코드를 사이드패널에 입력합니다.</li>
      </ol>
      {!pairing || expired ? (
        <>
          {expired ? <p role="status">코드가 만료되었습니다. 다시 발급해 주세요.</p> : null}
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
    if (pin !== confirmPin) return setError("PIN 이 일치하지 않습니다.");
    setLoading(true);
    try {
      await naverExtensionApi.setPin(pin);
      setPin("");
      setConfirmPin("");
      toast.show("PIN 설정을 완료했습니다.", "success");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "PIN 설정에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="settings-page__card">
      <h2>일괄 승인 PIN</h2>
      <p>
        PIN 을 설정해야 일괄 승인을 쓸 수 있습니다. 공용 포스 PC 에서 직원이 대신 승인하는 것을
        막기 위한 장치입니다. 미설정 상태에서는 일괄 승인이 동작하지 않습니다.
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
  return <Card className="settings-page__session"><h2>세션</h2><p>현재 브라우저의 로그인 세션을 종료합니다.</p><Button type="button" variant="danger" loading={loading} onClick={() => void submit()}>로그아웃</Button></Card>;
}
