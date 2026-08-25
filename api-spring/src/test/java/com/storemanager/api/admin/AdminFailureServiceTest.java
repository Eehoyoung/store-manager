package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 운영자 화면에도 자격증명을 그대로 뿌리지 않는다(절대규칙 5 의 취지). */
class AdminFailureServiceTest {

    @Test
    void 로그인_아이디를_가린다() {
        assertThat(AdminFailureService.maskLoginId("jinsa966")).isEqualTo("jin*****");
        assertThat(AdminFailureService.maskLoginId("abcd")).isEqualTo("abc*");
        assertThat(AdminFailureService.maskLoginId("ab")).isEqualTo("a**");
        assertThat(AdminFailureService.maskLoginId(null)).isNull();
        assertThat(AdminFailureService.maskLoginId("  ")).isNull();
    }

    @Test
    void 원본_아이디가_그대로_남지_않는다() {
        String raw = "owner_account_2026";
        String masked = AdminFailureService.maskLoginId(raw);
        assertThat(masked).isNotEqualTo(raw).doesNotContain("account");
    }
}
