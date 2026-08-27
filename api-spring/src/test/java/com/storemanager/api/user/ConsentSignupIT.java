package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.agreement.UserAgreementRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.franchise.FranchiseAffiliationRequestRepository;
import com.storemanager.api.franchise.FranchiseJoinCode;
import com.storemanager.api.franchise.FranchiseJoinCodeRepository;
import com.storemanager.api.security.JwtTokenProvider;
import com.storemanager.api.store.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class ConsentSignupIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired AuthService service;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired UserAgreementRepository agreements;
    @Autowired FranchiseJoinCodeRepository codes;
    @Autowired FranchiseAffiliationRequestRepository affiliations;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean JwtTokenProvider tokens;

    @BeforeEach
    void tokens() {
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(tokens.createAccessToken(anyString())).thenReturn("access");
        when(tokens.createRefreshToken()).thenReturn("refresh");
        when(tokens.getAccessTtlSeconds()).thenReturn(1800L);
        when(tokens.getRefreshTtlSeconds()).thenReturn(3600L);
    }

    @Test
    void 필수동의가_빠지면_사용자와_매장이_트랜잭션에_남지_않는다() {
        long beforeUsers = users.count();
        long beforeStores = stores.count();
        var req = request("missing@test.com", null, false, true, null, AgreementService.CURRENT_VERSION);
        assertThatThrownBy(() -> service.signup(req, "127.0.0.1", "test"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONSENT_REQUIRED);
        assertThat(users.count()).isEqualTo(beforeUsers);
        assertThat(stores.count()).isEqualTo(beforeStores);
    }

    @Test
    void 가맹코드_HQ미동의는_가입되고_거부행만_남는다() {
        service.signup(request("decline@test.com", "ABCD-EFGH-JKLM", true, true, false,
                AgreementService.CURRENT_VERSION), "127.0.0.1", "test");
        var user = users.findByEmailIgnoreCaseAndDeletedAtIsNull("decline@test.com").orElseThrow();
        assertThat(affiliations.count()).isZero();
        assertThat(agreements.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .anySatisfy(a -> { assertThat(a.getAgreementCode()).isEqualTo(AgreementService.HQ); assertThat(a.isAgreed()).isFalse(); });
    }

    @Test
    void 가맹코드_HQ동의는_소속요청을_만든다() throws Exception {
        String code = "ABCD-EFGH-JKLM";
        codes.save(FranchiseJoinCode.builder().brandName("테스트본부").codeHash(hash(code)).build());
        service.signup(request("accept@test.com", code, true, true, true, AgreementService.CURRENT_VERSION),
                "127.0.0.1", "test");
        assertThat(affiliations.count()).isOne();
    }

    @Test
    void 구버전은_409이고_사용자를_만들지_않는다() {
        long before = users.count();
        assertThatThrownBy(() -> service.signup(request("old@test.com", null, true, true, null, "old"), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONSENT_VERSION_MISMATCH);
        assertThat(users.count()).isEqualTo(before);
    }

    private SignupRequest request(String email, String code, boolean terms, boolean privacy, Boolean hq,
            String version) {
        return new SignupRequest(email, "password1234", "사장님", null, code, "테스트매장", "서울",
                terms, privacy, hq, version);
    }

    private String hash(String code) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.replace("-", "").getBytes(StandardCharsets.UTF_8)));
    }
}
