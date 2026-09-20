package com.storemanager.api.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BusinessInfoPropertiesTest {

    private static BusinessInfoProperties filled() {
        var p = new BusinessInfoProperties();
        p.setRepresentative("홍길동");
        p.setAddress("서울시 어딘가");
        p.setPhone("02-000-0000");
        p.setEmail("help@example.com");
        p.setRegistrationNumber("000-00-00000");
        p.setMailOrderNumber("제2026-서울-0000호");
        p.setPrivacyOfficer("홍길동");
        return p;
    }

    @Test
    void 개발에서는_비어_있어도_기동한다() {
        var p = new BusinessInfoProperties();
        p.validate();
        assertThat(p.missing()).contains("BUSINESS_REGISTRATION_NUMBER");
    }

    /**
     * ★ 이 테스트가 출시 게이트 자체다. 표시 의무가 있는 값을 비운 채 운영으로 뜨는 것을 막는다.
     * 화면에 빈칸이 나가는 것보다 서비스가 안 뜨는 편이 낫다 — 빈칸은 아무도 눈치채지 못한다.
     */
    @Test
    void 운영에서_사업자정보가_비면_기동을_막는다() {
        var p = filled();
        p.setRegistrationNumber("");
        p.setRequireComplete(true);
        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_REGISTRATION_NUMBER");
    }

    @Test
    void 전부_채우면_운영에서도_기동한다() {
        var p = filled();
        p.setRequireComplete(true);
        p.validate();
        assertThat(p.missing()).isEmpty();
    }
}
