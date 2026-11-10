package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ★ PHONE_VERIFY_ENABLED 기본 꺼짐이면 문자 발송기 자체가 없다 = 문자 0건.
 * 정확히 "true" 일 때만 뜨고, 켰는데 솔라피 값이 비면 기동을 막는다.
 */
class SolapiSmsSenderConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AlimtalkProperties.class)
            .withUserConfiguration(SolapiSmsSender.class);

    @Test
    void 설정이_없으면_발송기가_없다() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SolapiSmsSender.class));
    }

    @Test
    void false_나_다른_값이면_발송기가_없다() {
        runner.withPropertyValues("app.phone-verify.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SolapiSmsSender.class));
        runner.withPropertyValues("app.phone-verify.enabled=yes")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SolapiSmsSender.class));
    }

    @Test
    void 켰는데_솔라피_값이_비면_기동을_막는다() {
        runner.withPropertyValues("app.phone-verify.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
