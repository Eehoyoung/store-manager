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
}
