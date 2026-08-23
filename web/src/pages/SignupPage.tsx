import { useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { Field } from "../components/Field";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { AddressField } from "../components/AddressField";
import { formatPhone, normalizeFranchiseCode } from "../lib/format";
import { AuthAside } from "../components/AuthAside";

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

  const update = (key: keyof FormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!form.storeName.trim()) errs.storeName = "매장명을 입력해 주세요.";
    if (!form.storeAddress.trim()) errs.storeAddress = "주소를 검색해 선택해 주세요.";
    if (form.password.length < 8) errs.password = "비밀번호는 8자 이상이어야 합니다.";
    if (form.password !== form.passwordConfirm) errs.passwordConfirm = "비밀번호가 일치하지 않습니다.";
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!validate()) return;
    setLoading(true);
    try {
      await signup({
        name: form.name,
        email: form.email,
        password: form.password,
        phone: form.phone || undefined,
        franchiseCode: form.franchiseCode || undefined,
        storeName: form.storeName,
        storeAddress: form.storeAddress,
      });
      navigate("/onboarding", { replace: true });
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
          {error ? (
            <p className="auth-card__error" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" loading={loading} className="auth-card__submit">
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
