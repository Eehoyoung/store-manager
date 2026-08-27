package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 알림톡 설정 fail-closed 검증.
 *
 * <p>★ "설정은 켰는데 실제로는 아무것도 안 나간다" 가 가장 나쁜 상태다. 위험 리뷰 알림이
 * 조용히 사라지고 아무도 모른다. 기동을 막는 편이 낫다.
 */
class AlimtalkPropertiesTest {

    private AlimtalkProperties props(boolean enabled, String key, String secret, String pf,
            String tplRisk, String tplBrief) {
        AlimtalkProperties p = new AlimtalkProperties();
        p.setEnabled(enabled);
        p.setApiKey(key);
        p.setApiSecret(secret);
        p.setPfId(pf);
        p.setWebhookSecret("webhook-test-secret");
        AlimtalkProperties.Template t = new AlimtalkProperties.Template();
        t.setRiskReview(tplRisk);
        t.setDailyBriefing(tplBrief);
        p.setTemplate(t);
        return p;
    }

    @Test
    void 꺼져_있으면_값이_비어도_기동한다() {
        assertThatCode(() -> props(false, "", "", "", "", "").validate()).doesNotThrowAnyException();
    }

    @Test
    void 켜져_있는데_값이_비면_비어_있는_항목을_말하며_기동을_막는다() {
        AlimtalkProperties p = props(true, "", "", "", "", "");
        p.setWebhookSecret("");
        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLAPI_API_KEY")
                .hasMessageContaining("SOLAPI_TEMPLATE_RISK_REVIEW")
                .hasMessageContaining("SOLAPI_WEBHOOK_SECRET");
    }

    @Test
    void 일차_범위가_아닌_브리핑_템플릿은_비어도_기동한다() {
        assertThatCode(() -> props(true, "k", "s", "pf", "tpl-risk", "").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void 값이_다_있으면_기동한다() {
        assertThatCode(() -> props(true, "k", "s", "pf", "tpl-risk", "tpl-brief").validate())
                .doesNotThrowAnyException();
    }

    /** ★ 설정 덤프·에러 리포트로 자격증명이 새어 나가는 경로를 막는다(절대규칙 5). */
    @Test
    void toString에_자격증명이_담기지_않는다() {
        AlimtalkProperties p = props(true, "NCSAPIKEY123", "SECRET456", "pf-1", "t1", "t2");
        p.setSenderPhone("01012345678");

        assertThat(p.toString())
                .doesNotContain("NCSAPIKEY123")
                .doesNotContain("SECRET456")
                .contains("(설정됨)");
    }
}
