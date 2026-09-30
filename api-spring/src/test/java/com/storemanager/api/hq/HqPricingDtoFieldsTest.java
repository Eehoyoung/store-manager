package com.storemanager.api.hq;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.billing.PricingDtos.HqBrandPricingResponse;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 본부 브랜드 단가 조회(V49)가 매장별 결제 상태·약정 매장 수(committedStoreCount, 관리자 전용)를
 * 흘리지 않는지 정적으로 단언한다({@code HqReviewDtoFieldsTest} 와 같은 리플렉션 방식).
 *
 * <p>★ {@code HqBrandPricingResponse} 는 의도적으로 {@code HqDtos.java} 밖(billing 패키지)에 둔다 —
 * {@code HqDtoFieldsTest} 가 "price"·"billing"·"amount" 를 HqDtos 전체 금지어로 두고 있어서
 * (본부는 매장별 결제 상세를 못 본다는 H9 규칙), 브랜드 집계용 가격 필드까지 그 규칙에 걸린다.
 * 이 테스트가 그 대신 "약정 매장 수·매장별 상태가 없는가" 를 확인한다.
 */
class HqPricingDtoFieldsTest {

    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
            "storeid", "subscriptionstatus", "committedstorecount", "status",
            "loginid", "password", "credential", "email", "phone", "bizregno");

    @Test
    void HqBrandPricingResponse에는_매장별_상태와_약정_매장수가_없다() {
        List<String> fields = List.of(HqBrandPricingResponse.class.getDeclaredFields()).stream()
                .filter(f -> !f.isSynthetic()).map(Field::getName).toList();

        assertThat(fields).containsExactlyInAnyOrder(
                "brandName", "paidStoreCount", "lastMonth", "thisMonth", "nextMonth");

        List<String> violations = fields.stream().map(String::toLowerCase)
                .filter(name -> FORBIDDEN_SUBSTRINGS.stream().anyMatch(name::contains)).toList();
        assertThat(violations).isEmpty();
    }
}
