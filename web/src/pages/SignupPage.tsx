import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
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
  businessNumber: string;
  openingDate: string;
  representativeName: string;
  promoCode: string;
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
  businessNumber: "",
  openingDate: "",
  representativeName: "",
  promoCode: "",
};

declare global {
  interface Window {
    kakao?: {
      Postcode: new (options: { oncomplete: (data: { address: string }) => void }) => { open: () => void };
    };
  }
}

export function SignupPage() {
  const [searchParams] = useSearchParams();
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState<FormState>(() => ({
    ...INITIAL_FORM,
    promoCode: (searchParams.get("promo") ?? "").trim().toUpperCase().slice(0, 32),
  }));
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [docVersion, setDocVersion] = useState("");
  const [agreedTerms, setAgreedTerms] = useState(false);
  const [agreedPrivacy, setAgreedPrivacy] = useState(false);
  const [agreedHq, setAgreedHq] = useState(false);
  const [step, setStep] = useState(0);

  const stepTitles = ["계정 정보", "매장 정보", "약관 동의"];

  useEffect(() => {
    agreementsApi.catalog().then((catalog) => setDocVersion(catalog.currentVersion)).catch(() => setError("동의 문서를 불러오지 못했습니다."));
  }, []);

  const update = (key: keyof FormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!form.name.trim()) errs.name = "이름을 입력해 주세요.";
    if (!form.email.trim()) errs.email = "이메일을 입력해 주세요.";
    if (!form.storeName.trim()) errs.storeName = "매장명을 입력해 주세요.";
    if (!form.storeAddress.trim()) errs.storeAddress = "주소를 검색해 선택해 주세요.";
    if (!/^\d{10}$/.test(form.businessNumber)) errs.businessNumber = "사업자등록번호 숫자 10자리를 입력해 주세요.";
    if (!/^\d{8}$/.test(form.openingDate)) errs.openingDate = "개업일자를 8자리로 입력해 주세요.";
    if (!form.representativeName.trim()) errs.representativeName = "대표자명을 입력해 주세요.";
    if (form.password.length < 8) errs.password = "비밀번호는 8자 이상이어야 합니다.";
    if (form.password !== form.passwordConfirm) errs.passwordConfirm = "비밀번호가 일치하지 않습니다.";
    if (!agreedTerms) errs.agreedTerms = "이용약관 동의가 필요합니다.";
    if (!agreedPrivacy) errs.agreedPrivacy = "개인정보 수집·이용 동의가 필요합니다.";
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const validateStep = (current: number): boolean => {
    const errs: Record<string, string> = {};
    if (current === 0) {
      if (!form.name.trim()) errs.name = "이름을 입력해 주세요.";
      if (!form.email.trim()) errs.email = "이메일을 입력해 주세요.";
      if (form.password.length < 8) errs.password = "비밀번호는 8자 이상이어야 합니다.";
      if (form.password !== form.passwordConfirm) errs.passwordConfirm = "비밀번호가 일치하지 않습니다.";
    } else if (current === 1) {
      if (!form.storeName.trim()) errs.storeName = "매장명을 입력해 주세요.";
      if (!form.storeAddress.trim()) errs.storeAddress = "주소를 검색해 선택해 주세요.";
      if (!/^\d{10}$/.test(form.businessNumber)) errs.businessNumber = "사업자등록번호 숫자 10자리를 입력해 주세요.";
      if (!/^\d{8}$/.test(form.openingDate)) errs.openingDate = "개업일자를 8자리로 입력해 주세요.";
      if (!form.representativeName.trim()) errs.representativeName = "대표자명을 입력해 주세요.";
    } else {
      if (!agreedTerms) errs.agreedTerms = "이용약관 동의가 필요합니다.";
      if (!agreedPrivacy) errs.agreedPrivacy = "개인정보 수집·이용 동의가 필요합니다.";
    }
    const stepFields = current === 0
      ? ["name", "email", "password", "passwordConfirm", "promoCode"]
      : current === 1 ? ["storeName", "storeAddress", "businessNumber", "openingDate", "representativeName", "franchiseCode"] : ["agreedTerms", "agreedPrivacy"];
    setFieldErrors((prev) => {
      const next = { ...prev };
      stepFields.forEach((key) => delete next[key]);
      return { ...next, ...errs };
    });
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!validate()) return;
    setLoading(true);
    try {
      const signupResult = await signup({
        name: form.name,
        email: form.email,
        password: form.password,
        phone: form.phone || undefined,
        franchiseCode: form.franchiseCode || undefined,
        storeName: form.storeName,
        storeAddress: form.storeAddress,
        businessNumber: form.businessNumber,
        openingDate: form.openingDate,
        representativeName: form.representativeName.trim(),
        agreedTerms,
        agreedPrivacy,
        agreedHqDataSharing: form.franchiseCode ? agreedHq : undefined,
        docVersion,
        promoCode: form.promoCode || undefined,
      });
      navigate("/onboarding", { replace: true, state: {
        affiliationRequested: signupResult.affiliationRequested,
        franchiseCodeEntered: Boolean(form.franchiseCode),
        promotionApplied: signupResult.promotionApplied,
      } });
    } catch (err) {
      if (err instanceof ApiError && err.code === "VALIDATION_FAILED" && err.details?.fields) {
        const fields = err.details.fields as Record<string, string>;
        setFieldErrors(fields);
        setStep(Object.keys(fields).some((key) => ["storeName", "storeAddress", "businessNumber", "openingDate", "representativeName", "franchiseCode"].includes(key)) ? 1
          : Object.keys(fields).some((key) => key.startsWith("agreed")) ? 2 : 0);
      } else if (err instanceof ApiError && err.code === "DUPLICATE_RESOURCE") {
        setError("이미 가입된 이메일입니다.");
      } else if (err instanceof ApiError && err.code === "INVALID_FRANCHISE_CODE") {
        setStep(1);
        setFieldErrors((current) => ({ ...current, franchiseCode: "가맹코드를 다시 확인해 주세요." }));
      } else if (err instanceof ApiError && err.code === "VALIDATION_FAILED" && err.details?.reason === "INVALID_PROMOTION_CODE") {
        setStep(0);
        setFieldErrors((current) => ({ ...current, promoCode: "쿠폰번호를 다시 확인해 주세요." }));
      } else if (err instanceof ApiError && err.code === "VALIDATION_FAILED" && err.details?.reason === "PROMOTION_SOLD_OUT") {
        setStep(0);
        setFieldErrors((current) => ({ ...current, promoCode: "이 쿠폰의 무료체험 신청이 마감되었습니다." }));
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
      <Card className="auth-card signup-card">
        <h1 className="auth-card__title">회원가입</h1>
        <ol className="signup-steps" aria-label="회원가입 단계">
          {stepTitles.map((title, index) => (
            <li key={title} aria-current={step === index ? "step" : undefined} className={step === index ? "signup-steps__item signup-steps__item--current" : index < step ? "signup-steps__item signup-steps__item--complete" : "signup-steps__item"}>
              <span aria-hidden="true">{index + 1}</span>{title}
            </li>
          ))}
        </ol>
        <form onSubmit={handleSubmit} noValidate>
          <div className="signup-card__step-title">
            <h2 id={`signup-step-${step}`}>{stepTitles[step]}</h2>
            <p>{step === 0 ? "로그인과 안내에 필요한 정보를 입력해 주세요." : step === 1 ? "리뷰를 연결할 매장 정보를 입력해 주세요." : "서비스 안내를 확인하고 필수 항목에 동의해 주세요."}</p>
          </div>
          {step === 0 ? <div role="tabpanel" aria-labelledby="signup-step-0">
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
            label="쿠폰번호 (선택)"
            hint="안내받은 쿠폰번호를 입력하시면 30일 무료체험이 시작됩니다."
            autoCapitalize="characters"
            value={form.promoCode}
            onChange={(e) => setForm((c) => ({ ...c, promoCode: e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 32) }))}
            error={fieldErrors.promoCode}
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
          </div> : null}
          {step === 1 ? <div role="tabpanel" aria-labelledby="signup-step-1">
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
          <Field
            label="사업자등록번호"
            required
            inputMode="numeric"
            maxLength={10}
            hint="하이픈 없이 숫자 10자리를 입력해 주세요. 국세청 진위확인에 사용합니다."
            value={form.businessNumber}
            onChange={(e) => setForm((current) => ({ ...current, businessNumber: e.target.value.replace(/\D/g, "").slice(0, 10) }))}
            error={fieldErrors.businessNumber}
          />
          <Field
            label="개업일자"
            required
            inputMode="numeric"
            maxLength={8}
            placeholder="예: 20260928"
            hint="사업자등록증의 개업일자를 연월일 8자리로 입력해 주세요."
            value={form.openingDate}
            onChange={(e) => setForm((current) => ({ ...current, openingDate: e.target.value.replace(/\D/g, "").slice(0, 8) }))}
            error={fieldErrors.openingDate}
          />
          <Field
            label="대표자명"
            required
            hint="사업자등록증에 적힌 대표자명과 같아야 합니다."
            value={form.representativeName}
            onChange={update("representativeName")}
            error={fieldErrors.representativeName}
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
          </div> : null}
          {step === 2 ? <div role="tabpanel" aria-labelledby="signup-step-2">
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
          </div> : null}
          {error ? (
            <p className="auth-card__error" role="alert">
              {error}
            </p>
          ) : null}
          <div className="signup-card__actions">
            {step > 0 ? <Button type="button" variant="secondary" onClick={() => setStep(step - 1)}>이전</Button> : null}
            {step < 2 ? <Button type="button" onClick={() => { if (validateStep(step)) setStep(step + 1); }}>다음</Button>
              : <Button type="submit" loading={loading} disabled={!docVersion} className="auth-card__submit">가입하기</Button>}
          </div>
        </form>
        <p className="auth-card__switch">
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
        </p>
      </Card>
    </div>
  );
}
