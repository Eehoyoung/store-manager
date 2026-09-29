package com.storemanager.api.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 포트원 V2 REST 호출. 빌링키 조회·삭제와 빌링키 결제만 쓴다(소담한판 PortOneClient 이식, 2026-09-29).
 *
 * <p>카드 정보는 PG 결제창에서 바로 PG로 가고, 우리는 빌링키(대리값)만 받는다(절대규칙 7).
 * 응답 본문을 로그에 남기지 말 것 — 결제 오류 본문에 고객 이름·연락처가 되돌아올 수 있다.
 */
@Service
public class PortOneClient {

    private static final Logger log = LoggerFactory.getLogger(PortOneClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String apiBase;
    private final String secret;
    private final String storeId;
    private final String channelKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 생성자 하나만 둔다. 테스트용 가짜 포트원 서버는 {@code app.portone.api-base} 를
     * {@code @DynamicPropertySource} 로 바꿔 가리키게 한다 — 별도 테스트 생성자를 두면 Spring 이
     * 어떤 생성자로 빈을 만들지 알 수 없어 기동이 깨진다(2026-09-29 실측).
     */
    public PortOneClient(@Value("${app.portone.api-secret:}") String secret,
            @Value("${app.portone.store-id:}") String storeId,
            @Value("${app.portone.inicis-channel-key:}") String channelKey,
            @Value("${app.portone.api-base:https://api.portone.io}") String apiBase) {
        this.secret = secret.trim();
        this.storeId = storeId.trim();
        this.channelKey = channelKey.trim();
        this.apiBase = apiBase;
    }

    /** 값이 하나라도 비면 결제를 받지 않는다. 설정 실수가 결제 없는 서비스 시작으로 새지 않게 한다. */
    public boolean enabled() {
        return !secret.isEmpty() && !storeId.isEmpty() && !channelKey.isEmpty();
    }

    public String storeId() {
        return storeId;
    }

    public String channelKey() {
        return channelKey;
    }

    public JsonNode billingKey(String billingKey) {
        Reply r = send("GET", "/billing-keys/" + enc(billingKey) + "?storeId=" + enc(storeId), null);
        if (r.status == 404) {
            throw new ApiException(ErrorCode.BILLING_KEY_INVALID);
        }
        if (r.status != 200) {
            throw unavailable();
        }
        return r.body;
    }

    public void deleteBillingKey(String billingKey) {
        try {
            send("DELETE", "/billing-keys/" + enc(billingKey) + "?storeId=" + enc(storeId), null);
        } catch (ApiException e) {
            log.warn("portone billing key delete failed");
        }
    }

    public enum Outcome {PAID, DECLINED, UNKNOWN}

    public record Charge(Outcome outcome, String reason) {}

    /**
     * ★ 성공 판정은 최상위 status 가 아니라 HTTP 200 이다(소담한판과 동일). 포트원 V2 문서상 성공 응답은
     * {@code {"payment":{"pgTxId":..,"paidAt":..}}} 형태로 status 필드가 없다 — 실결제로는 아직 확인 전.
     */
    public Charge pay(String paymentId, String billingKey, String orderName, long amount, Map<String, Object> customer) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("storeId", storeId);
        body.put("billingKey", billingKey);
        body.put("orderName", orderName);
        body.put("amount", Map.of("total", amount));
        body.put("currency", "KRW");
        body.put("customer", customer);
        Reply r;
        try {
            r = send("POST", "/payments/" + enc(paymentId) + "/billing-key", body);
        } catch (ApiException e) {
            return confirm(paymentId);
        }
        if (r.status == 200) {
            return new Charge(Outcome.PAID, null);
        }
        String type = r.body.path("type").asText("");
        // 같은 paymentId 로 다시 부르면 여기로 온다. 앞선 호출이 결제됐다는 뜻이다.
        if ("ALREADY_PAID".equals(type)) {
            return new Charge(Outcome.PAID, null);
        }
        if (r.status >= 500 && !"PG_PROVIDER".equals(type)) {
            return confirm(paymentId);
        }
        return new Charge(Outcome.DECLINED, declineReason(r.body, type));
    }

    /** 카드사 거절 사유는 pgMessage 에 온다(예: "[1254][실시간빌링실패|잔액부족]"). message 는 비어 있을 때가 있다. */
    private static String declineReason(JsonNode body, String fallback) {
        for (String field : new String[] {"pgMessage", "message"}) {
            String v = body.path(field).asText("");
            if (!v.isBlank()) {
                return shortReason(v);
            }
        }
        return "카드사에서 승인하지 않았어요(" + fallback + ")";
    }

    /** 응답을 못 받았으면 결제됐는지 되물어 본다. 그래도 모르면 UNKNOWN 으로 두고 사람이 대조한다. */
    private Charge confirm(String paymentId) {
        try {
            Reply r = send("GET", "/payments/" + enc(paymentId) + "?storeId=" + enc(storeId), null);
            String status = r.body.path("status").asText("");
            if ("PAID".equals(status)) {
                return new Charge(Outcome.PAID, null);
            }
            if ("FAILED".equals(status)) {
                return new Charge(Outcome.DECLINED, shortReason(r.body.path("failure").path("reason").asText("결제 실패")));
            }
        } catch (ApiException ignored) {
            // 조회도 실패 — UNKNOWN 으로 둔다.
        }
        return new Charge(Outcome.UNKNOWN, null);
    }

    private record Reply(int status, JsonNode body) {}

    private Reply send(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(TIMEOUT)
                    .header("Authorization", "PortOne " + secret).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 400) {
                log.warn("portone {} returned {}", method, res.statusCode());
            }
            String text = res.body();
            return new Reply(res.statusCode(), text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text));
        } catch (Exception e) {
            log.warn("portone call failed: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static String shortReason(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static ApiException unavailable() {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
