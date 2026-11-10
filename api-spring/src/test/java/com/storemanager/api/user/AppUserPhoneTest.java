package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AppUserPhoneTest {

    private AppUser verified() {
        AppUser user = AppUser.builder().email("a@b.c").name("홍길동").phone("010-1234-5678").build();
        user.markPhoneVerified(Instant.now());
        return user;
    }

    @Test
    void 하이픈만_다른_같은_번호면_확인이_유지된다() {
        AppUser user = verified();
        user.updateProfile("홍길동", "01012345678");
        assertThat(user.getPhoneVerifiedAt()).isNotNull();
    }

    @Test
    void 다른_번호면_확인이_풀린다() {
        AppUser user = verified();
        user.updateProfile("홍길동", "010-9999-8888");
        assertThat(user.getPhoneVerifiedAt()).isNull();
    }

    @Test
    void 번호를_지우면_확인이_풀린다() {
        AppUser user = verified();
        user.updateProfile("홍길동", "");
        assertThat(user.getPhoneVerifiedAt()).isNull();
    }
}
