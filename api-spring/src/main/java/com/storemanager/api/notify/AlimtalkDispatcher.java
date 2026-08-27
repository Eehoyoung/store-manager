package com.storemanager.api.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.store.StoreRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** notification_log의 신규 고위험 알림만 SOLAPI에 접수한다. */
@Service
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class AlimtalkDispatcher {

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
                } catch (RuntimeException ignored) {
                    // 이미 발송됐다. QUEUED 복구·재발송하지 않고 SENDING 정체 감시가 잡게 둔다.
                }
            } catch (Exception e) {
                // 접수 결과가 모호한 예외를 자동 재시도하면 같은 알림이 두 번 갈 수 있어 종결한다.
                try {
                    transactions.recordFailed(claim.logId());
                } catch (RuntimeException ignored) {
                    // 결과 기록 장애도 다음 행 처리를 막지 않는다. SENDING 정체 감시가 잡는다.
                }
            }
        }
        return accepted;
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
