package com.storemanager.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * PersonalIdentifierMasker 순수함수 단위테스트(WP-04).
 * ★ 식별자 오탐(false negative)뿐 아니라, 일반 숫자 표현을 지우는 오탐(false positive) 회귀도 함께 잠근다.
 */
class PersonalIdentifierMaskerTest {

    @Test
    void 휴대폰번호를_마스킹한다() {
        assertThat(PersonalIdentifierMasker.mask("연락주세요 010-1234-5678 로")).isEqualTo("연락주세요 [전화번호] 로");
        assertThat(PersonalIdentifierMasker.mask("01012345678 로 연락주세요")).isEqualTo("[전화번호] 로 연락주세요");
    }

    @Test
    void 일반_전화번호를_마스킹한다() {
        assertThat(PersonalIdentifierMasker.mask("가게 전화번호는 02-1234-5678 입니다")).isEqualTo("가게 전화번호는 [전화번호] 입니다");
        assertThat(PersonalIdentifierMasker.mask("문의는 1588-1234 로")).isEqualTo("문의는 [전화번호] 로");
    }

    @Test
    void 이메일을_마스킹한다() {
        assertThat(PersonalIdentifierMasker.mask("메일은 owner@example.com 입니다"))
                .isEqualTo("메일은 [이메일] 입니다");
    }

    @Test
    void 계좌번호를_마스킹한다() {
        assertThat(PersonalIdentifierMasker.mask("환불은 110-234-567890 으로 부탁드려요"))
                .isEqualTo("환불은 [계좌번호] 으로 부탁드려요");
    }

    @Test
    void 주민등록번호를_마스킹한다() {
        assertThat(PersonalIdentifierMasker.mask("주민번호 901231-1234567 입니다"))
                .isEqualTo("주민번호 [주민등록번호] 입니다");
    }

    @Test
    void 카드번호를_마스킹한다() {
        assertThat(PersonalIdentifierMasker.mask("카드번호 1234-5678-9012-3456 결제했어요"))
                .isEqualTo("카드번호 [카드번호] 결제했어요");
    }

    @Test
    void 식별자가_없는_문장은_한글자도_바뀌지_않는다() {
        String text = "너무 짜서 먹기 힘들었습니다";
        assertThat(PersonalIdentifierMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void 숫자가_섞인_일반_표현은_마스킹하지_않는다() {
        for (String text : Arrays.asList(
                "별점 5점 드립니다",
                "30분 지연됐어요",
                "2인분 시켰는데 양이 적었어요",
                "1인 8900원인데 비싸요",
                "2026-08-28 에 주문했어요")) {
            assertThat(PersonalIdentifierMasker.mask(text)).as(text).isEqualTo(text);
        }
    }

    @Test
    void null과_빈문자열은_예외없이_그대로_반환한다() {
        assertThat(PersonalIdentifierMasker.mask(null)).isNull();
        assertThat(PersonalIdentifierMasker.mask("")).isEmpty();
        assertThat(PersonalIdentifierMasker.maskAll(null)).isNull();
    }

    @Test
    void 리스트_전체에_마스킹을_적용한다() {
        assertThat(PersonalIdentifierMasker.maskAll(Arrays.asList("김치찌개", "010-1234-5678 로 연락줘요")))
                .containsExactly("김치찌개", "[전화번호] 로 연락줘요");
    }
}
