package com.storemanager.api.common;

import org.springframework.http.HttpStatus;

/**
 * 내부 API 명세(docs/13 §1.2) 에 정의된 에러 코드 전체.
 * HTTP 상태코드와 기본 한국어 메시지를 함께 가진다.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요해요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요."),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "필수 동의가 필요해요."),
    CONSENT_VERSION_MISMATCH(HttpStatus.CONFLICT, "동의 문서가 변경됐어요. 내용을 다시 확인해 주세요."),
    SUBSCRIPTION_INACTIVE(HttpStatus.FORBIDDEN, "구독이 활성 상태가 아니에요."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없어요."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스예요."),
    // 같은 배달앱 계정을 두 곳에서 연동하면 같은 매장을 이중 수집하고 답글이 겹친다. 검사는 유지하되
    // 사장님이 다음에 무엇을 해야 하는지 문구로 알려준다(문서 14 §1 — 오류는 원인과 다음 행동을 말한다).
    PLATFORM_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT,
            "이미 연동된 배달앱 계정이에요. 기존 연동을 해지한 뒤 다시 시도해 주세요."),
    DRAFT_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "이미 게시된 답글이에요."),
    REVIEW_ALREADY_REPLIED(HttpStatus.CONFLICT, "플랫폼에 이미 답글이 있어요."),
    INVALID_DRAFT_STATE(HttpStatus.CONFLICT, "현재 상태에서는 이 작업을 할 수 없어요."),
    GUARDRAIL_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "생성된 답글이 안전 규칙에 걸렸어요."),
    RISK_LEVEL_TOO_HIGH(HttpStatus.UNPROCESSABLE_ENTITY, "위험도가 높아 자동 게시할 수 없어요."),
    PLATFORM_LINK_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, "플랫폼 계정 연동에 실패했어요."),
    INVALID_FRANCHISE_CODE(HttpStatus.UNPROCESSABLE_ENTITY, "가맹코드가 올바르지 않거나 사용할 수 없어요."),
    // 자동결제(2026-09-29). 화면이 코드로 분기한다 — 이름을 바꾸면 웹도 함께 바꿀 것.
    SUBSCRIPTION_PAYMENT_REQUIRED(HttpStatus.PAYMENT_REQUIRED,
            "결제가 확인되지 않아 이용이 중지됐어요. 결제 화면에서 결제수단을 확인해 주세요."),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "결제가 승인되지 않았어요."),
    PAYMENT_PENDING(HttpStatus.BAD_GATEWAY, "결제 결과를 확인하지 못했어요. 잠시 후 결제 화면을 다시 확인해 주세요."),
    BILLING_BUSY(HttpStatus.CONFLICT, "결제를 처리하고 있어요. 잠시 후 다시 확인해 주세요."),
    BILLING_KEY_INVALID(HttpStatus.BAD_REQUEST, "이 매장에서 등록한 결제수단이 아니에요. 다시 등록해 주세요."),
    BILLING_CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "자동결제에 동의해 주세요."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),
    // ★ 검증 실패는 항상 이 한 종류만 반환한다 — 이메일 등록 여부·시도 횟수 초과 등을 구분해서
    //   알려주지 않는다(docs/26a auth.otp). 세분화하면 계정 존재 여부가 새어 나간다.
    OTP_INVALID(HttpStatus.UNAUTHORIZED, "인증번호가 올바르지 않거나 만료됐어요."),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "외부 연동 처리 중 오류가 발생했어요."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없어요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
