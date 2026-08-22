package com.storemanager.api.common;

import org.springframework.http.HttpStatus;

/**
 * 내부 API 명세(docs/13 §1.2) 에 정의된 에러 코드 전체.
 * HTTP 상태코드와 기본 한국어 메시지를 함께 가진다.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    CONTRACT_NOT_SIGNED(HttpStatus.FORBIDDEN, "전자계약이 완료되지 않아 이용할 수 없습니다."),
    SUBSCRIPTION_INACTIVE(HttpStatus.FORBIDDEN, "구독이 활성 상태가 아닙니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
    // 같은 배달앱 계정을 두 곳에서 연동하면 같은 매장을 이중 수집하고 답글이 겹친다. 검사는 유지하되
    // 사장님이 다음에 무엇을 해야 하는지 문구로 알려준다(문서 14 §1 — 오류는 원인과 다음 행동을 말한다).
    PLATFORM_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT,
            "이미 연동된 배달앱 계정입니다. 기존 연동을 해지한 뒤 다시 시도해 주세요."),
    DRAFT_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "이미 게시된 답글입니다."),
    REVIEW_ALREADY_REPLIED(HttpStatus.CONFLICT, "플랫폼에 이미 답글이 존재합니다."),
    INVALID_DRAFT_STATE(HttpStatus.CONFLICT, "현재 상태에서는 이 작업을 할 수 없습니다."),
    GUARDRAIL_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, "생성된 답글이 안전 규칙에 걸렸습니다."),
    RISK_LEVEL_TOO_HIGH(HttpStatus.UNPROCESSABLE_ENTITY, "위험도가 높아 자동 게시할 수 없습니다."),
    PLATFORM_LINK_ERROR(HttpStatus.UNPROCESSABLE_ENTITY, "플랫폼 계정 연동에 실패했습니다."),
    INVALID_FRANCHISE_CODE(HttpStatus.UNPROCESSABLE_ENTITY, "가맹코드가 올바르지 않거나 사용할 수 없습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "외부 연동 처리 중 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다.");

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
