import { useNavigate } from "react-router-dom";
import { hqAuthApi } from "../../api/hq";
import { Card } from "../../components/Card";
import { OtpLoginForm } from "../../components/OtpLoginForm";
import { setAccessToken } from "../../auth/tokenStore";
import { setHqIdentity } from "../../auth/HqSession";

// docs/26a auth.otp — 본부 OTP 는 300초, 세션은 8시간(자동연장 없음).
const HQ_OTP_TTL_SECONDS = 300;

export function HqLoginPage() {
  const navigate = useNavigate();

  return (
    <div className="auth-page">
      <Card className="auth-card">
        <OtpLoginForm
          title="가맹본부 로그인"
          description="본부 담당자로 등록된 이메일로 인증번호를 보내드려요."
          ttlSeconds={HQ_OTP_TTL_SECONDS}
          onRequest={(email) => hqAuthApi.request(email)}
          onVerify={(email, code) => hqAuthApi.verify(email, code)}
          onSuccess={(res) => {
            setAccessToken(res.accessToken, "HQ");
            if (res.user) setHqIdentity(res.user);
            navigate("/hq/brands", { replace: true });
          }}
        />
      </Card>
    </div>
  );
}
