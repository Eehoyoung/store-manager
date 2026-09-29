package com.storemanager.api.hq;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 개별 리뷰 DTO 금지 필드 스캔 (docs/26a endpoints.hq.reviewForbiddenFields).
 *
 * <p>★ {@link HqDtoFieldsTest} 와는 다른 금지 목록을 쓴다 — 그 테스트는 "본부는 개별 리뷰를 볼 수
 * 없다"던 WP-01 설계를 지키는 것이라 body·writtenAt 같은 이름 자체를 막는다. 지금은 동의·플래그로
 * 통제된 개별 리뷰 조회가 의도된 기능이므로, 여기서는 계약이 실제로 금지한 항목만 막는다:
 * author_hash, 원본 닉네임, platform_review_id, 주문번호, platform_extra, 사진 URL, 자격증명,
 * 가맹점주 개인정보, 결제정보.
 */
class HqReviewDtoFieldsTest {

    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
            "authorhash", "rawnickname", "originalauthor", "nickname",
            "platformreviewid", "ordernumber", "platformextra",
            "photo", "imageurl", "picture",
            "loginid", "password", "credential", "encpassword", "encdek",
            "email", "phone", "bizregno",
            "subscription", "billing", "payment", "deposit", "price", "amount");

    @Test
    void HqReviewDtos_어떤_레코드에도_금지필드가_없다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> nested : HqReviewDtos.class.getDeclaredClasses()) {
            for (Field f : nested.getDeclaredFields()) {
                if (f.isSynthetic()) {
                    continue;
                }
                String lower = f.getName().toLowerCase();
                boolean forbidden = FORBIDDEN_SUBSTRINGS.stream().anyMatch(lower::contains);
                if (forbidden) {
                    violations.add(nested.getSimpleName() + "." + f.getName());
                }
            }
        }
        assertThat(violations).as("HqReviewDtos 에 계약이 금지한 필드가 있으면 안 됩니다").isEmpty();
    }

    @Test
    void HqReviewDtos에는_레코드가_실제로_존재한다() {
        assertThat(HqReviewDtos.class.getDeclaredClasses()).isNotEmpty();
        // ★ 허용된 개별 리뷰 필드(body·writtenAt·authorDisplay)가 실제로 있는지도 확인한다 —
        //   스캔 대상 자체가 잘못돼 있으면 위 테스트가 아무 의미가 없다.
        boolean hasBodyField = false;
        for (Class<?> nested : HqReviewDtos.class.getDeclaredClasses()) {
            for (Field f : nested.getDeclaredFields()) {
                if ("body".equals(f.getName())) {
                    hasBodyField = true;
                }
            }
        }
        assertThat(hasBodyField).as("상세 응답에 body 필드가 있어야 한다(목록에는 excerpt 만)").isTrue();
    }
}
