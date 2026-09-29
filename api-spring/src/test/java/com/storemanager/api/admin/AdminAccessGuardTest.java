package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminAccessGuardTest {

    @Test
    void 허용목록에_없는_이메일은_관리자가_아니다() {
        var guard = new AdminAccessGuard("boss@sodam.test");

        assertThat(guard.isAdminEmail("other@example.com")).isFalse();
    }

    @Test
    void 허용목록에_있으면_대소문자와_무관하게_관리자다() {
        var guard = new AdminAccessGuard("boss@sodam.test, second@sodam.test");

        assertThat(guard.isAdminEmail("BOSS@sodam.test")).isTrue();
        assertThat(guard.isAdminEmail(" second@sodam.test ")).isTrue();
    }

    @Test
    void 값이_비면_아무도_관리자가_아니다() {
        var guard = new AdminAccessGuard("");

        assertThat(guard.isAdminEmail("anyone@example.com")).isFalse();
    }
}
