package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 서버측 입력 형식 검증을 잠근다.
 *
 * ★ 화면이 하이픈을 자동으로 넣어 주지만 그건 편의일 뿐이다. API 를 직접 호출하면 화면 로직은
 * 지나가지 않으므로, 형식은 신뢰 경계인 서버에서 다시 강제해야 한다. 이 테스트를 지우면
 * 그 방어선이 사라진 것을 아무도 모른다.
 */
class AuthPatternsTest {

    private static final Pattern PHONE = Pattern.compile(AuthPatterns.PHONE);
    private static final Pattern CODE = Pattern.compile(AuthPatterns.FRANCHISE_CODE);

    @Test
    void 휴대폰_정상형식을_받는다() {
        assertThat(PHONE.matcher("010-1234-5678").matches()).isTrue();
        assertThat(PHONE.matcher("02-123-4567").matches()).isTrue();   // 서울 2자리 국번
        assertThat(PHONE.matcher("031-123-4567").matches()).isTrue();
        assertThat(PHONE.matcher("").matches()).isTrue();               // 선택 입력
    }

    @Test
    void 휴대폰_잘못된_형식을_거절한다() {
        assertThat(PHONE.matcher("01012345678").matches()).isFalse();   // 하이픈 없음
        assertThat(PHONE.matcher("110-1234-5678").matches()).isFalse(); // 0 으로 시작하지 않음
        assertThat(PHONE.matcher("010-1234-567").matches()).isFalse();  // 자릿수 부족
        assertThat(PHONE.matcher("010-1234-5678 ").matches()).isFalse();// 뒤 공백
        assertThat(PHONE.matcher("010-abcd-5678").matches()).isFalse();
        // ★ 개행으로 뒤를 이어붙이는 시도. Java 의 $ 는 마지막 개행 앞에도 매칭되므로
        //   이런 입력이 통과하면 로그·표시에 엉뚱한 줄이 끼어든다.
        assertThat(PHONE.matcher("010-1234-5678\n<script>").matches()).isFalse();
    }

    @Test
    void 가맹코드는_전달되는_형태_그대로_받는다() {
        // 코드는 9R75-KLZQ-S97E 처럼 하이픈이 붙어 전달된다. 사장님이 소문자로 치거나 공백을
        // 넣어도 받아야 한다 - 실제 대조는 FranchiseService 가 정규화 후 해시로 한다.
        assertThat(CODE.matcher("9R75-KLZQ-S97E").matches()).isTrue();
        assertThat(CODE.matcher("9r75 klzq s97e").matches()).isTrue();
        assertThat(CODE.matcher("").matches()).isTrue();
        assertThat(CODE.matcher("ABC").matches()).isFalse();            // 너무 짧음
    }

    @Test
    void 가맹코드에_주입_문자열을_받지_않는다() {
        // 이 패턴의 일은 코드가 맞는지 판정하는 게 아니라 제어문자·주입 문자열을 거르는 것이다.
        assertThat(CODE.matcher("9R75<script>").matches()).isFalse();
        assertThat(CODE.matcher("9R75\nKLZQ").matches()).isFalse();
        assertThat(CODE.matcher("9R75'--").matches()).isFalse();
    }
}
