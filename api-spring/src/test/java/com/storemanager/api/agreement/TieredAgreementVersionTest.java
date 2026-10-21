package com.storemanager.api.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.hq.HqReviewAccessProperties;
import com.storemanager.api.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

/** 선택형 요금 약관 버전 접근자(PR1). 꺼짐이면 현행 2026-10-21 과 동일해야 한다. */
class TieredAgreementVersionTest {
    private static final String OLD = "2026-10-21";
    private static final String LEGACY = "2026-09-30";
    private static final String NEW = "2026-11-01";
    private static final String[] SLUGS =
            {"terms", "privacy", "hq-data-sharing", "platform-credential", "hq-review-sharing"};

    private final UserAgreementRepository repository = mock(UserAgreementRepository.class);

    private AgreementService service(String flag) {
        return new AgreementService(repository, new TieredPricingFlag(flag));
    }

    private AgreementController controller(AgreementService service) {
        return new AgreementController(service, mock(AppUserRepository.class), mock(AuditLogRepository.class),
                new HqReviewAccessProperties(false));
    }

    @Test
    void 꺼짐이면_카탈로그_문서_검증_저장_버전이_현행과_같다() {
        AgreementService s = service("false");
        AgreementController c = controller(s);
        assertThat(s.currentVersion()).isEqualTo(OLD);
        assertThat(c.catalog().currentVersion()).isEqualTo(OLD);
        assertThat(c.terms().version()).isEqualTo(OLD);
        assertThat(c.terms().content()).startsWith("문서 버전: " + OLD);
        s.requireCurrentVersion(OLD);
        assertThatThrownBy(() -> s.requireCurrentVersion(NEW)).isInstanceOf(ApiException.class);
        s.record(1L, null, AgreementService.TERMS, true, "127.0.0.1", "t");
        assertThat(saved().getDocVersion()).isEqualTo(OLD);
    }

    @Test
    void 켜짐이면_새_버전이_나가고_옛_버전_동의는_거절된다() {
        AgreementService s = service("true");
        AgreementController c = controller(s);
        assertThat(c.catalog().currentVersion()).isEqualTo(NEW);
        assertThat(c.terms().version()).isEqualTo(NEW);
        assertThat(c.privacy().content()).startsWith("문서 버전: " + NEW);
        s.requireCurrentVersion(NEW);
        assertThatThrownBy(() -> s.requireCurrentVersion(OLD)).isInstanceOf(ApiException.class);
        s.record(1L, null, AgreementService.TERMS, true, "127.0.0.1", "t");
        assertThat(saved().getDocVersion()).isEqualTo(NEW);
    }

    @Test
    void 정확히_true_만_켜진다() {
        for (String raw : new String[] {"TRUE", "True", "1", "yes", "on", "", " true", "false"}) {
            assertThat(new TieredPricingFlag(raw).enabled()).as(raw).isFalse();
        }
        assertThat(new TieredPricingFlag("true").enabled()).isTrue();
        assertThat(new AgreementService(repository).currentVersion()).isEqualTo(OLD);
    }

    /** 상수 직접 참조가 접근자 밖에 있으면 플래그를 우회해 옛 버전이 나간다. */
    @Test
    void noDirectCurrentVersionConstantReference() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("AgreementService.java")) continue;
                assertThat(Files.readString(f, StandardCharsets.UTF_8))
                        .as("%s 가 약관 버전 상수를 직접 참조한다 - currentVersion() 을 쓸 것", f)
                        .doesNotContain("CURRENT_VERSION").doesNotContain("TIERED_VERSION");
            }
        }
    }

    @Test
    void 새_버전_문서_다섯_개가_있고_본문_버전이_같다() throws Exception {
        for (String slug : SLUGS) {
            String body = read(slug);
            assertThat(body).as(slug).isNotBlank().contains("문서 버전: " + NEW).doesNotContain("§");
        }
        assertThat(read("terms")).contains("시행일: 2026년 11월 1일");
        assertThat(read("privacy")).contains("시행일: 2026년 11월 1일");
    }

    /** AgreementDocumentTest 의 필수 문구를 새 버전에도 요구한다(결제대행사 표기만 NHN KCP). */
    @Test
    void 새_버전에도_필수_문구가_있다() throws Exception {
        assertThat(read("terms"))
                .contains("답글 게시는 되돌릴 수 없습니다", "포트원 V2", "NHN KCP", "빌링키", "30일간", "D+3일 00:00",
                        "이미 예약된 답글의 자동 게시", "25,000원", "29,000원", "33,000원", "9.1의3",
                        "월 1회", "3개 상품으로 보며")
                .doesNotContain("법무 검토 전 초안");
        assertThat(read("privacy"))
                .contains("가맹본부 제공에 대한 동의 철회", "사업자등록번호, 개업일자, 대표자명", "빌링키", "NHN KCP㈜", "㈜코리아포트원")
                .doesNotContain("㈜케이지이니시스");
        assertThat(read("hq-data-sharing")).contains("본부는 보기만 할 수 있습니다", "동의하지 않으셔도 됩니다");
        assertThat(read("platform-credential")).contains("기웅정보통신(주)", "사장님이 직접 운영하시는 매장의 계정만");
        assertThat(read("hq-review-sharing")).contains("법무 검토 전 초안", "철회");
    }

    /** 기존 디렉터리는 동의의 근거 문서다 - 새 디렉터리를 만들면서 건드리지 않았는지 정체성만 확인. */
    @Test
    void 옛_버전_문서는_결제대행사_표기가_그대로다() throws Exception {
        String old = new String(new ClassPathResource("agreements/" + LEGACY + "/terms.md")
                .getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(old).contains("KG이니시스").doesNotContain("NHN KCP").contains("33,000원");
    }

    private UserAgreement saved() {
        ArgumentCaptor<UserAgreement> cap = ArgumentCaptor.forClass(UserAgreement.class);
        verify(repository).save(cap.capture());
        return cap.getValue();
    }

    private static String read(String slug) throws Exception {
        return new String(new ClassPathResource("agreements/" + NEW + "/" + slug + ".md")
                .getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
