package com.storemanager.api.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 문서 본문이 말하는 버전과 서버가 기록하는 버전이 같은가.
 *
 * <p><b>★ 여기가 어긋나면 "무엇에 동의했는가" 를 답할 수 없다.</b> 실제로 한 번 어긋나 있었다 —
 * 동의 기록은 {@code 2026-09-20} 인데 회원이 본 문서에는 {@code 2026-08-28} 이라고 적혀 있었다.
 * 분쟁이 나면 이 한 줄이 그대로 쟁점이 된다.
 */
class AgreementVersionTest {

    private static final Path DIR = Path.of("src/main/resources/agreements");
    private static final Pattern VERSION_IN_BODY = Pattern.compile("(문서 버전|시행일)[^\\n]*?(\\d{4})[-년]\\s?(\\d{1,2})[-월]\\s?(\\d{1,2})");

    @Test
    void 문서_본문의_버전_표기가_현행_버전과_같다() throws IOException {
        Path current = DIR.resolve(AgreementService.CURRENT_VERSION);
        assertThat(Files.isDirectory(current))
                .as("%s 디렉터리가 없다 — CURRENT_VERSION 을 올리고 문서를 안 만들면 가입이 막힌다",
                        AgreementService.CURRENT_VERSION)
                .isTrue();

        List<Path> docs;
        try (Stream<Path> files = Files.list(current)) {
            docs = files.filter(f -> f.toString().endsWith(".md")).toList();
        }
        assertThat(docs).hasSize(4);

        String[] parts = AgreementService.CURRENT_VERSION.split("-");
        for (Path doc : docs) {
            Matcher m = VERSION_IN_BODY.matcher(Files.readString(doc, StandardCharsets.UTF_8));
            while (m.find()) {
                String found = "%s-%02d-%02d".formatted(m.group(2), Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)));
                assertThat(found)
                        .as("%s 본문이 말하는 버전(%s)이 서버가 기록하는 버전과 다르다: \"%s\"",
                                doc.getFileName(), found, m.group())
                        .isEqualTo("%s-%s-%s".formatted(parts[0], parts[1], parts[2]));
            }
        }
    }
}
