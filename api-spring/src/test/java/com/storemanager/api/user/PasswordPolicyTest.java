package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storemanager.api.common.ApiException;
import org.junit.jupiter.api.Test;

/**
 * 비밀번호 정책을 잠근다. 길이만 보던 상태에서는 8자여도 "password" 면 통과했다.
 *
 * <p>★ 이 테스트를 지우면 정책이 사라진 것을 아무도 모른다. 회원가입은 계속 200 을 준다.
 */
class PasswordPolicyTest {

    @Test
    void 정상_비밀번호는_통과한다() {
        assertThatCode(() -> PasswordPolicy.validate("noodle-rain-42", "owner@example.com", "이호영"))
                .doesNotThrowAnyException();
    }

    @Test
    void 짧으면_거절한다() {
        assertThatThrownBy(() -> PasswordPolicy.validate("short1234", "a@b.com", "홍길동"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 흔한_비밀번호를_거절한다() {
        for (String pw : new String[] {"password123", "1234567890", "qwertyuiop", "baemin1234"}) {
            assertThatThrownBy(() -> PasswordPolicy.validate(pw, "a@b.com", "홍길동"))
                    .as(pw)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void 같은_글자_반복을_거절한다() {
        assertThatThrownBy(() -> PasswordPolicy.validate("aaaaaaaaaa", "a@b.com", "홍길동"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("abababababab", "a@b.com", "홍길동"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 연속된_문자를_거절한다() {
        assertThatThrownBy(() -> PasswordPolicy.validate("zx12345abc", "a@b.com", "홍길동"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("qwabcdezx9", "a@b.com", "홍길동"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 이메일_이름이_들어가면_거절한다() {
        // 계정이 털렸을 때 가장 먼저 시도되는 조합이다.
        assertThatThrownBy(() -> PasswordPolicy.validate("reviewpilot9x", "reviewpilot@example.com", "홍길동"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("xxhoyoung77", "a@b.com", "hoyoung"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 짧은_이름은_우연_일치로_막지_않는다() {
        // 3자 이하 조각까지 막으면 정상 비밀번호가 이유 없이 거절된다.
        assertThatCode(() -> PasswordPolicy.validate("mist-harbor-7", "abc@b.com", "김"))
                .doesNotThrowAnyException();
    }
}
