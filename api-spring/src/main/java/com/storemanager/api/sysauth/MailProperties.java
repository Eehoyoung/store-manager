package com.storemanager.api.sysauth;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gmail SMTP + 앱 비밀번호 발송 설정 (docs/26a decisions.mail).
 *
 * <p><b>★ {@code required=true} 인데 계정이 비면 기동을 막는다.</b>
 * {@code BusinessInfoProperties.require-complete}·{@code AlimtalkProperties.enabled} 와 같은
 * fail-closed 원칙 — "설정은 켰는데 실제로는 안 나간다" 가 가장 나쁜 상태다.
 *
 * <p>{@code required=false}(기본값, 로컬·테스트)이고 계정이 비어 있으면 {@link MailService} 가
 * 발송을 건너뛰고 WARN 로그 한 줄만 남긴다 — 수신 이메일·OTP 값은 어떤 경우에도 로그에 싣지 않는다.
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    private String host = "smtp.gmail.com";
    private int port = 587;
    private String username = "";
    private String password = "";
    private String from = "";
    private boolean required = false;

    @PostConstruct
    void validate() {
        if (!required) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (username.isBlank()) {
            missing.add("MAIL_USERNAME");
        }
        if (password.isBlank()) {
            missing.add("MAIL_PASSWORD");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "MAIL_REQUIRED=true 인데 다음 값이 비어 있습니다: " + String.join(", ", missing)
                            + ". 이메일 OTP 가 조용히 발송되지 않는 상태를 막기 위해 기동을 중단합니다.");
        }
    }

    /** 발신 주소 — 비어 있으면 로그인 계정을 그대로 쓴다. */
    public String resolvedFrom() {
        return from.isBlank() ? username : from;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    /** ★ toString 에 자격증명을 싣지 않는다(AlimtalkProperties 와 같은 관례). */
    @Override
    public String toString() {
        return "MailProperties{host=" + host + ", port=" + port
                + ", username=" + (username.isBlank() ? "(비어있음)" : "(설정됨)") + "}";
    }
}
