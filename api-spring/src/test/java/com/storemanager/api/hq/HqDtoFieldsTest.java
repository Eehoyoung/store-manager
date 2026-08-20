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
 */
class HqDtoFieldsTest {

    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
            "subscription", "billing", "payment", "deposit", "price", "amount",
            "loginid", "password", "credential", "encpassword", "encdek",
            "authorhash", "email", "phone", "bizregno");

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
