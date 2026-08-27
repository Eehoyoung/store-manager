import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { Field } from "../components/Field";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { AddressField } from "../components/AddressField";
import { formatPhone, normalizeFranchiseCode } from "../lib/format";
import { AuthAside } from "../components/AuthAside";
import { agreementsApi } from "../api/agreements";

interface FormState {
  name: string;
  email: string;
  password: string;
  passwordConfirm: string;
  phone: string;
  franchiseCode: string;
  storeName: string;
  storeAddress: string;
}

const INITIAL_FORM: FormState = {
  name: "",
  email: "",
  password: "",
  passwordConfirm: "",
  phone: "",
  franchiseCode: "",
  storeName: "",
  storeAddress: "",
};

declare global {
  interface Window {
    kakao?: {
      Postcode: new (options: { oncomplete: (data: { address: string }) => void }) => { open: () => void };
    };
  }
}

export function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState<FormState>(INITIAL_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [docVersion, setDocVersion] = useState("");
  const [agreedTerms, setAgreedTerms] = useState(false);
  const [agreedPrivacy, setAgreedPrivacy] = useState(false);
  const [agreedHq, setAgreedHq] = useState(false);

  useEffect(() => {
    agreementsApi.catalog().then((catalog) => setDocVersion(catalog.currentVersion)).catch(() => setError("동의 문서를 불러오지 못했습니다."));
  }, []);

  const update = (key: keyof FormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!form.storeName.trim()) errs.storeName = "매장명을 입력해 주세요.";
    if (!form.storeAddress.trim()) errs.storeAddress = "주소를 검색해 선택해 주세요.";
    if (form.password.length < 8) errs.password = "비밀번호는 8자 이상이어야 합니다.";
    if (form.password !== form.passwordConfirm) errs.passwordConfirm = "비밀번호가 일치하지 않습니다.";
    if (!agreedTerms) errs.agreedTerms = "이용약관 동의가 필요합니다.";
    if (!agreedPrivacy) errs.agreedPrivacy = "개인정보 수집·이용 동의가 필요합니다.";
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!validate()) return;
    setLoading(true);
    try {
      const affiliationRequested = await signup({
        name: form.name,
        email: form.email,
        password: form.password,
        phone: form.phone || undefined,
        franchiseCode: form.franchiseCode || undefined,
        storeName: form.storeName,
        storeAddress: form.storeAddress,
        agreedTerms,
        agreedPrivacy,
        agreedHqDataSharing: form.franchiseCode ? agreedHq : undefined,
        docVersion,
      });
      navigate("/onboarding", { replace: true, state: { affiliationRequested, franchiseCodeEntered: Boolean(form.franchiseCode) } });
    } catch (err) {
      if (err instanceof ApiError && err.code === "VALIDATION_FAILED" && err.details?.fields) {
        setFieldErrors(err.details.fields as Record<string, string>);
      } else if (err instanceof ApiError && err.code === "DUPLICATE_RESOURCE") {
        setError("이미 가입된 이메일입니다.");
      } else if (err instanceof ApiError && err.code === "INVALID_FRANCHISE_CODE") {
        setFieldErrors((current) => ({ ...current, franchiseCode: "가맹코드를 다시 확인해 주세요." }));
      } else {
        setError(err instanceof ApiError ? err.message : "회원가입에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <AuthAside />
      <Card className="auth-card">
        <h1 className="auth-card__title">회원가입</h1>
        <form onSubmit={handleSubmit} noValidate>
          <Field label="이름" required value={form.name} onChange={update("name")} error={fieldErrors.name} />
          <Field
            label="이메일"
            type="email"
            autoComplete="username"
            required
            value={form.email}
            onChange={update("email")}
            error={fieldErrors.email}
          />
          <Field
            label="휴대폰 번호 (선택)"
            type="tel"
            inputMode="numeric"
            autoComplete="tel"
            hint="숫자만 입력하세요. 하이픈은 자동으로 들어갑니다."
            value={form.phone}
            onChange={(e) => setForm((c) => ({ ...c, phone: formatPhone(e.target.value) }))}
            error={fieldErrors.phone}
          />
          <Field
            label="가맹코드 (선택)"
            hint="프랜차이즈 가맹점인 경우 본부에서 받은 코드를 입력해 주세요. 대소문자는 구분하지 않습니다."
            autoCapitalize="characters"
            value={form.franchiseCode}
            onChange={(e) =>
              setForm((c) => ({ ...c, franchiseCode: normalizeFranchiseCode(e.target.value) }))
            }
            error={fieldErrors.franchiseCode}
          />
          {form.franchiseCode ? (
            <section className="consent-box consent-box--hq">
              <h2>가맹코드를 넣으시면 본부가 우리 매장 리뷰를 볼 수 있게 됩니다.</h2>
              <p>본부에서 받으신 가맹코드를 넣으시면, 저희가 확인한 뒤 본부에서 사장님 매장의 리뷰와 운영 현황을 <strong>볼 수 있게</strong> 됩니다.</p>
              <div className="consent-table-wrap"><table><thead><tr><th>본부가 볼 수 있는 것</th><th>본부가 볼 수 없는 것</th></tr></thead><tbody>
                <tr><td>매장 이름과 주소</td><td>사장님 이메일·전화번호</td></tr>
                <tr><td>서비스 이용 중인지 / 멈춰 있는지</td><td>요금·결제·입금 내역</td></tr>
                <tr><td>배달앱 연결 상태와 마지막으로 리뷰를 가져온 시각</td><td>배달앱 아이디와 비밀번호</td></tr>
                <tr><td>리뷰 별점과 리뷰 내용</td><td>리뷰 쓴 손님이 누구인지 알 수 있는 정보</td></tr>
                <tr><td>리뷰 쓴 사람 표시 (`김**`처럼 가려진 형태)</td><td>사장님 사업자 정보</td></tr>
                <tr><td>주문한 메뉴와 리뷰 사진</td><td /></tr>
                <tr><td>답글이 올라갔는지 / 막혔는지</td><td /></tr>
                <tr><td>별점 평균, 자주 나오는 불만, 다른 매장과 비교한 순위</td><td /></tr>
              </tbody></table></div>
              <p><strong>본부는 보기만 할 수 있습니다.</strong> 본부가 사장님 답글을 고치거나, 지우거나, 매장 설정을 바꿀 수는 없습니다. 본부가 언제 무엇을 봤는지는 모두 기록에 남습니다.</p>
              <label className="consent-check"><input type="checkbox" checked={agreedHq} onChange={(e) => setAgreedHq(e.target.checked)} /> (선택) 가맹본부에 위 정보가 제공되는 것에 동의합니다.</label>
              <ul><li>제공받는 곳: 가맹코드를 발급한 가맹본부</li><li>제공 목적: 브랜드 전체 리뷰 현황 파악과 가맹점 지원</li><li>제공 항목: 위 표의 '본부가 볼 수 있는 것'</li><li>제공 기간: 서비스를 이용하시는 동안. 사장님이 요청하시면 언제든 중단합니다</li></ul>
              <p><strong>동의하지 않으셔도 됩니다.</strong> 가맹코드를 넣지 않으셔도 리뷰 수집, 답글 자동 생성, 자동 게시 등 <strong>서비스의 모든 기능을 똑같이</strong> 쓰실 수 있고 요금도 같습니다. 저희가 드리는 불이익은 없습니다.</p>
              <p className="field__hint">다만 본부와 맺으신 가맹계약에 별도로 정해진 내용이 있을 수 있으며, 그 부분은 저희가 관여하지 않습니다. 나중에 마음이 바뀌시면 고객문의로 <strong>소속 해제</strong>를 요청하실 수 있습니다.</p>
              <Link to="/legal/hq-data-sharing" target="_blank">전문 보기</Link>
            </section>
          ) : null}
          <Field
            label="매장명"
            required
            value={form.storeName}
            onChange={update("storeName")}
            error={fieldErrors.storeName}
          />
          <AddressField
            label="매장 주소"
            required
            value={form.storeAddress}
            error={fieldErrors.storeAddress}
            onChange={(next) => {
              setForm((c) => ({ ...c, storeAddress: next }));
              setFieldErrors((c) => ({ ...c, storeAddress: "" }));
            }}
          />
          <Field
            label="비밀번호"
            type="password"
            autoComplete="new-password"
            required
            hint="8자 이상 입력해 주세요."
            value={form.password}
            onChange={update("password")}
            error={fieldErrors.password}
          />
          <Field
            label="비밀번호 확인"
            type="password"
            autoComplete="new-password"
            required
            value={form.passwordConfirm}
            onChange={update("passwordConfirm")}
            error={fieldErrors.passwordConfirm}
          />
          <section className="service-notice">
            <h2>이 서비스가 하는 일을 먼저 확인해 주세요.</h2>
            <ul><li>사장님 매장에 달린 배달앱 리뷰를 자동으로 가져옵니다.</li><li>AI가 답글을 만들어 <strong>사장님 이름으로 배달앱에 자동으로 올립니다.</strong></li><li><strong>한 번 올라간 답글은 저희 서비스에서 지우거나 고칠 수 없습니다.</strong> 수정은 배달앱 사장님 화면에서 직접 하셔야 합니다.</li><li>위생·이물질·법적 다툼처럼 민감한 리뷰에는 답글을 <strong>자동으로 올리지 않습니다.</strong></li></ul>
          </section>
          <section className="consent-box">
            <label className="consent-check consent-check--all"><input type="checkbox" checked={agreedTerms && agreedPrivacy} onChange={(e) => { setAgreedTerms(e.target.checked); setAgreedPrivacy(e.target.checked); }} /> 전체 동의합니다</label>
            <label className="consent-check"><input type="checkbox" checked={agreedTerms} onChange={(e) => setAgreedTerms(e.target.checked)} /> (필수) 이용약관에 동의합니다 <Link to="/legal/terms" target="_blank">전문 보기</Link></label>
            {fieldErrors.agreedTerms ? <p className="field__error">{fieldErrors.agreedTerms}</p> : null}
            <label className="consent-check"><input type="checkbox" checked={agreedPrivacy} onChange={(e) => setAgreedPrivacy(e.target.checked)} /> (필수) 개인정보 수집·이용에 동의합니다 <Link to="/legal/privacy" target="_blank">전문 보기</Link></label>
            {fieldErrors.agreedPrivacy ? <p className="field__error">{fieldErrors.agreedPrivacy}</p> : null}
            <div className="consent-table-wrap"><table><thead><tr><th>수집 항목</th><th>이용 목적</th><th>보유 기간</th></tr></thead><tbody><tr><td>이름, 이메일, 비밀번호</td><td>회원 가입·본인 확인·로그인</td><td>이용계약 종료 시까지</td></tr><tr><td>전화번호 (선택)</td><td>중요 안내 연락</td><td>이용계약 종료 시까지</td></tr><tr><td>매장명, 매장 주소</td><td>매장 등록·리뷰 관리</td><td>이용계약 종료 시까지</td></tr></tbody></table></div>
            <p>동의를 거부하실 수 있으나, 필수 항목에 동의하지 않으시면 서비스에 가입하실 수 없습니다. 전화번호는 선택 항목이며, 입력하지 않으셔도 가입하실 수 있습니다.</p>
          </section>
          {error ? (
            <p className="auth-card__error" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" loading={loading} disabled={!docVersion} className="auth-card__submit">
            가입하기
          </Button>
        </form>
        <p className="auth-card__switch">
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
        </p>
      </Card>
    </div>
  );
}
