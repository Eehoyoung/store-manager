import { useNavigate } from "react-router-dom";
import { adminAuthApi } from "../../api/admin";
import { Card } from "../../components/Card";
import { OtpLoginForm } from "../../components/OtpLoginForm";
import { setAccessToken } from "../../auth/tokenStore";
import { setAdminEmail } from "../../auth/AdminSession";

// docs/26a auth.otp — 관리자 OTP 는 120초, 세션은 10분(자동연장 없음).
const ADMIN_OTP_TTL_SECONDS = 120;

export function AdminLoginPage() {
  const navigate = useNavigate();

  return (
    <div className="auth-page">
      <Card className="auth-card">
        <OtpLoginForm
          title="시스템 콘솔 로그인"
          description="등록된 관리자 이메일로 인증번호를 보내드려요."
          ttlSeconds={ADMIN_OTP_TTL_SECONDS}
          onRequest={(email) => adminAuthApi.request(email)}
          onVerify={(email, code) => adminAuthApi.verify(email, code)}
          onSuccess={(res, email) => {
            setAccessToken(res.accessToken, "ADMIN");
            setAdminEmail(email);
            navigate("/admin/franchises", { replace: true });
          }}
        />
      </Card>
    </div>
  );
}
