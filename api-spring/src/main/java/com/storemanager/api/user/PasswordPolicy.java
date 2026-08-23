package com.storemanager.api.user;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 비밀번호 정책. 회원가입·비밀번호 변경 두 경로에서 같은 규칙을 쓴다.
 *
 * <p>★ 길이만 보던 상태였다. 실제 공격은 사전 대입이라 8자여도 "password"·"12345678" 이면 뚫린다.
 *
 * <p>★ 왜 대문자·특수문자 강제를 넣지 않았나 — 그 규칙은 사람을 "Password1!" 로 몰아넣을 뿐
 * 실제 엔트로피를 올리지 못한다는 게 NIST SP 800-63B 의 결론이다. 대신 길이를 올리고
 * 추측 가능한 형태를 차단한다. 40~60대 대상이라 외우기 어려운 규칙은 메모지에 적히게 된다.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 10;

    /**
     * 자주 쓰이는 비밀번호. 전량 목록을 담지 않는다 — 사전 파일을 들이는 대신, 실제로 상위권에
     * 반복 등장하는 형태만 막는다. 나머지는 아래의 구조 규칙(연속·반복·개인정보 포함)이 걸러낸다.
     */
    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "passw0rd", "qwerty123", "qwertyuiop",
            "1234567890", "123456789", "12345678", "1q2w3e4r", "1q2w3e4r5t", "asdfasdf",
            "iloveyou", "admin123", "administrator", "letmein123", "welcome123", "abcd1234",
            "aaaaaaaaaa", "zaq12wsx", "qazwsxedc", "korea1234", "seoul1234", "test1234",
            "baemin1234", "coupang1234", "yogiyo1234");

    private PasswordPolicy() {
    }

    /**
     * 정책 위반이면 VALIDATION_FAILED 를 던진다.
     *
     * @param email 비밀번호에 이메일 앞부분이 그대로 들어갔는지 보기 위해 받는다. null 허용.
     * @param name  같은 이유로 이름도 본다. null 허용.
     */
    static void validate(String password, String email, String name) {
        String pw = password == null ? "" : password;
        String lower = pw.toLowerCase(Locale.ROOT);

        if (pw.length() < MIN_LENGTH) {
            reject("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        if (pw.chars().distinct().count() < 4) {
            // "aaaaaaaaaa", "abababab" 처럼 사실상 몇 글자로 이루어진 비밀번호.
            reject("같은 글자를 반복한 비밀번호는 사용할 수 없습니다.");
        }
        if (COMMON.contains(lower)) {
            reject("너무 흔한 비밀번호입니다. 다른 비밀번호를 사용해 주세요.");
        }
        if (hasRun(lower, 5)) {
            // 12345, abcde 처럼 연속된 문자. 키보드 순서(qwert)까지는 보지 않는다.
            reject("연속된 숫자나 문자가 이어지는 비밀번호는 사용할 수 없습니다.");
        }
        if (containsPersonal(lower, email, name)) {
            reject("이메일이나 이름이 들어간 비밀번호는 사용할 수 없습니다.");
        }
    }

    /** len 개 이상 연속 증가/감소하는 문자열이 있으면 true. */
    private static boolean hasRun(String s, int len) {
        int up = 1;
        int down = 1;
        for (int i = 1; i < s.length(); i++) {
            int diff = s.charAt(i) - s.charAt(i - 1);
            up = diff == 1 ? up + 1 : 1;
            down = diff == -1 ? down + 1 : 1;
            if (up >= len || down >= len) {
                return true;
            }
        }
        return false;
    }

    /** 이메일 앞부분·이름이 비밀번호에 그대로 들어갔는지. 4자 미만 조각은 우연 일치가 많아 보지 않는다. */
    private static boolean containsPersonal(String lower, String email, String name) {
        String local = email == null ? "" : email.split("@")[0].toLowerCase(Locale.ROOT);
        String who = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return (local.length() >= 4 && lower.contains(local))
                || (who.length() >= 4 && lower.contains(who));
    }

    private static void reject(String message) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("field", "password", "reason", message));
    }
}
