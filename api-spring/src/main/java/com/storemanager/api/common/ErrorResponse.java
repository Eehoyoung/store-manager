package com.storemanager.api.common;

import java.util.Map;

/** 내부 API 명세(docs/13 §1.1) 표준 에러 응답 봉투. */
public record ErrorResponse(String code, String message, String traceId, Map<String, Object> details) {
}
