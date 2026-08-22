package com.storemanager.api.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 모든 컨트롤러의 예외를 docs/13 §1.1 표준 에러 봉투로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        ErrorCode ec = ex.getErrorCode();
        Map<String, Object> details = ex.getDetails();
        return ResponseEntity.status(ec.getStatus())
                .body(new ErrorResponse(ec.name(), ec.getMessage(), traceId(), details.isEmpty() ? null : details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(ec.getStatus())
                .body(new ErrorResponse(ec.name(), ec.getMessage(), traceId(), Map.of("fields", fields)));
    }

    /** 본문이 JSON 으로 파싱되지 않는 경우(인코딩 오류 포함)는 서버 오류가 아니라 400 이다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(ec.getStatus())
                .body(new ErrorResponse(ec.name(), "요청 본문을 읽을 수 없습니다. JSON 형식과 UTF-8 인코딩을 확인해 주세요.",
                        traceId(), null));
    }

    /** 계정·매장 매핑의 DB 유니크 경합은 계약된 중복 응답으로 변환한다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause() == null ? "" : ex.getMostSpecificCause().getMessage();
        if (message.contains("platform_account") || message.contains("platform_store_id")) {
            ErrorCode ec = ErrorCode.DUPLICATE_RESOURCE;
            return ResponseEntity.status(ec.getStatus())
                    .body(new ErrorResponse(ec.name(), ec.getMessage(), traceId(), null));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", traceId(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        // 예상 못한 예외는 반드시 traceId 와 함께 로그에 남긴다. 응답 본문에는 내부 정보를 노출하지 않는다.
        String traceId = traceId();
        log.error("처리되지 않은 예외 (traceId={})", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", traceId, null));
    }

    private String traceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
