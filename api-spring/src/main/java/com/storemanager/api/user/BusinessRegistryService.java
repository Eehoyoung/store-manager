package com.storemanager.api.user;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 국세청 진위확인. 원문 응답과 대표자명은 로그에 남기지 않는다. */
@Service
public class BusinessRegistryService {
    private static final Logger log = LoggerFactory.getLogger(BusinessRegistryService.class);
    private static final String URL = "https://api.odcloud.kr/api/nts-businessman/v1/validate";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final boolean enabled;
    private final String serviceKey;
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public BusinessRegistryService(@Value("${app.business-verification.enabled:true}") boolean enabled,
            @Value("${app.business-verification.service-key:}") String serviceKey) {
        this(enabled, serviceKey, URI.create(URL), HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    BusinessRegistryService(boolean enabled, String serviceKey, URI base, HttpClient http) {
        this.enabled = enabled;
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.endpoint = URI.create(base + "?serviceKey="
                + URLEncoder.encode(this.serviceKey, StandardCharsets.UTF_8) + "&returnType=JSON");
        this.http = http;
    }

    void verify(String rawNumber, String rawDate, String rawName) {
        if (!enabled) return;
        if (serviceKey.isEmpty()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        String number = rawNumber == null ? "" : rawNumber.replace("-", "").trim();
        String date = rawDate == null ? "" : rawDate.replaceAll("[-.\\s]", "");
        String name = rawName == null ? "" : rawName.trim();
        if (!number.matches("[0-9]{10}") || name.isEmpty() || name.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    Map.of("reason", "사업자등록번호와 대표자명을 확인해 주세요."));
        }
        try {
            if (!LocalDate.parse(date, DATE).format(DATE).equals(date)) throw new DateTimeParseException("", date, 0);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("reason", "개업일자를 YYYYMMDD 형식으로 입력해 주세요."));
        }

        try {
            String payload = json.writeValueAsString(Map.of("businesses",
                    List.of(Map.of("b_no", number, "start_dt", date, "p_nm", name))));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("사업자 진위확인 응답 상태: {}", response.statusCode());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            JsonNode first = json.readTree(response.body()).path("data").path(0);
            if (first.isMissingNode()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            if (!"01".equals(first.path("valid").asText())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        Map.of("reason", "사업자등록번호·개업일자·대표자명이 국세청 정보와 일치하지 않아요."));
            }
            String state = first.path("status").path("b_stt_cd").asText();
            if (!"01".equals(state)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        Map.of("reason", "계속사업자만 가입할 수 있어요."));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("사업자 진위확인 실패: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
