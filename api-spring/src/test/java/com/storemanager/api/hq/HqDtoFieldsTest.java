package com.storemanager.api.hq;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H9 회귀 방지 — HqDtos 의 모든 응답 레코드에 구독/청구/입금, 플랫폼 자격증명, author_hash,
 * 가맹점주 개인정보(이메일·전화·사업자번호) 관련 필드가 하나도 없는지 정적으로 단언한다.
 * 순수 리플렉션 테스트라 Spring 컨텍스트·DB 가 필요 없다.
 *
 * <p>★ WP-01(2026-08-28) — hq-data-sharing.md 가 "개별 리뷰 내용·사진·주문 메뉴·작성일,
 * 리뷰 쓴 사람의 표시와 식별값"을 본부에 제공하지 않는다고 명시했다. FR-803 개별 리뷰 조회를
 * 코드에서 제거했지만, 이 테스트는 지우지 않고 그 필드들을 금지 목록에 추가해 강화한다 —
 * 나중에 누군가(또는 다른 에이전트가) 리뷰 조회를 되살리며 실수로 원문 필드를 다시 넣어도
 * 이 테스트가 잡아야 한다.
 */
class HqDtoFieldsTest {

    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
            "subscription", "billing", "payment", "deposit", "price", "amount",
            "loginid", "password", "credential", "encpassword", "encdek",
            "authorhash", "email", "phone", "bizregno",
            // ★ WP-01 추가분 — 개별 리뷰 원문·작성자 표시·주문 메뉴·사진·작성일·답글 본문(hq-data-sharing.md).
            "body", "authormasked", "orderedmenus", "imageurls", "writtenat", "content");

    @Test
    void HqDtos_어떤_레코드에도_금지필드가_없다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> nested : HqDtos.class.getDeclaredClasses()) {
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
        assertThat(violations).as("HqDtos 에 본부 비노출 항목(H9)에 해당하는 필드가 있으면 안 됩니다").isEmpty();
    }

    @Test
    void HqDtos에는_레코드가_실제로_존재한다() {
        // 스캔 대상이 0개라 위 테스트가 거짓양성으로 통과하는 것을 방지한다.
        assertThat(HqDtos.class.getDeclaredClasses()).isNotEmpty();
    }
}
