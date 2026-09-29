package com.storemanager.api.billing;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 무료체험 → 유료 전환 사전고지(약관 9.4조 4항). 체험 종료 7일 전부터 한 번, 이메일로 보낸다.
 *
 * <p>★ 발송에 성공했을 때만 {@code trial_notice_sent_at} 을 찍는다. 메일 계정이 없거나 발송이 실패하면
 * 비워 두고 다음 날 다시 시도한다 — "고지했다" 고 기록했는데 실제로는 안 나간 상태가 가장 나쁘다.
 * <p>★ 수신 이메일은 로그·감사로그에 싣지 않는다(절대규칙 5 와 같은 취급).
 * <p>ponytail: 채널은 이메일 하나다. 솔라피 알림톡은 휴대폰 인증 뒤(CLAUDE.md '실운영 전 필수 조치')
 * 이 서비스에서 같은 대상에 함께 보내도록 확장한다.
 */
@Service
public class TrialConversionNoticeService {

    static final Duration NOTICE_BEFORE = Duration.ofDays(7);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
    private static final Logger log = LoggerFactory.getLogger(TrialConversionNoticeService.class);

    private final SubscriptionRepository subscriptions;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final AuditLogRepository auditLogs;
    private final MailService mail;
    private final TransactionTemplate writes;
    private final String publicOrigin;

    public TrialConversionNoticeService(SubscriptionRepository subscriptions, StoreRepository stores,
            AppUserRepository users, AuditLogRepository auditLogs, MailService mail,
            PlatformTransactionManager tx, @Value("${app.public-origin:https://review.sodamlabs.kr}") String publicOrigin) {
        this.subscriptions = subscriptions;
        this.stores = stores;
        this.users = users;
        this.auditLogs = auditLogs;
        this.mail = mail;
        this.writes = new TransactionTemplate(tx);
        this.publicOrigin = publicOrigin;
    }

    /** 고지 대상을 모두 보낸다. 보낸 건수를 돌려준다. 스케줄러와 테스트가 같은 길을 쓴다. */
    public int sendDue(Instant now) {
        int sent = 0;
        for (Subscription sub : subscriptions.findTrialConversionNoticeDue(now, now.plus(NOTICE_BEFORE))) {
            Store store = stores.findById(sub.getStoreId()).orElse(null);
            AppUser owner = store == null ? null : users.findById(store.getOwnerId()).orElse(null);
            if (owner == null || owner.getEmail() == null || owner.getEmail().isBlank()) {
                continue;
            }
            if (!mail.sendTrialConversionNotice(owner.getEmail(), subject(), body(store, sub))) {
                continue;
            }
            Instant sentAt = Instant.now();
            writes.executeWithoutResult(status -> {
                Subscription row = subscriptions.findById(sub.getId()).orElseThrow();
                row.markTrialNoticeSent(sentAt);
                subscriptions.save(row);
                auditLogs.save(AuditLog.builder().actorType("SYSTEM").action("TRIAL_CONVERSION_NOTICE_SENT")
                        .targetType("SUBSCRIPTION").targetId(row.getId()).build());
            });
            sent++;
        }
        if (sent > 0) {
            log.info("유료 전환 사전고지 {}건 발송", sent);
        }
        return sent;
    }

    static String subject() {
        return "[소담리뷰] 무료체험이 곧 끝나고 유료로 전환됩니다";
    }

    /** 고지 필수 항목: 전환 예정일·금액·결제방법·해지 방법(약관 9.4조 3·4항). */
    String body(Store store, Subscription sub) {
        String day = sub.getTrialEndsAt().atZone(KST).format(DATE);
        return store.getName() + " 매장의 30일 무료체험이 " + day + "에 끝납니다.\n\n"
                + "- 유료 전환일(첫 결제일): " + day + "\n"
                + "- 결제 금액: 월 33,000원(부가세 포함)\n"
                + "- 결제 방법: 등록하신 카드로 매월 같은 날 자동결제(KG이니시스)\n"
                + "- 해지 방법: 소담리뷰 결제 화면에서 '자동결제 해지'를 누르면 됩니다. "
                + "체험 종료 전에 해지하면 결제되지 않습니다.\n\n"
                + "결제 화면: " + publicOrigin + "/stores/" + store.getPublicId() + "/billing\n";
    }
}
