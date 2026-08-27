package com.storemanager.api.notify;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 카카오 알림톡(솔라피) 설정.
 *
 * <p><b>★ {@code enabled} 기본값은 false 다.</b> {@code DATAAPI_WRITE_ENABLED} 와 같은 이유 —
 * 알림톡은 되돌릴 수 없고, 실수 한 번이 실고객 카카오톡으로 나간다. 켜는 것은 사람의
 * 명시적 결정이어야 한다.
 *
 * <p><b>★ 켜 놓고 값이 비면 기동을 막는다.</b> "설정은 켰는데 실제로는 아무것도 안 나간다"
 * 가 가장 나쁜 상태다 — 위험 리뷰 알림이 조용히 사라지고 아무도 모른다.
 * {@code MasterKeyProvider.require-kms} 와 같은 fail-closed 원칙이다.
 */
@Component
@ConfigurationProperties(prefix = "app.alimtalk")
public class AlimtalkProperties {

    private boolean enabled;
    private String apiKey = "";
    private String apiSecret = "";
    private String pfId = "";
    private String senderPhone = "";
    private String webhookSecret = "";
    private Template template = new Template();
    private String linkBaseUrl = "http://localhost:5173";

    public static class Template {
        private String riskReview = "";
        private String dailyBriefing = "";

        public String getRiskReview() {
            return riskReview;
        }

        public void setRiskReview(String riskReview) {
            this.riskReview = riskReview;
        }

        public String getDailyBriefing() {
            return dailyBriefing;
        }

        public void setDailyBriefing(String dailyBriefing) {
            this.dailyBriefing = dailyBriefing;
        }
    }

    /**
     * 켜져 있는데 필수값이 비면 기동을 막는다.
     *
     * <p>★ 예외 메시지에 <b>키 값을 넣지 않는다</b>(절대규칙 5). 어떤 항목이 비었는지만 말한다.
     */
    @PostConstruct
    void validate() {
        if (!enabled) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (apiKey.isBlank()) {
            missing.add("SOLAPI_API_KEY");
        }
        if (apiSecret.isBlank()) {
            missing.add("SOLAPI_API_SECRET");
        }
        if (pfId.isBlank()) {
            missing.add("SOLAPI_PF_ID");
        }
        if (template.getRiskReview().isBlank()) {
            missing.add("SOLAPI_TEMPLATE_RISK_REVIEW");
        }
        if (webhookSecret.isBlank()) {
            missing.add("SOLAPI_WEBHOOK_SECRET");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "ALIMTALK_ENABLED=true 인데 다음 값이 비어 있습니다: " + String.join(", ", missing)
                            + ". 알림톡이 조용히 나가지 않는 상태를 막기 위해 기동을 중단합니다.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getPfId() {
        return pfId;
    }

    public void setPfId(String pfId) {
        this.pfId = pfId;
    }

    public String getSenderPhone() {
        return senderPhone;
    }

    public void setSenderPhone(String senderPhone) {
        this.senderPhone = senderPhone;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }

    public String getLinkBaseUrl() {
        return linkBaseUrl;
    }

    public void setLinkBaseUrl(String linkBaseUrl) {
        this.linkBaseUrl = linkBaseUrl;
    }

    /** ★ toString 에 자격증명을 싣지 않는다. 설정 덤프·에러 리포트로 새어 나가는 경로다. */
    @Override
    public String toString() {
        return "AlimtalkProperties{enabled=" + enabled + ", pfId=" + (pfId.isBlank() ? "(비어있음)" : "(설정됨)")
                + ", apiKey=" + (apiKey.isBlank() ? "(비어있음)" : "(설정됨)") + "}";
    }
}
