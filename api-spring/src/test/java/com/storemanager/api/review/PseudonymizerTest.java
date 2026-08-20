package com.storemanager.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 절대규칙 6 / docs/08 F-5 검증: 배민 원문 닉네임은 첫 글자만 남기고 마스킹, 이미 마스킹된 값은 그대로 통과. */
class PseudonymizerTest {

    private final Pseudonymizer pseudonymizer = new Pseudonymizer("salt-value");

    @Test
    void 배민_원문_닉네임은_첫글자와_별표로_마스킹된다() {
        Pseudonymizer.Result result = pseudonymizer.mask("히리릴");

        assertThat(result.maskedAuthor()).isEqualTo("히**");
        assertThat(result.authorHash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 한글자_닉네임은_완전히_마스킹된다() {
        assertThat(pseudonymizer.mask("김").maskedAuthor()).isEqualTo("*");
    }

    @Test
    void 이미_마스킹된_값은_그대로_통과한다() {
        assertThat(pseudonymizer.mask("김**").maskedAuthor()).isEqualTo("김**");
        assertThat(pseudonymizer.mask("cl**").maskedAuthor()).isEqualTo("cl**");
    }

    @Test
    void 같은_원문은_항상_같은_해시를_만든다() {
        String hash1 = pseudonymizer.mask("히리릴").authorHash();
        String hash2 = pseudonymizer.mask("히리릴").authorHash();

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void 다른_salt는_다른_해시를_만든다() {
        Pseudonymizer other = new Pseudonymizer("other-salt");

        assertThat(pseudonymizer.mask("히리릴").authorHash())
                .isNotEqualTo(other.mask("히리릴").authorHash());
    }

    @Test
    void null이나_빈값은_마스킹하지_않는다() {
        Pseudonymizer.Result result = pseudonymizer.mask(null);

        assertThat(result.maskedAuthor()).isNull();
        assertThat(result.authorHash()).isNull();
    }
}
