package com.storemanager.api.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 동의 문서가 현행 버전 디렉터리에 전부 있는지.
 *
 * <p><b>★ 이게 어긋나면 가입이 통째로 막힌다.</b> {@code CURRENT_VERSION} 을 올리고 디렉터리를
 * 안 만들면 네 문서가 모두 404 가 되고, 가입 화면은 "동의 문서를 불러오지 못했습니다" 만 띄운다.
 * 서버는 멀쩡히 떠 있으므로 헬스체크로도 보이지 않는다.
 */
class AgreementDocumentsTest {

    private static final String[] SLUGS = {"terms", "privacy", "hq-data-sharing", "platform-credential"};

    @Test
    void 현행_버전의_동의_문서_네_개가_모두_있다() throws Exception {
        for (String slug : SLUGS) {
            var resource = new ClassPathResource(
                    "agreements/" + AgreementService.CURRENT_VERSION + "/" + slug + ".md");
            assertThat(resource.exists())
                    .as("%s 문서가 %s 버전에 없다", slug, AgreementService.CURRENT_VERSION)
                    .isTrue();
            assertThat(new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8))
                    .as("%s 문서가 비어 있다", slug)
                    .isNotBlank();
        }
    }

    /**
     * ★ 네이버 채널은 서버가 접속하지도, 로그인 정보를 받지도 않는다(NAVER ABSOLUTE RULES 2·3).
     * 그 사실이 약관·처리방침에 없으면 고지되지 않은 처리가 된다. 문구를 지우지 말 것.
     */
    @Test
    void 방문_리뷰_채널이_약관과_처리방침에_고지되어_있다() throws Exception {
        assertThat(read("terms")).contains("제8조의2").contains("네이버 스마트플레이스");
        assertThat(read("privacy")).contains("네이버 스마트플레이스").contains("2.2의2");
    }

    private static String read(String slug) throws Exception {
        return new String(new ClassPathResource(
                "agreements/" + AgreementService.CURRENT_VERSION + "/" + slug + ".md")
                .getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
