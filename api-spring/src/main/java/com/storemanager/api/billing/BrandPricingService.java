package com.storemanager.api.billing;

import com.storemanager.api.billing.PricingDtos.AdminBrandPricingRow;
import com.storemanager.api.billing.PricingDtos.MonthPrice;
import com.storemanager.api.franchise.FranchiseBrand;
import com.storemanager.api.franchise.FranchiseBrandRepository;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가맹 브랜드 구간 단가(V49, 2026-09-30 운영자 결정). 브랜드 소속 유료 이용 매장 수가 많을수록
 * 매장당 단가가 낮아진다({@link PricingTier}). 매월 25일 스냅샷이 다음 달 단가를 확정하고, 단가가
 * 바뀌는 브랜드에는 담당자·사장님에게 미리 메일로 알린다.
 *
 * <p>★ 브랜드가 없는 매장, 약정({@code committedStoreCount})·스냅샷이 모두 없는 브랜드는
 * 기본 단가(30,000원, 1~49 구간)를 쓴다.
 */
@Service
public class BrandPricingService {

    private static final Logger log = LoggerFactory.getLogger(BrandPricingService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final FranchiseBrandRepository brands;
    private final StoreRepository stores;
    private final SubscriptionRepository subscriptions;
    private final BrandMonthlyPriceRepository monthlyPrices;
    private final FranchiseHqMemberRepository hqMembers;
    private final AppUserRepository users;
    private final MailService mail;
    private final String publicOrigin;

    public BrandPricingService(FranchiseBrandRepository brands, StoreRepository stores,
            SubscriptionRepository subscriptions, BrandMonthlyPriceRepository monthlyPrices,
            FranchiseHqMemberRepository hqMembers, AppUserRepository users, MailService mail,
            @Value("${app.public-origin:https://review.sodamlabs.kr}") String publicOrigin) {
        this.brands = brands;
        this.stores = stores;
        this.subscriptions = subscriptions;
        this.monthlyPrices = monthlyPrices;
        this.hqMembers = hqMembers;
        this.users = users;
        this.mail = mail;
        this.publicOrigin = publicOrigin;
    }

    // ── 매장 1건의 청구 단가 ─────────────────────────────────────────────

    /** 결제 대상 월의 매장 단가(부가세 별도). 브랜드가 없으면 기본 단가. */
    public int unitPriceFor(Store store, YearMonth month) {
        String brand = store.getBrandName();
        return brand == null || brand.isBlank() ? PricingTier.DEFAULT_UNIT_PRICE : unitPriceForBrand(brand, month);
    }

    private int unitPriceForBrand(String brand, YearMonth month) {
        return monthlyPrices.findByBrandNameAndBillingMonth(brand, month.atDay(1))
                .map(BrandMonthlyPrice::getUnitPrice)
                .orElseGet(() -> committedOrDefaultUnitPrice(brand));
    }

    private int committedOrDefaultUnitPrice(String brand) {
        return brands.findByBrandName(brand).map(FranchiseBrand::getCommittedStoreCount)
                .map(PricingTier::monthlyPrice).orElse(PricingTier.DEFAULT_UNIT_PRICE);
    }

    // ── 브랜드 유료 매장 수 ──────────────────────────────────────────────

    /** ACTIVE 또는 GRACE 상태인 매장 수(TRIAL·UNPAID·RESTRICTED·SUSPENDED·CANCELED 제외). */
    public int paidStoreCount(String brand) {
        return paidStoreCount(brand, Instant.now());
    }

    // ponytail: 매장별로 Subscription 을 1건씩 조회한다(N+1). 브랜드당 매장 수가 수백 단위인
    // 지금 규모에서는 충분하다 — 수천 단위가 되면 Store~Subscription 조인 쿼리로 바꾼다.
    private int paidStoreCount(String brand, Instant now) {
        int count = 0;
        for (Store s : stores.findByBrandNameAndDeletedAtIsNull(brand)) {
            Subscription sub = subscriptions.findByStoreIdAndStatusNot(s.getId(), "CANCELED").orElse(null);
            if (sub != null && isPaidState(sub.serviceStateAt(now))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPaidState(String state) {
        return "ACTIVE".equals(state) || "GRACE".equals(state);
    }

    // ── 매월 25일 00:05(KST) 스냅샷 ──────────────────────────────────────

    /** 다음 달 단가를 확정한다. 유료 매장이 0인 브랜드는 행을 만들지 않는다(약정 단가 유지). 재실행해도 1행(멱등). */
    @Transactional
    public int snapshotNextMonth(Instant now) {
        LocalDate nextMonth = YearMonth.from(now.atZone(KST)).plusMonths(1).atDay(1);
        int created = 0;
        for (FranchiseBrand brand : brands.findAllByOrderByBrandNameAsc()) {
            if (!brand.isActive() || monthlyPrices.existsByBrandNameAndBillingMonth(brand.getBrandName(), nextMonth)) {
                continue;
            }
            int paid = paidStoreCount(brand.getBrandName(), now);
            if (paid == 0) {
                continue;
            }
            monthlyPrices.save(BrandMonthlyPrice.builder().brandName(brand.getBrandName()).billingMonth(nextMonth)
                    .paidStoreCount(paid).unitPrice(PricingTier.monthlyPrice(paid)).build());
            created++;
        }
        return created;
    }

    // ── 단가 변경 안내 메일(매월 25일 09:00 + 매일 재시도) ─────────────────

    /** 단가가 실제로 바뀐 브랜드에만 메일을 보낸다. 값이 같으면 발송 없이 확인 처리한다. */
    public int sendPriceNotices(Instant now) {
        int sent = 0;
        for (BrandMonthlyPrice row : monthlyPrices.findByNotifiedAtIsNull()) {
            YearMonth month = YearMonth.from(row.getBillingMonth());
            int prevUnit = unitPriceForBrand(row.getBrandName(), month.minusMonths(1));
            if (prevUnit == row.getUnitPrice()) {
                markNotified(row, now);
                continue;
            }
            Set<String> recipients = recipientEmails(row.getBrandName(), now);
            if (recipients.isEmpty()) {
                markNotified(row, now); // 보낼 대상이 없다 — 확인 처리하고 다시 보지 않는다
                continue;
            }
            boolean anySent = false;
            for (String email : recipients) {
                if (mail.sendBrandPriceNotice(email, subject(row), body(row))) {
                    anySent = true;
                }
            }
            if (anySent) {
                markNotified(row, now);
                sent++;
            } else {
                log.warn("브랜드 단가 변경 안내 발송에 모두 실패했습니다(다음 실행에서 재시도) brand={}", row.getBrandName());
            }
        }
        return sent;
    }

    private void markNotified(BrandMonthlyPrice row, Instant now) {
        row.markNotified(now);
        monthlyPrices.save(row);
    }

    /** 수신자: 그 브랜드에서 서비스 중인 매장의 소유자 + 활성 담당자(브랜드도 활성일 때만). */
    private Set<String> recipientEmails(String brand, Instant now) {
        Set<String> emails = new LinkedHashSet<>();
        for (Store s : stores.findByBrandNameAndDeletedAtIsNull(brand)) {
            Subscription sub = subscriptions.findByStoreIdAndStatusNot(s.getId(), "CANCELED").orElse(null);
            if (sub != null && sub.isServiceableAt(now)) {
                addEmail(emails, s.getOwnerId());
            }
        }
        boolean brandActive = brands.findByBrandName(brand).map(FranchiseBrand::isActive).orElse(false);
        if (brandActive) {
            for (FranchiseHqMember member : hqMembers.findByBrandNameOrderByInvitedAtAsc(brand)) {
                if (member.isActive()) {
                    addEmail(emails, member.getUserId());
                }
            }
        }
        return emails;
    }

    private void addEmail(Set<String> emails, Long userId) {
        users.findById(userId).map(AppUser::getEmail).filter(e -> e != null && !e.isBlank()).ifPresent(emails::add);
    }

    private static String subject(BrandMonthlyPrice row) {
        YearMonth m = YearMonth.from(row.getBillingMonth());
        return "[소담리뷰] " + m.getYear() + "년 " + m.getMonthValue() + "월 매장당 이용료 안내";
    }

    private String body(BrandMonthlyPrice row) {
        YearMonth m = YearMonth.from(row.getBillingMonth());
        int total = PricingTier.total(row.getUnitPrice());
        return m.getYear() + "년 " + m.getMonthValue() + "월부터 가맹 브랜드 구간 단가가 적용됩니다.\n\n"
                + "- 매장당 월 이용료: " + String.format("%,d", row.getUnitPrice()) + "원(부가세 포함 "
                + String.format("%,d", total) + "원)\n"
                + "- 매월 25일 기준 유료 이용 매장 " + row.getPaidStoreCount() + "곳\n\n"
                + "결제 화면: " + publicOrigin + "/billing\n";
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    /** 관리자 콘솔 — 브랜드 하나의 지난달·이번달·다음달 단가. */
    @Transactional(readOnly = true)
    public AdminBrandPricingRow summary(String brand, Instant now) {
        FranchiseBrand fb = brands.findByBrandName(brand).orElse(null);
        Integer committed = fb == null ? null : fb.getCommittedStoreCount();
        int paid = paidStoreCount(brand, now);
        YearMonth thisMonth = YearMonth.from(now.atZone(KST));
        MonthPrice last = monthPrice(brand, thisMonth.minusMonths(1), committed, paid, false);
        MonthPrice ths = monthPrice(brand, thisMonth, committed, paid, false);
        MonthPrice next = monthPrice(brand, thisMonth.plusMonths(1), committed, paid, true);
        return new AdminBrandPricingRow(brand, fb == null ? null : fb.getStatus(), committed, paid, last, ths, next);
    }

    /** 관리자 콘솔 — 전체 브랜드 목록. */
    @Transactional(readOnly = true)
    public List<AdminBrandPricingRow> summaryAll(Instant now) {
        return brands.findAllByOrderByBrandNameAsc().stream().map(b -> summary(b.getBrandName(), now)).toList();
    }

    private MonthPrice monthPrice(String brand, YearMonth month, Integer committed, int currentPaid, boolean isNext) {
        var row = monthlyPrices.findByBrandNameAndBillingMonth(brand, month.atDay(1));
        if (row.isPresent()) {
            BrandMonthlyPrice r = row.get();
            return new MonthPrice(month.toString(), r.getUnitPrice(), PricingTier.total(r.getUnitPrice()),
                    r.getPaidStoreCount(), "SNAPSHOT", true);
        }
        if (isNext && currentPaid > 0) {
            int unit = PricingTier.monthlyPrice(currentPaid);
            return new MonthPrice(month.toString(), unit, PricingTier.total(unit), currentPaid, "LIVE_ESTIMATE", false);
        }
        int unit = committed != null ? PricingTier.monthlyPrice(committed) : PricingTier.DEFAULT_UNIT_PRICE;
        String basis = committed != null ? "COMMITTED" : "DEFAULT";
        return new MonthPrice(month.toString(), unit, PricingTier.total(unit), committed, basis, !isNext);
    }
}
