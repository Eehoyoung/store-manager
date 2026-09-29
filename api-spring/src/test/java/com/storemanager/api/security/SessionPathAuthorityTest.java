package com.storemanager.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 세션 종류별 경로 분리(docs/26a auth.authorities)를 잠근다.
 *
 * <p>★ 한쪽만 막으면 반대 방향이 뚫린다. 2026-09-29 첫 구현은 {@code anyRequest().authenticated()} 라
 * 본부 OTP 토큰(principal = 사장님과 같은 public UUID)으로 사장님 API 를 호출할 수 있었다.
 */
class SessionPathAuthorityTest {

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/storemanager/api/security/SecurityConfig.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void 관리자_본부_경로는_각자의_세션만_허용한다() throws Exception {
        String s = source();
        assertThat(s).contains(".requestMatchers(\"/api/v1/admin/**\").hasAuthority(\"SESSION_ADMIN\")");
        assertThat(s).contains(".requestMatchers(\"/api/v1/hq/**\").hasAuthority(\"SESSION_HQ\")");
    }

    @Test
    void 나머지_경로는_사장님_세션만_허용한다() throws Exception {
        String s = source();
        assertThat(s).contains(".anyRequest().hasAuthority(\"SESSION_USER\")");
        assertThat(s).doesNotContain(".anyRequest().authenticated()");
    }

    @Test
    void 확장_토큰은_사장님_세션_권한을_받는다() throws Exception {
        String s = Files.readString(Path.of("src/main/java/com/storemanager/api/naver/ExtensionTokenFilter.java"),
                StandardCharsets.UTF_8);
        assertThat(s).contains("SimpleGrantedAuthority(\"SESSION_USER\")");
    }
}
