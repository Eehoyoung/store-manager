package com.storemanager.api.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.legal.BusinessInfoProperties;
import com.storemanager.api.notify.DunningAlimtalk;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미납 해지 절차(T-30, 대표 결정 D120). 이용 제한(결제예정일 D+3 00:00 KST) 이후에도 결제가 안 된 매장에
 * (a) 시정 요구 통지 + 해지일 확정(발송일 + 14일) (b) 해지 3일 전 재통지 (c) 해지일 도래 시 해지·빌링키 삭제·배달앱 자격증명 파기
 * 를 순서대로 한다. 미납 요금은 청구하지 않는다.
 *
 * <p>★ 잡 전체가 {@code app.billing.dunning-notify-enabled}(기본 false) 뒤에 있다. 통지가 실제로 나가지 않은 채
 * 해지하면 약관 위반이므로, 꺼짐이면 대상 건수만 로그로 남기고 아무것도 바꾸지 않는다.
 * <p>★ (a)·(b)는 메일 또는 알림톡 중 하나라도 접수돼야 기록한다. 둘 다 실패하면 비워 두고 다음 날 다시 시도한다.
 * <p>매장별 트랜잭션(CancelCredentialPurgeService 와 같은 패턴). 수신 이메일·전화번호는 로그에 싣지 않는다.
 * <p>ponytail: 알림톡 메시지 ID 는 기록하지 않는다(접수 여부만 감사 detail 에). 필요하면 DunningAlimtalk 가 ID 를 돌려주게 확장.
 */
@Service
public class DunningService {

    static final int TERMINATION_DAYS = 14;
    static final int REMIND_BEFORE_DAYS = 3;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final SubscriptionRepository subscriptions;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final AuditLogRepository auditLogs;
    private final MailService mail;
    private final ObjectProvider<DunningAlimtalk> alimtalk;
    private final DowngradeCredentialCleanup cleanup;
    private final PortOneClient portOneClient;
    private final BrandPricingService brandPricingService;
    private final BusinessInfoProperties business;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final PaymentRepository payments;
    private final boolean enabled;

    public DunningService(SubscriptionRepository subscriptions, StoreRepository stores, AppUserRepository users,
            AuditLogRepository auditLogs, MailService mail, ObjectProvider<DunningAlimtalk> alimtalk,
            DowngradeCredentialCleanup cleanup, PortOneClient portOneClient, BrandPricingService brandPricingService,
            BusinessInfoProperties business, ObjectMapper objectMapper, TransactionTemplate tx, PaymentRepository payments,
            @Value("${app.billing.dunning-notify-enabled:false}") boolean enabled) {
        this.subscriptions = subscriptions;
        this.stores = stores;
        this.users = users;
        this.auditLogs = auditLogs;
        this.mail = mail;
        this.alimtalk = alimtalk;
        this.cleanup = cleanup;
        this.portOneClient = portOneClient;
        this.brandPricingService = brandPricingService;
        this.business = business;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.payments = payments;
        this.enabled = enabled;
    }

