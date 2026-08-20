package com.storemanager.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** JwtTokenProvider 단위 테스트: 발급/검증, 만료 거부, 위조 거부. */
class JwtTokenProviderTest {

    // application-test.yml 과 동일한 테스트 전용 더미 시크릿(base64, 32바이트) - 운영 값 아님
    private static final String SECRET = "/HbJWF+hl5rgq8axtp6NmSqJlE3UL7WDQmT2PswcCNg=";

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 1800, 1209600);

    @Test
    void 발급한_토큰에서_subject를_그대로_복원한다() {
        String publicId = UUID.randomUUID().toString();

        String token = provider.createAccessToken(publicId);

        assertThat(provider.parseSubject(token)).isEqualTo(publicId);
    }

    @Test
    void 만료된_토큰은_검증에서_거부된다() {
        JwtTokenProvider expiring = new JwtTokenProvider(SECRET, -1, 1209600); // 발급 즉시 만료
        String token = expiring.createAccessToken(UUID.randomUUID().toString());

        assertThrows(JwtException.class, () -> expiring.parseSubject(token));
    }

    @Test
    void 위조된_토큰은_검증에서_거부된다() {
        String token = provider.createAccessToken(UUID.randomUUID().toString());
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A") + token.charAt(token.length() - 1);

        assertThrows(JwtException.class, () -> provider.parseSubject(tampered));
    }

    @Test
    void 다른_시크릿으로_서명된_토큰은_거부된다() {
        JwtTokenProvider other = new JwtTokenProvider("dGVzdC1vdGhlci1zZWNyZXQtMzJieXRlcy1sb25nISE=", 1800, 1209600);
        String token = other.createAccessToken(UUID.randomUUID().toString());

        assertThrows(JwtException.class, () -> provider.parseSubject(token));
    }
}
