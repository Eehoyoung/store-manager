package com.storemanager.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 운영 콘솔 collector 용 무인증 metrics 는 분리된 관리 포트로 들어온 요청에만 열린다.
 * 앱 포트(8080)나 관리 포트 미설정(-1)에서는 인증 규칙이 그대로 적용돼야 한다.
 */
class ManagementMetricsAccessTest {

    private static MockHttpServletRequest req(int localPort, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setLocalPort(localPort);
        return r;
    }

    @Test
    void 관리_포트의_metrics만_허용한다() {
        RequestMatcher m = SecurityConfig.managementMetrics(8081);
        assertThat(m.matches(req(8081, "/actuator/metrics"))).isTrue();
        assertThat(m.matches(req(8081, "/actuator/metrics/http.server.requests"))).isTrue();
        assertThat(m.matches(req(8080, "/actuator/metrics"))).isFalse();
        assertThat(m.matches(req(8081, "/actuator/env"))).isFalse();
        assertThat(m.matches(req(8081, "/actuator/metricsX"))).isFalse();
        assertThat(m.matches(req(8081, "/api/v1/stores"))).isFalse();
    }

    @Test
    void 관리_포트가_없으면_아무것도_허용하지_않는다() {
        assertThat(SecurityConfig.managementMetrics(-1).matches(req(-1, "/actuator/metrics"))).isFalse();
    }
}
