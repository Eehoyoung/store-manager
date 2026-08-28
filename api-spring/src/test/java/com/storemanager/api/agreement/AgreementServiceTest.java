package com.storemanager.api.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import org.junit.jupiter.api.Test;

class AgreementServiceTest {
    private final UserAgreementRepository repository = mock(UserAgreementRepository.class);
    private final AgreementService service = new AgreementService(repository);

    @Test
    void 구버전은_409로_거절한다() {
        assertThatThrownBy(() -> service.requireCurrentVersion("old"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONSENT_VERSION_MISMATCH);
    }

    @Test
    void 철회는_기존행을_바꾸지_않고_새행으로_저장한다() {
        service.record(1L, 2L, AgreementService.HQ, true, "127.0.0.1", "test");
        service.record(1L, 2L, AgreementService.HQ, false, "127.0.0.1", "test");
        verify(repository, times(2)).save(any(UserAgreement.class));
    }

    /**
     * ★ X-Forwarded-For 는 클라이언트가 넣는 값이다. 호스트명을 그대로 넘기면 미인증 가입 경로가
     * DNS 를 조회하게 된다. 리터럴만 통과시키고 나머지는 조용히 버린다.
     */
    @Test
    void 호스트명은_DNS를_조회하지_않고_버린다() {
        org.assertj.core.api.Assertions.assertThat(AgreementService.toAddress("evil.example.com")).isNull();
        org.assertj.core.api.Assertions.assertThat(AgreementService.toAddress("localhost")).isNull();
        org.assertj.core.api.Assertions.assertThat(AgreementService.toAddress("1.2.3.4.evil.example.com")).isNull();
        org.assertj.core.api.Assertions.assertThat(AgreementService.toAddress(null)).isNull();

        org.assertj.core.api.Assertions.assertThat(AgreementService.toAddress("203.0.113.7"))
                .isNotNull()
                .extracting(java.net.InetAddress::getHostAddress).isEqualTo("203.0.113.7");
        org.assertj.core.api.Assertions.assertThat(AgreementService.toAddress("2001:db8::1")).isNotNull();
    }
}
