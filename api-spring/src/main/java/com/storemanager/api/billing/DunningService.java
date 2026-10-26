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
    private final boolean enabled;

    public DunningService(SubscriptionRepository subscriptions, StoreRepository stores, AppUserRepository users,
            AuditLogRepository auditLogs, MailService mail, ObjectProvider<DunningAlimtalk> alimtalk,
            DowngradeCredentialCleanup cleanup, PortOneClient portOneClient, BrandPricingService brandPricingService,
            BusinessInfoProperties business, ObjectMapper objectMapper, TransactionTemplate tx,
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
        if (sub == null || !stillDunning(sub, now)) {
            return false; // 목록을 읽은 뒤 결제·해지 접수·운영자 조치로 대상에서 빠졌다
        }
        LocalDate due = sub.getTerminationDueDate();
        if (due != null && !today.isBefore(due)) {
            return terminate(sub, due);
        }
        if (sub.getDunningNotifiedAt() == null) {
            return notifyStage(sub, Stage.NOTICE, now);
        }
        if (sub.getTerminationRemindedAt() == null && !today.isBefore(due.minusDays(REMIND_BEFORE_DAYS))) {
            return notifyStage(sub, Stage.REMINDER, now);
        }
        return false;
    }

    /** findDunningTargets 와 같은 조건. 해지 직전에 한 번 더 본다. */
    private static boolean stillDunning(Subscription s, Instant now) {
        return s.getCancellationRequestedAt() == null && s.isAutoRenew() && s.getRenewalFailures() > 0
                && !"CANCELED".equals(s.getStatus()) && !"SUSPENDED".equals(s.getStatus())
                && s.getServiceUntil() != null && !now.isBefore(s.getServiceUntil());
    }

    private enum Stage { NOTICE, REMINDER, TERMINATED }

    /** (a)·(b) 통지. 한 채널이라도 접수되면 기록한다. */
    private boolean notifyStage(Subscription sub, Stage stage, Instant now) {
        Ctx c = ctx(sub);
        if (c == null) {
            return false;
        }
        LocalDate due = stage == Stage.NOTICE ? now.atZone(KST).toLocalDate().plusDays(TERMINATION_DAYS)
                : sub.getTerminationDueDate();
        Map<String, Object> result = send(c, stage, due);
        boolean ok = Boolean.TRUE.equals(result.get("mail")) || Boolean.TRUE.equals(result.get("alimtalk"));
        tx.executeWithoutResult(st -> {
            Subscription row = subscriptions.findById(sub.getId()).orElseThrow();
            if (ok) {
                if (stage == Stage.NOTICE) {
                    row.markDunningNotified(now);
                } else {
                    row.markTerminationReminded(now);
                }
                subscriptions.save(row);
            }
            audit(ok ? "DUNNING_NOTICE_SENT" : "DUNNING_NOTICE_FAILED", row.getId(), result);
        });
        return ok;
    }

    /** (c) 해지일 도래: 해지·빌링키 삭제·자격증명 파기·감사·해지 통지. */
    private boolean terminate(Subscription sub, LocalDate due) {
        Ctx c = ctx(sub);
        String[] keyHolder = new String[1];
        tx.executeWithoutResult(st -> {
            Subscription row = subscriptions.findById(sub.getId()).orElseThrow();
            keyHolder[0] = row.terminateForNonPayment();
            subscriptions.save(row);
            cleanup.onRemoved(row.getStoreId(), new HashSet<>(Subscription.DELIVERY_PLATFORMS),
                    "CREDENTIAL_REVOKED_ON_TERMINATION");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("unpaidAmountKrw", c == null ? null : c.amount);
            detail.put("terminationDate", due.toString());
            detail.put("billedUnpaid", false);
            audit("SUBSCRIPTION_TERMINATED_BY_COMPANY", row.getId(), detail);
        });
        // 빌링키 삭제는 PG 호출이라 커밋 뒤에 한다. 실패해도 해지는 유지(탈퇴와 같은 규율).
        if (keyHolder[0] != null) {
            try {
                portOneClient.deleteBillingKey(keyHolder[0]);
            } catch (RuntimeException e) {
                log.warn("미납 해지 시 포트원 빌링키 삭제 실패: {}", e.getClass().getSimpleName());
            }
        }
        if (c != null) {
            Map<String, Object> result = send(c, Stage.TERMINATED, due);
            tx.executeWithoutResult(st -> audit("DUNNING_NOTICE_SENT", sub.getId(), result));
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
        return "[소담리뷰] " + store + " 이용계약이 " + day + "에 해지될 예정이에요.\n"
                + "아직 " + amount + "원 결제가 확인되지 않았어요. 결제관리에서 카드를 바꾸면 해지가 취소돼요.\n"
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
