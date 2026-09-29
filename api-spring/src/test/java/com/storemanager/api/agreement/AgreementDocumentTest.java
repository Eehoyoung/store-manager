package com.storemanager.api.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AgreementDocumentTest {
    @Test
    void 공개문서에는_내부_법무검토_표시가_없고_필수문구가_있다() throws Exception {
        String base = "agreements/" + AgreementService.CURRENT_VERSION + "/";
        String terms = read(base + "terms.md");
        String privacy = read(base + "privacy.md");
        String hq = read(base + "hq-data-sharing.md");
        String credential = read(base + "platform-credential.md");
        assertThat(terms + privacy + hq + credential)
                .doesNotContain("법무 검토 전 초안", "초안 주석", "§16. 변호사 확인");
        assertThat(terms)
                .contains("답글 게시는 되돌릴 수 없습니다")
                .contains("포트원 V2")
                .contains("KG이니시스")
                .contains("빌링키")
                .contains("30일간")
                .contains("D+3일 00:00")
                .contains("이미 예약된 답글의 자동 게시");
        assertThat(privacy)
                .contains("가맹본부 제공에 대한 동의 철회")
                .contains("사업자등록번호, 개업일자, 대표자명")
                .contains("빌링키")
                .contains("㈜케이지이니시스")
                .contains("㈜코리아포트원");
        assertThat(hq).contains("본부는 보기만 할 수 있습니다", "동의하지 않으셔도 됩니다");
        assertThat(credential).contains("기웅정보통신(주)", "사장님이 직접 운영하시는 매장의 계정만");

        // ★ hq-review-sharing 은 법무 검토 전이라 초안 표시를 의도적으로 남긴다(docs/26a decisions.reviewConsent).
        String reviewSharing = read(base + "hq-review-sharing.md");
        assertThat(reviewSharing).contains("법무 검토 전 초안", "동의하지 않으셔도 됩니다", "철회");
        assertThat(terms + privacy + hq + credential)
                .doesNotContain("BANK_TRANSFER", "deposit code", "Groble", "입금자명", "무통장");
    }

    private String read(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
