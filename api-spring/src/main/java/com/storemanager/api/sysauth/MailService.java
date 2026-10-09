package com.storemanager.api.sysauth;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * OTP·담당자 안내 메일 발송.
 *
 * <p>★ 절대규칙 5 와 같은 취급 — 수신 이메일 주소와 인증번호는 어떤 로그에도 남기지 않는다.
 * 실패해도 예외를 던지지 않는다: OTP 요청 응답은 등록 여부·발송 성패와 무관하게 항상
 * 동일해야 하므로({@code docs/26a auth.otp}), 메일 발송 실패가 API 호출자에게 새어 나가면 안 된다.
 */
@Component
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties props;

    public MailService(JavaMailSender mailSender, MailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    public void sendOtp(String toEmail, String code, Duration ttl) {
        if (!canSend()) {
            log.warn("MAIL_REQUIRED=false 이고 메일 계정이 설정되지 않아 OTP 메일 발송을 건너뜁니다.");
            return;
        }
        send(toEmail, "[소담리뷰] 로그인 인증번호",
                "인증번호: " + code + "\n" + (ttl.toSeconds() / 60) + "분 이내에 입력해 주세요.\n"
                        + "본인이 요청하지 않았다면 이 메일을 무시하세요.");
    }

    public void sendHqInvite(String toEmail, String brandName) {
        if (!canSend()) {
            log.warn("MAIL_REQUIRED=false 이고 메일 계정이 설정되지 않아 담당자 안내 메일 발송을 건너뜁니다.");
            return;
        }
        send(toEmail, "[소담리뷰] 가맹본부 담당자로 등록되었습니다",
                brandName + " 브랜드의 가맹본부 담당자로 등록되었습니다.\n"
                        + "로그인 화면에서 이 이메일 주소로 인증번호를 요청해 로그인해 주세요.");
    }

    /**
     * 무료체험 → 유료 전환 사전고지(약관 9.4조 4항). OTP 와 달리 <b>성공 여부를 돌려준다</b> —
     * 호출부는 성공했을 때만 "고지함" 으로 기록하고, 실패하면 다음 날 다시 보낸다.
     */
    public boolean sendTrialConversionNotice(String toEmail, String subject, String body) {
        if (!canSend()) {
            log.warn("메일 계정이 설정되지 않아 유료 전환 사전고지 발송을 건너뜁니다(다음 실행에서 다시 시도).");
            return false;
        }
        return send(toEmail, subject, body);
    }

    /** 가맹 브랜드 구간 단가 변경 안내(V49). 성공 여부를 돌려준다 — 실패하면 다음 실행에서 재시도한다. */
    public boolean sendBrandPriceNotice(String toEmail, String subject, String body) {
        if (!canSend()) {
            log.warn("메일 계정이 설정되지 않아 브랜드 단가 변경 안내 발송을 건너뜁니다(다음 실행에서 다시 시도).");
            return false;
        }
        return send(toEmail, subject, body);
    }

    private boolean canSend() {
        return !props.getUsername().isBlank() && !props.getPassword().isBlank();
    }

    private boolean send(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setFrom(props.resolvedFrom());
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            // ★ 수신자·본문(인증번호 포함)은 로그에 싣지 않는다. 실패해도 호출자에게 전파하지 않는다 —
            //   반송·발송 실패는 운영자가 메일 서비스 콘솔에서 확인한다(docs/26 단계 B 완료 기준).
            log.warn("메일 발송에 실패했습니다", e);
            return false;
        }
    }
}
