package com.storemanager.api.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.store.StoreRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** notification_log의 신규 고위험 알림만 SOLAPI에 접수한다. */
@Service
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class AlimtalkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlimtalkDispatcher.class);

    /**
     * 승인된 카카오 템플릿 변수와 반드시 함께 검토한다. 리뷰 본문·작성자·전화번호·주소는
     * 어떤 경우에도 이 집합에 추가하지 않는다.
     */
    private static final Set<String> ALLOWED_VARIABLES = Set.of("storeName", "reviewUrl");
    private static final int MAX_PER_CYCLE = 20;
    private final AlimtalkDispatchTransactions transactions;
    private final StoreRepository stores;
    private final SolapiSender sender;
    private final ObjectMapper objectMapper;
    private final AlimtalkProperties properties;

    AlimtalkDispatcher(AlimtalkDispatchTransactions transactions, StoreRepository stores,
            SolapiSender sender, ObjectMapper objectMapper, AlimtalkProperties properties) {
        this.transactions = transactions;
        this.stores = stores;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 건별 실패를 흡수해 다른 매장의 발송을 계속한다. */
    int dispatchDue() {
        int accepted = 0;
        for (int i = 0; i < MAX_PER_CYCLE; i++) {
            var claimed = transactions.claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            var claim = claimed.orElseThrow();
            if (!claim.sendable()) {
                continue;
            }
            try {
                SolapiSender.SendResult result = sender.sendHighRisk(claim.phone(), variables(claim));
                accepted++;
                try {
                    transactions.recordAccepted(claim.logId(), result.messageId());
                } catch (RuntimeException e) {
                    // 이미 발송됐다. QUEUED 복구·재발송하지 않고 SENDING 정체 감시가 잡게 둔다.
                    // messageId 를 남기는 이유 — 이 행은 SENDING 으로 굳으므로 SOLAPI 콘솔에서
                    // 실제 전달 여부를 확인할 유일한 단서다.
                    log.warn("알림톡 발송 후 결과 기록 실패 logId={} messageId={} {}: {}",
                            claim.logId(), result.messageId(), e.getClass().getSimpleName(),
                            maskPhones(e.getMessage()));
                }
            } catch (Exception e) {
                // 접수 결과가 모호한 예외를 자동 재시도하면 같은 알림이 두 번 갈 수 있어 종결한다.
                // ★ DB 에는 고정 문구만 남기므로(수신번호 유출 방지) 원인은 여기서만 알 수 있다.
                //   이 줄이 없으면 미승인 템플릿·잘못된 키·pfId 오류·네트워크 장애가
                //   운영자 화면에서 전부 SOLAPI_ACCEPT_FAILED 한 줄로 똑같이 보인다.
                log.warn("알림톡 접수 실패 logId={} {}: {}", claim.logId(),
                        e.getClass().getSimpleName(), maskPhones(e.getMessage()));
                try {
                    transactions.recordFailed(claim.logId());
                } catch (RuntimeException ignored) {
                    // 결과 기록 장애도 다음 행 처리를 막지 않는다. SENDING 정체 감시가 잡는다.
                }
            }
        }
        return accepted;
    }

    /**
     * SOLAPI 오류 문구에 수신번호가 섞여 오는 경우가 있어 로그에 남기기 전에 가린다(절대규칙 6 준용).
     *
     * <p>★ 휴대폰 형태만 좁게 잡는다. {@code \d{10,}} 같은 넓은 패턴을 쓰면 날짜·메시지ID 까지
     * 가려져 정작 원인 추적이 안 된다.
     */
    static String maskPhones(String message) {
        return message == null ? "" : message.replaceAll("01[0-9][-. ]?[0-9]{3,4}[-. ]?[0-9]{4}", "***");
    }

    private Map<String, String> variables(AlimtalkDispatchTransactions.Claim claim) {
        Map<String, String> vars = new LinkedHashMap<>();
        try {
            var payload = objectMapper.readTree(claim.payload());
            for (String key : ALLOWED_VARIABLES) {
                if (payload.hasNonNull(key) && payload.get(key).isTextual()) {
                    vars.put(key, payload.get(key).textValue());
                }
            }
        } catch (Exception ignored) {
            // 과거 빈/깨진 payload는 민감정보를 추정하지 않고 안전한 변수만 새로 채운다.
        }
        if (claim.storeId() != null) {
            stores.findById(claim.storeId()).ifPresent(store -> {
                vars.put("storeName", store.getName());
                vars.put("reviewUrl", properties.getLinkBaseUrl() + "/stores/" + store.getPublicId()
                        + "/reviews?riskLevel=3");
            });
        }
        return vars;
    }
}
