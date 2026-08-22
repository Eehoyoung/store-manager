import { useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { Field } from "../components/Field";
import { Button } from "../components/Button";
import { Card } from "../components/Card";

interface FormState {
  name: string;
  email: string;
  password: string;
  passwordConfirm: string;
  phone: string;
  storeName: string;
  storeAddress: string;
}

const INITIAL_FORM: FormState = {
  name: "",
  email: "",
  password: "",
  passwordConfirm: "",
  phone: "",
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
        storeName: form.storeName,
        storeAddress: form.storeAddress,
      });
      navigate("/onboarding", { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.code === "VALIDATION_FAILED" && err.details?.fields) {
        setFieldErrors(err.details.fields as Record<string, string>);
      } else if (err instanceof ApiError && err.code === "DUPLICATE_RESOURCE") {
        setError("이미 가입된 이메일입니다.");
      } else {
        setError(err instanceof ApiError ? err.message : "회원가입에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setLoading(false);
    }
  };

  const searchAddress = () => {
    if (!window.kakao?.Postcode) {
      setError("주소 검색 서비스를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      return;
    }
    new window.kakao.Postcode({
      oncomplete: ({ address }) => {
        setForm((current) => ({ ...current, storeAddress: address }));
        setFieldErrors((current) => ({ ...current, storeAddress: "" }));
      },
    }).open();
  };

  return (
    <div className="auth-page">
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
            value={form.phone}
            onChange={update("phone")}
            error={fieldErrors.phone}
          />
          <Field
            label="매장명"
            required
            value={form.storeName}
            onChange={update("storeName")}
            error={fieldErrors.storeName}
          />
          <div className="auth-card__address">
            <Field
              label="매장 주소"
              required
              readOnly
              value={form.storeAddress}
              error={fieldErrors.storeAddress}
              placeholder="주소 검색 버튼을 눌러 주세요."
            />
            <Button type="button" variant="secondary" onClick={searchAddress}>
              주소 검색
            </Button>
          </div>
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