    /** 처리한 건수(통지·해지 각 1건). 꺼짐이면 0. */
    public int run(Instant now) {
        var targets = subscriptions.findDunningTargets(now);
        if (!enabled) {
            log.info("미납 해지 절차 꺼짐(app.billing.dunning-notify-enabled=false) - 대상 {}건은 처리하지 않는다", targets.size());
            return 0;
        }
        int done = 0;
        LocalDate today = now.atZone(KST).toLocalDate();
        for (Subscription s : targets) {
            try {
                if (process(s.getId(), now, today)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("미납 해지 절차 실패 subscriptionId={} {}", s.getId(), e.getClass().getSimpleName());
            }
        }
        return done;
    }

    private boolean process(Long subId, Instant now, LocalDate today) {
        Subscription sub = subscriptions.findById(subId).orElse(null);
        if (sub == null || !eligible(sub, now)) {
            return false; // 목록을 읽은 뒤 결제·해지 접수·운영자 조치로 대상에서 빠졌다
        }
        // termination_due_date 는 '기한'이다: 이날까지 결제하면 유지, 해지 실행은 기한 다음 날부터(today > 기한).
        LocalDate due = sub.getTerminationDueDate();
        if (due != null && today.isAfter(due) && sub.getTerminationRemindedAt() != null) {
            return terminate(sub, now, today);
        }
        if (sub.getDunningNotifiedAt() == null) {
            return notifyStage(sub, Stage.NOTICE, now, today);
        }
        // 재통지 창은 기한-3 <= today. 재통지 없이 기한이 지났으면 여기서 재통지하고 기한을 늦춘다(해지는 재통지 뒤에만).
        if (sub.getTerminationRemindedAt() == null && !today.isBefore(due.minusDays(REMIND_BEFORE_DAYS))) {
            return notifyStage(sub, Stage.REMINDER, now, today);
        }
        return false;
    }

    /** findDunningTargets 와 같은 조건 + 결과를 모르는(PENDING) 결제가 없을 것. */
    private boolean eligible(Subscription s, Instant now) {
        return stillDunning(s, now) && !payments.existsBySubscriptionIdAndStatus(s.getId(), "PENDING");
    }

    private static boolean stillDunning(Subscription s, Instant now) {
        return s.getCancellationRequestedAt() == null && s.isAutoRenew() && s.getRenewalFailures() > 0
                && !"CANCELED".equals(s.getStatus()) && !"SUSPENDED".equals(s.getStatus())
                && s.getServiceUntil() != null && !now.isBefore(s.getServiceUntil());
    }

    /**
     * 청구 잠금 아래에서 구독을 다시 읽는다. 결제가 진행 중(잠금 중)이거나 그새 대상에서 빠졌으면 null.
     * 잠금 UPDATE 의 행 잠금은 이 트랜잭션이 끝날 때까지 유지돼 동시 결제와 겹치지 않는다. until=now 라
     * 잠금 값은 커밋 즉시 만료된 것과 같다(별도 해제 불필요).
     */
    private Subscription lockAndReread(Subscription sub, Instant now) {
        if (subscriptions.lockBilling(sub.getStoreId(), now, now) == 0) {
            return null;
        }
        Subscription row = subscriptions.findById(sub.getId()).orElse(null);
        return row != null && eligible(row, now) ? row : null;
    }

    private enum Stage { NOTICE, REMINDER, TERMINATED }

    /** (a)·(b) 통지. 한 채널이라도 접수되면 기록한다. 기록 직전에 잠금 아래에서 다시 판정한다. */
    private boolean notifyStage(Subscription sub, Stage stage, Instant now, LocalDate today) {
        Ctx c = ctx(sub);
        if (c == null) {
            return false;
        }
        LocalDate due;
        if (stage == Stage.NOTICE) {
            due = today.plusDays(TERMINATION_DAYS);
        } else {
            // 재통지가 기한-3 보다 늦게 나가면 기한을 (재통지 발송일 + 3일)로 늦춘다.
            due = today.isAfter(sub.getTerminationDueDate().minusDays(REMIND_BEFORE_DAYS))
                    ? today.plusDays(REMIND_BEFORE_DAYS) : sub.getTerminationDueDate();
        }
        Map<String, Object> result = send(c, stage, due);
        boolean ok = Boolean.TRUE.equals(result.get("mail")) || Boolean.TRUE.equals(result.get("alimtalk"));
        Boolean recorded = tx.execute(st -> {
            Subscription row = lockAndReread(sub, now);
            if (row == null) {
                return false; // 발송 사이에 결제 등으로 빠졌다 - 해지일을 남기지 않는다
            }
            if (ok) {
                if (stage == Stage.NOTICE) {
                    row.markDunningNotified(now);
                } else {
                    row.markTerminationReminded(now, due);
                }
                subscriptions.save(row);
            }
            audit(ok ? "DUNNING_NOTICE_SENT" : "DUNNING_NOTICE_FAILED", row.getId(), result);
            return ok;
        });
        return Boolean.TRUE.equals(recorded);
    }

    /** (c) 기한 다음 날: 해지·빌링키 삭제·자격증명 파기·감사·해지 통지. 해지일은 실제 실행일(today). */
    private boolean terminate(Subscription sub, Instant now, LocalDate today) {
        Ctx c = ctx(sub);
        String[] keyHolder = new String[1];
        Boolean done = tx.execute(st -> {
            Subscription row = lockAndReread(sub, now);
            if (row == null || row.getTerminationRemindedAt() == null || row.getTerminationDueDate() == null
                    || !today.isAfter(row.getTerminationDueDate())) {
                return false;
            }
            keyHolder[0] = row.terminateForNonPayment();
            subscriptions.save(row);
            cleanup.onRemoved(row.getStoreId(), new HashSet<>(Subscription.DELIVERY_PLATFORMS),
                    "CREDENTIAL_REVOKED_ON_TERMINATION");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("unpaidAmountKrw", c == null ? null : c.amount);
            detail.put("terminationDate", today.toString());
            detail.put("billedUnpaid", false);
            audit("SUBSCRIPTION_TERMINATED_BY_COMPANY", row.getId(), detail);
            return true;
        });
        if (!Boolean.TRUE.equals(done)) {
            return false;
        }
        // 빌링키 삭제는 PG 호출이라 커밋 뒤에 한다. 실패해도 해지는 유지(탈퇴와 같은 규율) - 감사에는 사유 코드만.
        if (keyHolder[0] != null) {
            try {
                portOneClient.deleteBillingKey(keyHolder[0]);
            } catch (RuntimeException e) {
                log.warn("미납 해지 시 포트원 빌링키 삭제 실패: {}", e.getClass().getSimpleName());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("storeId", sub.getStoreId());
                detail.put("reason", e.getClass().getSimpleName());
                tx.executeWithoutResult(st -> audit("BILLING_KEY_DELETE_FAILED", sub.getId(), detail));
            }
        }
        if (c != null) {
            Map<String, Object> result = send(c, Stage.TERMINATED, today);
            boolean ok = Boolean.TRUE.equals(result.get("mail")) || Boolean.TRUE.equals(result.get("alimtalk"));
            // 재발송은 하지 않는다 - 해지는 유지, 실패 사실만 남긴다.
            tx.executeWithoutResult(st -> audit(ok ? "DUNNING_NOTICE_SENT" : "DUNNING_NOTICE_FAILED", sub.getId(), result));
        }
        return true;
    }

    private record Ctx(Store store, AppUser owner, int amount) {
    }

    private Ctx ctx(Subscription sub) {
        Store store = stores.findById(sub.getStoreId()).orElse(null);
        AppUser owner = store == null ? null : users.findById(store.getOwnerId()).orElse(null);
        if (owner == null) {
            return null;
        }
        Instant base = sub.getNextBillingAt() != null ? sub.getNextBillingAt() : Instant.now();
        int amount = brandPricingService.quoteFor(store, YearMonth.from(base.atZone(KST)), sub).total();
        return new Ctx(store, owner, amount);
    }

    /** 메일 + 알림톡 발송. 채널별 접수 여부를 돌려준다(주소·번호는 싣지 않는다). */
    private Map<String, Object> send(Ctx c, Stage stage, LocalDate due) {
        String amount = String.format("%,d", c.amount);
        String day = due.format(DATE);
        String contact = contact();
        String name = c.store.getName();
        String body = switch (stage) {
            case NOTICE -> notice(name, amount, day, contact);
            case REMINDER -> reminder(name, amount, day, contact);
            case TERMINATED -> terminated(name, amount, day, contact);
        };
        boolean mailOk = false;
        String email = c.owner.getEmail();
        if (email != null && !email.isBlank()) {
            mailOk = mail.sendDunningNotice(email, body.lines().findFirst().orElse(""), body);
        }
        boolean talkOk = false;
        DunningAlimtalk talk = alimtalk.getIfAvailable();
        if (talk != null && c.owner.getPhoneVerifiedAt() != null) {
            talkOk = talk.send(switch (stage) {
                case NOTICE -> DunningAlimtalk.Kind.NOTICE;
                case REMINDER -> DunningAlimtalk.Kind.REMINDER;
                case TERMINATED -> DunningAlimtalk.Kind.TERMINATED;
            }, c.owner.getPhone(), Map.of("storeName", name, "amount", amount, "terminationDate", day));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage.name());
        result.put("mail", mailOk);
        result.put("alimtalk", talkOk);
        result.put("terminationDate", due.toString());
        return result;
    }

    private String contact() {
        if (!business.getEmail().isBlank()) {
            return business.getEmail();
        }
        return business.getPhone().isBlank() ? "소담리뷰 고객센터" : business.getPhone();
    }

    private void audit(String action, Long subId, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        auditLogs.save(AuditLog.builder().actorType("SYSTEM").action(action).targetType("SUBSCRIPTION")
                .targetId(subId).detail(json).build());
    }

    // 메일 제목은 각 본문의 첫 줄이다(D120 문구 그대로).
    static String notice(String store, String amount, String day, String contact) {
        return "[소담리뷰] " + store + " 이용요금 " + amount + "원(VAT 포함) 결제가 되지 않았어요.\n"
                + "지금은 리뷰 수집과 답글 자동 게시가 멈춘 상태예요. 결제관리에서 카드를 바꾸면 바로 다시 쓸 수 있어요.\n"
                + day + "까지 결제가 안 되면 이용계약이 해지되고, 등록하신 배달앱 계정정보는 그날 바로 지워져요.\n"
                + "해지되더라도 밀린 요금은 청구하지 않아요. 문의: " + contact;
    }

    static String reminder(String store, String amount, String day, String contact) {
        return "[소담리뷰] " + store + " 아직 " + amount + "원 결제가 확인되지 않았어요.\n"
                + day + "까지 결제가 안 되면 이용계약이 해지돼요. 결제관리에서 카드를 바꾸면 해지가 취소돼요.\n"
                + "해지되면 배달앱 계정정보는 바로 지워지고, 밀린 요금은 청구하지 않아요.\n"
                + "문의: " + contact;
    }

    static String terminated(String store, String amount, String day, String contact) {
        return "[소담리뷰] " + store + " 이용계약이 오늘(" + day + ") 해지되었어요.\n"
                + "자동결제는 종료되었고 등록된 카드 정보와 배달앱 계정정보는 모두 지웠어요. 밀린 " + amount + "원은 청구하지 않아요.\n"
                + "다시 쓰시려면 카드를 다시 등록하고 배달앱 계정을 다시 연동해 주세요.\n"
                + "문의: " + contact;
    }
}
