package com.storemanager.api.internal;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker → Spring 내부 API (docs/13 §11.2). 외부 네트워크에 노출하지 않는 전제로, JWT 대신
 * 공유 시크릿 헤더(X-Internal-Token)로 인증한다.
 * ★ 요청 본문에 배민 원본 닉네임(authorRaw)이 포함되므로 이 컨트롤러는 요청/응답을 로깅하지 않는다.
 */
@RestController
@RequestMapping("/internal")
public class CollectResultController {

    private final CollectResultService collectResultService;
    private final String internalToken;

    public CollectResultController(CollectResultService collectResultService,
            @Value("${app.internal.token}") String internalToken) {
        this.collectResultService = collectResultService;
        this.internalToken = internalToken;
    }

    @PostMapping("/collect-result")
    public ResponseEntity<CollectResultService.CollectResultSummary> receive(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody CollectResultRequest req) {
        // 시크릿 비교는 상수시간으로 — 길이·일치 위치가 응답시간으로 새지 않게 한다.
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return ResponseEntity.ok(collectResultService.ingest(req));
    }
}
