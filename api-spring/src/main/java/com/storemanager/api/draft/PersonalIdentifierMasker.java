package com.storemanager.api.draft;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 외부 AI(Claude)에 리뷰 관련 텍스트를 보내기 전, 형식이 명확한 개인 식별자만 지운다.
 * privacy.md §6.3 — "회사는 개인을 알아볼 수 없도록 처리된 정보만 외부 AI에 전송합니다.
 * 개인정보가 남아 있거나 익명성을 보장할 수 없는 경우에는 전송하지 않습니다" 를 실제로 지키는 지점.
 *
 * <p>★ 반드시 Spring 쪽, AI 요청을 만드는 시점(DraftService.buildAiRequest)에 적용한다.
 * ai-python 에서 하면 이미 국외 전송을 마친 뒤라 방침의 약속을 지키지 못한다
 * (docs/goals/legal-code-alignment.json WP-04 T-6).
 *
 * <p>★ 대상은 형식이 명확한 식별자만이다 — 전화번호(휴대폰·일반), 이메일, 계좌번호,
 * 주민등록번호, 카드번호. 이름·주소 추정처럼 모호한 판단은 넣지 않는다. 리뷰 본문에서
 * 지울 근거가 없는데 지우면 "너무 짜서 먹기 힘들었습니다" 류의 답글 품질이 무너진다.
 * 대상을 넓히려면 먼저 "별점 5점"·"30분 지연"·"2인분"·"1인 8900원"·"2026-08-28" 같은
 * 숫자 섞인 일반 표현이 걸리지 않는지 확인할 것 — 이 클래스의 테스트가 그 회귀를 잠근다.
 *
 * <p>원본 문자열은 절대 바꾸지 않는다. 새 문자열을 반환하므로 호출자가 AI 요청 사본에만 써야 한다
 * (unified_review.body 원본은 그대로 둔다).
 */
public final class PersonalIdentifierMasker {

    private PersonalIdentifierMasker() {
    }

    // 이메일 — @ 와 도메인이 있어 형식이 가장 명확하다.
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    // 주민등록번호 — 생년월일 6자리 + 성별구분(1~8) + 6자리. yyyy-MM-dd 날짜(4-2-2)와 자릿수 구성이 달라 겹치지 않는다.
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}-[1-8]\\d{6}(?!\\d)");

    // 카드번호 — 4자리씩 4묶음 + 구분자(하이픈/공백) 필수. 구분자 없는 16연속숫자는 오탐 위험이 커서 뺐다.
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{4}[- ]\\d{4}[- ]\\d{4}[- ]\\d{4}(?!\\d)");

    // 휴대폰번호 — 010/011~019, 하이픈 유무 모두 허용.
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)01[016789]-?\\d{3,4}-?\\d{4}(?!\\d)");

    // 일반 전화번호 — 지역번호(02, 031~064, 070)나 대표번호(1588 등 4자리)+하이픈 형태만 잡는다.
    // 하이픈을 필수로 둬서 "30분"·"8900원" 같은 하이픈 없는 일반 숫자와 겹치지 않게 한다.
    private static final Pattern LANDLINE = Pattern
            .compile("(?<!\\d)(0(?:2|[3-6]\\d|70)-\\d{3,4}-\\d{4}|1[0-9]{3}-\\d{4})(?!\\d)");

    // 계좌번호 — 은행마다 자릿수·구분이 달라 "형식이 명확한" 패턴이 없다. 하이픈 2개로 묶인 숫자 모양만
    // 최소 조건으로 잡고, 실제 계좌번호 자릿수 범위(10~14자리)를 코드에서 한 번 더 확인한다.
    // 위 전화번호·카드번호 패턴을 먼저 치환한 뒤에 돌려야 서로 겹치지 않는다.
    private static final Pattern ACCOUNT_SHAPE = Pattern.compile("(?<!\\d)\\d{2,6}-\\d{2,6}-\\d{2,8}(?!\\d)");

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        masked = EMAIL.matcher(masked).replaceAll("[이메일]");
        masked = RRN.matcher(masked).replaceAll("[주민등록번호]");
        masked = CARD.matcher(masked).replaceAll("[카드번호]");
        masked = MOBILE.matcher(masked).replaceAll("[전화번호]");
        masked = LANDLINE.matcher(masked).replaceAll("[전화번호]");
        masked = maskAccountShapes(masked);
        return masked;
    }

    public static List<String> maskAll(List<String> texts) {
        if (texts == null) {
            return null;
        }
        return texts.stream().map(PersonalIdentifierMasker::mask).toList();
    }

    private static String maskAccountShapes(String text) {
        Matcher m = ACCOUNT_SHAPE.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int digitCount = m.group().replace("-", "").length();
            if (digitCount >= 10 && digitCount <= 14) {
                sb.append(text, last, m.start()).append("[계좌번호]");
                last = m.end();
            }
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }
}
