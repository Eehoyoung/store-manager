package com.storemanager.api.notify;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 휴대폰 확인 인증번호 SMS 발송(솔라피 계정은 알림톡과 공유).
 *
 * <p>★ 켜면 실제 고객 휴대폰으로 문자가 나간다. 켜기는 대표 결정이라 기본 false.
 * <p>★ 켜 놓고 자격증명이 비면 기동을 막는다(AlimtalkProperties 와 같은 fail-closed). 값은 메시지에 싣지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.phone-verify.enabled", havingValue = "true")
public class SolapiSmsSender {

    private final DefaultMessageService messageService;
    private final String from;

    public SolapiSmsSender(AlimtalkProperties properties) {
        List<String> missing = new ArrayList<>();
        if (properties.getApiKey().isBlank()) {
            missing.add("SOLAPI_API_KEY");
        }
        if (properties.getApiSecret().isBlank()) {
            missing.add("SOLAPI_API_SECRET");
        }
        if (properties.getSenderPhone().isBlank()) {
            missing.add("SOLAPI_SENDER_PHONE");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("PHONE_VERIFY_ENABLED=true 인데 다음 값이 비어 있습니다: "
                    + String.join(", ", missing) + ". 기동을 중단합니다.");
        }
        this.messageService = SolapiClient.INSTANCE.createInstance(properties.getApiKey(), properties.getApiSecret());
        this.from = digits(properties.getSenderPhone());
    }

    /** 문자 한 건 접수. 실패는 예외 - 호출부가 번호 끝 4자리만 로그로 남긴다. */
    public void send(String to, String text) throws Exception {
        Message message = new Message();
        // ponytail: type 을 지정하지 않으면 솔라피가 길이로 SMS/LMS 를 고른다(문안이 짧아 SMS).
        message.setFrom(from);
        message.setTo(digits(to));
        message.setText(text);
        var response = messageService.send(message);
        if (response.getMessageList() == null || response.getMessageList().isEmpty()
                || response.getMessageList().get(0).getMessageId() == null) {
            throw new IllegalStateException("SOLAPI가 메시지 ID를 반환하지 않았습니다.");
        }
    }

    private static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }
}
