import { useEffect, useState, type FormEvent } from "react";
import type { OtpVerifyResponse } from "../api/types";
import { ApiError, NetworkError } from "../api/client";
import { Button } from "./Button";
import { Field } from "./Field";

const RESEND_COOLDOWN_SECONDS = 60;

interface OtpLoginFormProps {
  title: string;
  description: string;
  /** OTP 유효시간(초). 관리자 120초 / 본부 300초(docs/26a auth.otp). */
  ttlSeconds: number;
  onRequest: (email: string) => Promise<{ sent: boolean }>;
  onVerify: (email: string, code: string) => Promise<OtpVerifyResponse>;
  onSuccess: (res: OtpVerifyResponse, email: string) => void;
}

function mmss(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const m = Math.floor(clamped / 60);
  const s = clamped % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

/**
 * 시스템 관리자·가맹본부 공용 2단계 이메일 OTP 로그인 폼.
 *
 * ★ 계정 존재 여부를 노출하지 않는다 — 요청 응답은 항상 동일 문구다(docs/26a auth.otp).
 * ★ 검증 실패는 항상 같은 메시지(OTP_INVALID 한 종류) — 오답인지 만료인지 구분해 알려주지 않는다.
 *   구분해 알려주면 그 자체가 무차별 대입에 힌트가 된다.
 */
export function OtpLoginForm({ title, description, ttlSeconds, onRequest, onVerify, onSuccess }: OtpLoginFormProps) {
  const [step, setStep] = useState<"email" | "code">("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [codeExpiresAt, setCodeExpiresAt] = useState<number | null>(null);
  const [resendAt, setResendAt] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    if (step !== "code") return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [step]);

  const remainingTtl = codeExpiresAt ? Math.round((codeExpiresAt - now) / 1000) : 0;
  const remainingCooldown = resendAt ? Math.round((resendAt - now) / 1000) : 0;
  const codeExpired = codeExpiresAt !== null && remainingTtl <= 0;

  const requestCode = async () => {
    setError(null);
    setLoading(true);
    try {
      await onRequest(email.trim());
      setStep("code");
      setCode("");
      setCodeExpiresAt(Date.now() + ttlSeconds * 1000);
      setResendAt(Date.now() + RESEND_COOLDOWN_SECONDS * 1000);
      setNotice("등록된 이메일이면 인증번호가 발송돼요. 받은 편지함을 확인해 주세요.");
    } catch (e) {
      setError(e instanceof ApiError || e instanceof NetworkError ? e.message : "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
    } finally {
      setLoading(false);
    }
  };

  const submitEmail = async (e: FormEvent) => {
    e.preventDefault();
    if (!email.trim()) return;
    await requestCode();
  };

  const submitCode = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await onVerify(email.trim(), code.trim());
      onSuccess(res, email.trim());
    } catch (err) {
      setError(err instanceof ApiError || err instanceof NetworkError ? err.message : "인증번호가 올바르지 않거나 만료됐어요.");
    } finally {
      setLoading(false);
    }
  };

  if (step === "email") {
    return (
      <>
        <h1 className="auth-card__title">{title}</h1>
        <p className="otp-form__desc">{description}</p>
        <form onSubmit={(e) => void submitEmail(e)} noValidate>
          <Field
            label="이메일"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          {error ? (
            <p className="auth-card__error" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" loading={loading} className="auth-card__submit">
            인증번호 받기
          </Button>
        </form>
      </>
    );
  }

  return (
    <>
      <h1 className="auth-card__title">{title}</h1>
      <p className="otp-form__desc" role="status">
        {notice ?? `${email} 로 인증번호를 보냈어요.`}
      </p>
      <form onSubmit={(e) => void submitCode(e)} noValidate>
        <Field
          label="인증번호 6자리"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          required
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          hint={codeExpired ? "인증번호가 만료됐어요. 다시 받아 주세요." : `남은 시간 ${mmss(remainingTtl)}`}
        />
        {error ? (
          <p className="auth-card__error" role="alert">
            {error}
          </p>
        ) : null}
        <Button type="submit" loading={loading} disabled={codeExpired || code.length !== 6} className="auth-card__submit">
          로그인
        </Button>
      </form>
      <div className="otp-form__resend">
        <Button
          type="button"
          variant="secondary"
          small
          disabled={remainingCooldown > 0 || loading}
          onClick={() => void requestCode()}
        >
          {remainingCooldown > 0 ? `인증번호 다시 받기 (${remainingCooldown}초 후)` : "인증번호 다시 받기"}
        </Button>
        <button
          type="button"
          className="otp-form__back"
          onClick={() => {
            setStep("email");
            setError(null);
            setCode("");
          }}
        >
          이메일 다시 입력
        </button>
      </div>
    </>
  );
}
