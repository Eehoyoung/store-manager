import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { accountApi } from "../api/account";
import { ApiError } from "../api/client";
import type { AccountProfile } from "../api/types";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Field } from "../components/Field";
import { Skeleton } from "../components/Skeleton";
import { useToast } from "../components/Toast";
import { useAuth } from "../auth/AuthContext";

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
        <Field label="휴대폰 번호 (선택)" value={phone} onChange={(e: ChangeEvent<HTMLInputElement>) => setPhone(e.target.value)} autoComplete="tel" />
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

function SessionCard() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const submit = async () => { setLoading(true); await logout(); navigate("/login", { replace: true }); };
  return <Card className="settings-page__session"><h2>세션</h2><p>현재 브라우저의 로그인 세션을 종료합니다.</p><Button type="button" variant="danger" loading={loading} onClick={() => void submit()}>로그아웃</Button></Card>;
}
