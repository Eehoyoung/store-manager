package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.franchise.FranchiseService;
import com.storemanager.api.security.JwtTokenProvider;
import com.storemanager.api.store.StoreService;
import com.storemanager.api.store.Store;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

/** AuthService 단위 테스트: 회원가입→로그인 해피패스, 이메일 중복 409. Repository 등은 Mockito 로 목킹한다. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private StoreService storeService;
    @Mock
    private FranchiseService franchiseService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(appUserRepository, passwordEncoder, jwtTokenProvider, redisTemplate, storeService,
                franchiseService);
    }

    @Test
    void 회원가입후_로그인_해피패스() {
        SignupRequest signupReq = new SignupRequest("owner@store.com", "password1234", "홍사장", "010-1234-5678",
                "SODM-TEST-CODE", "판교점", "경기 성남시 분당구 판교역로 166");
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(signupReq.email()))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode(signupReq.password())).thenReturn("bcrypt-hash");
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        Store pendingStore = Store.builder().id(10L).ownerId(1L).name(signupReq.storeName()).build();
        when(storeService.createStore(any(AppUser.class), anyString(), anyString())).thenReturn(pendingStore);
        stubTokenIssuance();

        AuthService.TokenPair signupResult = authService.signup(signupReq);

        assertThat(signupResult.accessToken()).isEqualTo("access-token");
        assertThat(signupResult.user().getEmail()).isEqualTo(signupReq.email());
        assertThat(signupResult.user().getFranchiseBrandName()).isNull();
        verify(appUserRepository).save(any(AppUser.class));
        verify(storeService).createStore(signupResult.user(), signupReq.storeName(), signupReq.storeAddress());
        verify(franchiseService).requestAffiliation(signupResult.user(), pendingStore, signupReq.franchiseCode());

        // 방금 가입한 사용자로 로그인
        AppUser saved = signupResult.user();
        LoginRequest loginReq = new LoginRequest(saved.getEmail(), signupReq.password());
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(loginReq.email()))
                .thenReturn(Optional.of(saved));
        when(passwordEncoder.matches(loginReq.password(), saved.getPasswordHash())).thenReturn(true);

        AuthService.TokenPair loginResult = authService.login(loginReq);

        assertThat(loginResult.accessToken()).isEqualTo("access-token");
        assertThat(loginResult.user().getEmail()).isEqualTo(saved.getEmail());
    }

    @Test
    void 이메일이_이미_존재하면_회원가입은_409_DUPLICATE_RESOURCE() {
        SignupRequest req = new SignupRequest("dup@store.com", "password1234", "김사장", null,
                "SODM-TEST-CODE", "강남점", "서울 강남구 테헤란로 1");
        AppUser existing = AppUser.builder().email(req.email()).name("기존회원").passwordHash("hash").build();
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(req.email()))
                .thenReturn(Optional.of(existing));

        ApiException ex = assertThrows(ApiException.class, () -> authService.signup(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        assertThat(ex.getErrorCode().getStatus().value()).isEqualTo(409);
    }

    @Test
    void 일반사용자는_가맹코드없이_브랜드없는_매장으로_가입한다() {
        SignupRequest req = new SignupRequest("solo@store.com", "password1234", "일반사장", null,
                null, "개인매장", "서울 종로구 종로 1");
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(req.email())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(req.password())).thenReturn("bcrypt-hash");
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTokenIssuance();

        AuthService.TokenPair result = authService.signup(req);

        assertThat(result.user().getFranchiseBrandName()).isNull();
        verify(storeService).createStore(result.user(), req.storeName(), req.storeAddress());
        verifyNoInteractions(franchiseService);
    }

    @Test
    void 비밀번호가_틀리면_로그인은_401_UNAUTHORIZED() {
        LoginRequest req = new LoginRequest("owner@store.com", "wrong-password");
        AppUser existing = AppUser.builder().email(req.email()).name("홍사장").passwordHash("bcrypt-hash").build();
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(req.email()))
                .thenReturn(Optional.of(existing));
        when(passwordEncoder.matches(req.password(), existing.getPasswordHash())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.login(req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private void stubTokenIssuance() {
        when(jwtTokenProvider.createAccessToken(anyString())).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken()).thenReturn("refresh-token");
        when(jwtTokenProvider.getAccessTtlSeconds()).thenReturn(1800L);
        when(jwtTokenProvider.getRefreshTtlSeconds()).thenReturn(1209600L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
}
