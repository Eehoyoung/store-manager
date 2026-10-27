package com.storemanager.api.notify;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 미납 해지 절차(T-30) 알림톡. 알림톡이 꺼져 있으면 이 빈이 없다(호출부는 ObjectProvider 로 받는다).
 * 템플릿 ID 가 비어 있으면 보내지 않고 false. 수신번호는 로그에 남기지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
public class DunningAlimtalk {

    public enum Kind { NOTICE, REMINDER, TERMINATED }

    private static final Logger log = LoggerFactory.getLogger(DunningAlimtalk.class);
    private final SolapiSender sender;
    private final AlimtalkProperties props;

    DunningAlimtalk(SolapiSender sender, AlimtalkProperties props) {
        this.sender = sender;
        this.props = props;
    }

    /** 접수 성공 시 true. 실패·템플릿 미설정은 false. */
    public boolean send(Kind kind, String phone, Map<String, String> variables) {
        String templateId = switch (kind) {
            case NOTICE -> props.getTemplate().getDunningNotice();
            case REMINDER -> props.getTemplate().getDunningReminder();
            case TERMINATED -> props.getTemplate().getDunningTerminated();
        };
        if (templateId.isBlank() || phone == null || phone.isBlank()) {
            return false;
        }
        try {
            sender.sendTemplate(templateId, phone.replaceAll("[^0-9]", ""), variables);
            return true;
        } catch (Exception e) {
            log.warn("미납 해지 알림톡 접수 실패 kind={} {}", kind, e.getClass().getSimpleName());
            return false;
        }
    }
}
