package com.storemanager.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 확장 오리진 CORS 회귀 테스트.
 *
 * <p>★ 실기동(2026-09-20)에서 페어링이 통째로 막혀 있었다. 크롬 확장은
 * host_permissions 로 <b>브라우저 쪽</b> CORS 검사를 면제받지만, 요청에는 여전히
 * {@code Origin: chrome-extension://<id>} 가 붙는다. 서버는 host_permissions 를 알 수
 * 없으므로 Spring CorsFilter 가 "Invalid CORS request" 로 403 을 냈다.
 * 화면에는 "코드가 올바르지 않습니다" 로 보여 원인을 찾기까지 여러 번 왕복했다.
 */
class NaverExtensionCorsTest {

    private static final String EXT_ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
    private CorsConfigurationSource source;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig(null, new ObjectMapper());
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:15173");
        ReflectionTestUtils.setField(config, "allowedExtensionOrigins", "chrome-extension://*");
        source = config.corsConfigurationSource();
    }

    private CorsConfiguration configFor(String path, String origin) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.addHeader("Origin", origin);
        return source.getCorsConfiguration(req);
    }

    @Test
    @DisplayName("확장 오리진이 네이버 경로에서 허용된다 — 이게 막혀 페어링이 안 됐다")
    void 확장_오리진은_네이버_경로에서_허용된다() {
        CorsConfiguration cfg = configFor("/api/v1/naver/extension/pair", EXT_ORIGIN);
        assertThat(cfg).isNotNull();
        assertThat(cfg.checkOrigin(EXT_ORIGIN)).isEqualTo(EXT_ORIGIN);
    }

    @Test
    @DisplayName("웹·확장이 함께 부르는 /naver/status 도 양쪽 다 허용된다")
    void 상태조회는_웹과_확장_둘_다_허용된다() {
        CorsConfiguration cfg = configFor("/api/v1/naver/status", EXT_ORIGIN);
        assertThat(cfg.checkOrigin(EXT_ORIGIN)).isEqualTo(EXT_ORIGIN);
        assertThat(cfg.checkOrigin("http://localhost:15173")).isEqualTo("http://localhost:15173");
    }

    @Test
    @DisplayName("★ 확장 오리진을 네이버 밖으로 넓히지 않는다 — 나머지 API 는 웹 화이트리스트 그대로다")
    void 확장_오리진은_다른_API_로_넓어지지_않는다() {
        CorsConfiguration cfg = configFor("/api/v1/stores", EXT_ORIGIN);
        assertThat(cfg).isNotNull();
        assertThat(cfg.checkOrigin(EXT_ORIGIN)).isNull();
        assertThat(cfg.checkOrigin("http://localhost:15173")).isEqualTo("http://localhost:15173");
    }

    @Test
    @DisplayName("★ 임의 웹사이트는 네이버 경로에서도 거절된다 — 넓힌 것은 확장뿐이다")
    void 임의_사이트는_네이버_경로에서도_거절된다() {
        CorsConfiguration cfg = configFor("/api/v1/naver/drafts", "https://evil.example.com");
        assertThat(cfg.checkOrigin("https://evil.example.com")).isNull();
    }

    @Test
    @DisplayName("운영처럼 고정 ID 로 좁히면 다른 확장은 거절된다")
    void 고정_ID_로_좁힐_수_있다() {
        SecurityConfig config = new SecurityConfig(null, new ObjectMapper());
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://review.sodamlabs.kr");
        ReflectionTestUtils.setField(config, "allowedExtensionOrigins", EXT_ORIGIN);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/naver/drafts");
        req.addHeader("Origin", EXT_ORIGIN);
        CorsConfiguration cfg = config.corsConfigurationSource().getCorsConfiguration(req);

        assertThat(cfg.checkOrigin(EXT_ORIGIN)).isEqualTo(EXT_ORIGIN);
        assertThat(cfg.checkOrigin("https://review.sodamlabs.kr")).isEqualTo("https://review.sodamlabs.kr");
        assertThat(cfg.checkOrigin("chrome-extension://zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")).isNull();
    }
}
