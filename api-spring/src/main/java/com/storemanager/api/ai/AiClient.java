package com.storemanager.api.ai;

import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftRequest;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Spring → Python 유일한 두 내부 경계 중 하나(CLAUDE.md). POST /internal/ai/analyze-and-draft 만 호출한다.
 * connect 3s / read 30s 타임아웃. 실패 시 UPSTREAM_ERROR(502)로 변환한다.
 */
@Component
public class AiClient {

    private final RestClient restClient;
    private final String internalToken;

    public AiClient(@Value("${ai.base-url}") String baseUrl,
            @Value("${app.internal.token}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(30_000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.internalToken = internalToken;
    }

    public AnalyzeAndDraftResponse analyzeAndDraft(AnalyzeAndDraftRequest req) {
        try {
            return restClient.post()
                    .uri("/internal/ai/analyze-and-draft")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(AnalyzeAndDraftResponse.class);
        } catch (RestClientException e) {
            // 리뷰 본문이 담긴 요청/응답 본문은 로그에 남기지 않는다. 예외 클래스명만 남긴다.
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, java.util.Map.of("upstream", "ai-service"));
        }
    }
}
