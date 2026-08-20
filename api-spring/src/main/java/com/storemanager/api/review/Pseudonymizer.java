package com.storemanager.api.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 리뷰 작성자 닉네임 가명처리 (절대규칙 6, docs/08 F-5).
 * 배민은 원문 닉네임("히리릴")이 그대로 오고, 요기요·쿠팡이츠는 이미 마스킹("cl**")되어 온다.
 * 원본 닉네임은 이 클래스 밖으로 나가지 않는다 — 로그·예외 메시지에 절대 담지 말 것.
 */
@Component
public class Pseudonymizer {

    private final String salt;

    public Pseudonymizer(@Value("${app.privacy.salt}") String salt) {
        this.salt = salt;
    }

    /** authorRaw 를 받아 (author_masked, author_hash) 를 반환한다. 원본은 반환값에 포함되지 않는다. */
    public Result mask(String authorRaw) {
        if (authorRaw == null || authorRaw.isBlank()) {
            return new Result(null, null);
        }
        String masked = authorRaw.endsWith("**") ? authorRaw : maskFirstChar(authorRaw);
        String hash = sha256Hex(authorRaw + salt);
        return new Result(masked, hash);
    }

    private String maskFirstChar(String raw) {
        if (raw.length() == 1) {
            return "*";
        }
        return raw.charAt(0) + "*".repeat(raw.length() - 1);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    public record Result(String maskedAuthor, String authorHash) {
    }
}
