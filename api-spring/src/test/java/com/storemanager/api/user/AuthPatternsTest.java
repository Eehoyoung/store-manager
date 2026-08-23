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
    void 가맹코드는_혼동문자를_받지_않는다() {
        // CODE_ALPHABET 에서 I, O, 0, 1 을 뺐다 - 전화로 불러줄 때 혼동을 막기 위함이다.
        assertThat(CODE.matcher("ABCD2345").matches()).isTrue();
        assertThat(CODE.matcher("").matches()).isTrue();
        assertThat(CODE.matcher("ABCI2345").matches()).isFalse();
        assertThat(CODE.matcher("ABCO2345").matches()).isFalse();
        assertThat(CODE.matcher("ABC02345").matches()).isFalse();
        assertThat(CODE.matcher("ABC12345").matches()).isFalse();
        assertThat(CODE.matcher("abcd2345").matches()).isFalse();       // 정규화는 화면 책임
        assertThat(CODE.matcher("ABC").matches()).isFalse();            // 너무 짧음
    }
}
