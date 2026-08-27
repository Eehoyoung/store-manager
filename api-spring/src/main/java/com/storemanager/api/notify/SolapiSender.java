package com.storemanager.api.notify;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.dto.response.MultipleDetailMessageSentResponse;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.model.kakao.KakaoOption;
import com.solapi.sdk.message.service.DefaultMessageService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 공식 SOLAPI SDK에 알림톡 한 건을 접수한다. 최종 전달 성공은 웹훅에서만 확정한다. */
@Component
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class SolapiSender {

    private final DefaultMessageService messageService;
    private final AlimtalkProperties properties;

    SolapiSender(AlimtalkProperties properties) {
        this.properties = properties;
        this.messageService = SolapiClient.INSTANCE.createInstance(properties.getApiKey(), properties.getApiSecret());
    }

    SendResult sendHighRisk(String to, Map<String, String> variables) throws Exception {
        KakaoOption kakao = new KakaoOption();
        kakao.setPfId(properties.getPfId());
        kakao.setTemplateId(properties.getTemplate().getRiskReview());
        kakao.setDisableSms(true);
        kakao.setVariables(templateVariables(variables));

        Message message = new Message();
        message.setTo(to);
        if (!properties.getSenderPhone().isBlank()) {
            message.setFrom(digits(properties.getSenderPhone()));
        }
        message.setKakaoOptions(kakao);

        MultipleDetailMessageSentResponse response = messageService.send(message);
        if (response.getMessageList() == null || response.getMessageList().isEmpty()
                || response.getMessageList().get(0).getMessageId() == null) {
            throw new IllegalStateException("SOLAPI가 메시지 ID를 반환하지 않았습니다.");
        }
        var sent = response.getMessageList().get(0);
        return new SendResult(sent.getMessageId(), sent.getStatusCode());
    }

    private static Map<String, String> templateVariables(Map<String, String> variables) {
        Map<String, String> result = new LinkedHashMap<>();
        variables.forEach((key, value) -> result.put(key.startsWith("#{") ? key : "#{" + key + "}", value));
        return result;
    }

    static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    record SendResult(String messageId, String statusCode) {
    }
}
