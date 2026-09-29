package com.storemanager.api.hq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.agreement.UserAgreement;
import com.storemanager.api.agreement.UserAgreementRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.common.PersonalIdentifierMasker;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.hq.HqDtos.CategoryBucket;
import com.storemanager.api.hq.HqDtos.CollectDelayedStoreItem;
import com.storemanager.api.hq.HqDtos.HqAnalyticsResponse;
import com.storemanager.api.hq.HqDtos.HqBrandResponse;
import com.storemanager.api.hq.HqDtos.HqOverviewResponse;
import com.storemanager.api.hq.HqDtos.HqStoreResponse;
import com.storemanager.api.hq.HqDtos.DailyRiskItem;
import com.storemanager.api.hq.HqDtos.IssueTagItem;
import com.storemanager.api.hq.HqDtos.MenuIssueItem;
import com.storemanager.api.hq.HqDtos.PlatformLinkStatus;
import com.storemanager.api.hq.HqDtos.PriorityStoreItem;
import com.storemanager.api.hq.HqDtos.RatingBucket;
import com.storemanager.api.hq.HqDtos.ReportResponse;
import com.storemanager.api.hq.HqDtos.ReportTotals;
import com.storemanager.api.hq.HqDtos.RiskClusterItem;
import com.storemanager.api.hq.HqDtos.StoreComparisonItem;
import com.storemanager.api.hq.HqReviewDtos.ReviewDetailResponse;
import com.storemanager.api.hq.HqReviewDtos.ReviewItem;
import com.storemanager.api.hq.HqReviewDtos.ReviewListResponse;
import com.storemanager.api.review.ReviewQueryRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.store.Store;
import com.storemanager.api.user.AppUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가맹본부 조회 서비스 (Sprint 8, FR-802·804 · docs/26a 본부 홈·개별 리뷰·보고서 확장).
 * ★ 조회 전용이다 — 이 클래스에 쓰기 메서드를 추가하지 않는다(H8). 기존 서비스의 쓰기 메서드도 호출하지 않는다.
 * ★ 모든 조회는 HqAccessGuard 로 접근통제를 거치고, 반드시 AuditLog 를 남긴다(H7, FR-805).
 * ★ 집계는 HqQueryRepository 의 DB 쿼리 결과를 조립만 한다 — 매장별 반복 쿼리(N+1) 없음.
 */
@Service
public class HqService {

    private static final ZoneId KST = PublishScheduleCalculator.KST;
    private static final int RECENT_DAYS = 30;
    private static final int DEFAULT_ANALYTICS_RANGE_DAYS = 30;
    private static final int OVERVIEW_TREND_DAYS = 7;
    /** 수집 지연 판정 — 하루 1회 폴링(10시 KST)이 이틀 연속 실패하면 지연으로 본다. */
    private static final int COLLECT_DELAY_DAYS = 2;
    private static final int REVIEW_MAX_LOOKBACK_DAYS = 90;
    private static final int REVIEW_EXCERPT_LENGTH = 80;

    static final int HQ_MAX_LOOKBACK_DAYS = 90;
    static final long MIN_AGGREGATION_THRESHOLD = 5;

    private final HqAccessGuard hqAccessGuard;
    private final FranchiseHqMemberRepository hqMemberRepository;
    private final HqQueryRepository hqQueryRepository;
    private final ReviewQueryRepository reviewQueryRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final HqReviewAccessProperties reviewAccessProperties;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public HqService(HqAccessGuard hqAccessGuard, FranchiseHqMemberRepository hqMemberRepository,
            HqQueryRepository hqQueryRepository, ReviewQueryRepository reviewQueryRepository,
            UserAgreementRepository userAgreementRepository, HqReviewAccessProperties reviewAccessProperties,
            AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.hqAccessGuard = hqAccessGuard;
        this.hqMemberRepository = hqMemberRepository;
        this.hqQueryRepository = hqQueryRepository;
        this.reviewQueryRepository = reviewQueryRepository;
        this.userAgreementRepository = userAgreementRepository;
        this.reviewAccessProperties = reviewAccessProperties;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /** FR-801 — 본부 권한이 없으면 빈 배열(403 아님). 조회 대상이 곧 "내 권한 목록"이라 감사로그는 남기지 않는다. */
    @Transactional(readOnly = true)
    public List<HqBrandResponse> listBrands(UUID userPublicId) {
        AppUser user = hqAccessGuard.resolveUser(userPublicId);
        List<String> brandNames = hqMemberRepository.findBrandNamesByUserId(user.getId());
        if (brandNames.isEmpty()) {
            return List.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : hqQueryRepository.countStoresByBrandNames(brandNames)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return brandNames.stream().map(b -> new HqBrandResponse(b, counts.getOrDefault(b, 0L))).toList();
    }

    /** FR-802 — 가맹점 목록 + 운영 상태. ★ 감사로그 INSERT 를 같은 트랜잭션에서 하므로 readOnly 를 걸지 않는다. */
    @Transactional
    public List<HqStoreResponse> listStores(UUID userPublicId, String brandName) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        audit(user, "HQ_STORES_VIEW", "BRAND", null, brandName);
        return computeStoreRows(brandName);
    }

    /** docs/26a endpoints.hq — 본부 홈 요약. */
    @Transactional
    public HqOverviewResponse overview(UUID userPublicId, String brandName) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        audit(user, "HQ_OVERVIEW_VIEW", "BRAND", null, brandName);

        List<HqStoreResponse> stores = computeStoreRows(brandName);
        long storeCount = stores.size();
        long serviceActive = stores.stream().filter(s -> "IN_SERVICE".equals(s.serviceStatus())).count();
        long suspended = storeCount - serviceActive;
        long unlinked = stores.stream().filter(s -> s.platformLinks().isEmpty()).count();

        Instant staleBefore = Instant.now().minus(COLLECT_DELAY_DAYS, ChronoUnit.DAYS);
        List<CollectDelayedStoreItem> delayed = stores.stream()
                .filter(s -> s.activated() && (s.lastCollectedAt() == null
                        || Instant.parse(s.lastCollectedAt()).isBefore(staleBefore)))
                .map(s -> new CollectDelayedStoreItem(s.storeId(), s.name(), s.lastCollectedAt()))
                .toList();

        long pendingTotal = stores.stream().mapToLong(s -> nz(s.pendingCount())).sum();
        long blockedTotal = stores.stream().mapToLong(s -> nz(s.blockedCount())).sum();
        long highRiskTotal = stores.stream().mapToLong(s -> nz(s.highRiskCount())).sum();

        List<Long> storeIds = storeIdsOf(brandName);
        Instant to = Instant.now();
        Instant from = to.minus(OVERVIEW_TREND_DAYS, ChronoUnit.DAYS);
        Instant previousFrom = from.minus(OVERVIEW_TREND_DAYS, ChronoUnit.DAYS);
        List<IssueTagItem> rising;
        double coverage;
        String dataAsOf;
        if (storeIds.isEmpty()) {
            rising = List.of();
            coverage = 0;
            dataAsOf = null;
        } else {
            long total = ((Number) hqQueryRepository.brandReviewCountAndAvgRating(storeIds, from, to).get(0)[0])
                    .longValue();
            long analyzed = hqQueryRepository.analyzedReviewCount(storeIds, from, to);
            coverage = total == 0 ? 0 : round4((double) analyzed / total);
            rising = topIssues(storeIds, from, to, previousFrom, from, analyzed,
                    hqQueryRepository.analyzedReviewCount(storeIds, previousFrom, from)).stream()
                    .filter(i -> "NEW".equals(i.signal()) || "RISING".equals(i.signal()))
                    // ★ 최소 집계 기준(WP-02)은 본부 홈에서도 동일하게 지킨다 — signal 판정은
                    //   count>=3 부터지만 기준은 5 다. 3~4건은 신호는 뜨되 수치는 가린다.
                    .filter(i -> !revealsIndividual(i.count()) && !revealsIndividual(i.previousCount()))
                    .limit(5)
                    .map(i -> new IssueTagItem(i.tag(), i.count(), i.previousCount(), i.rate(), i.previousRate(),
                            i.delta(), i.affectedStores(), i.avgRating(), i.signal(), false))
                    .toList();
            dataAsOf = hqQueryRepository.lastCollectedAtByStore(storeIds).stream().map(row -> (Instant) row[1])
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).map(Instant::toString)
                    .orElse(null);
        }

        List<PriorityStoreItem> priority = stores.stream()
                .filter(s -> nz(s.highRiskCount()) > 0 || nz(s.blockedCount()) > 0 || nz(s.pendingCount()) > 0)
                .sorted(Comparator
                        .comparingLong((HqStoreResponse s) -> nz(s.highRiskCount())).reversed()
                        .thenComparing(Comparator.comparingLong((HqStoreResponse s) -> nz(s.pendingCount())).reversed()))
                .limit(10)
                .map(s -> new PriorityStoreItem(s.storeId(), s.name(), priorityReason(s), s.highRiskCount(),
                        s.pendingCount()))
                .toList();

        return new HqOverviewResponse(storeCount, serviceActive, suspended, unlinked, delayed, pendingTotal,
                blockedTotal, highRiskTotal, rising, coverage, dataAsOf, priority);
    }

    public HqAnalyticsResponse analytics(UUID userPublicId, String brandName, String fromStr, String toStr) {
        return analytics(userPublicId, brandName, fromStr, toStr, null);
    }

    /** FR-804 — 브랜드 집계(별점·카테고리 분포, 이슈 태그 랭킹, 매장별 비교). storeIdFilter(선택)는 docs/26a analytics 필터. */
    @Transactional
    public HqAnalyticsResponse analytics(UUID userPublicId, String brandName, String fromStr, String toStr,
            UUID storeIdFilter) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        audit(user, "HQ_ANALYTICS_VIEW", "BRAND", null, brandName);
        List<Store> stores = storeIdFilter == null ? hqQueryRepository.findStoresByBrandName(brandName)
                : List.of(hqAccessGuard.requireStoreInBrand(storeIdFilter, brandName));
        return analyticsData(stores, fromStr, toStr);
    }

    /** docs/26a endpoints.hq — 인쇄용/CSV 보고서. 개별 리뷰 원문은 절대 포함하지 않는다. */
    @Transactional
    public ReportResponse report(UUID userPublicId, String brandName, String fromStr, String toStr) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        audit(user, "HQ_REPORT_VIEW", "BRAND", null, brandName);
        List<Store> stores = hqQueryRepository.findStoresByBrandName(brandName);
        HqAnalyticsResponse data = analyticsData(stores, fromStr, toStr);
        return new ReportResponse(data.from() + " ~ " + data.to(), data.previousFrom() + " ~ " + data.previousTo(),
                data.dataAsOf(), data.analysisCoverageRate(),
                new ReportTotals(data.totalReviews(), data.analyzedReviews(), data.avgRating(),
                        data.highRiskReviews()),
                data.storeComparison(), data.issueTagRanking());
    }

    // ── docs/26a endpoints.hq — 개별 리뷰 (플래그 게이트) ──────────────────

    @Transactional
    public ReviewListResponse listReviews(UUID userPublicId, String brandName, String fromStr, String toStr,
            UUID storeIdFilter, String platform, Integer rating, String replyStatus, Integer riskLevel,
            UUID cursor, int size) {
        requireReviewFeature();
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("size", "size는 1 이상 100 이하여야 합니다."));
        }
        List<Store> brandStores = storeIdFilter == null ? hqQueryRepository.findStoresByBrandName(brandName)
                : List.of(hqAccessGuard.requireStoreInBrand(storeIdFilter, brandName));
        List<Store> consented = brandStores.stream().filter(this::reviewAccessAllowed).toList();
        audit(user, "HQ_REVIEWS_LIST_VIEW", "BRAND", null, brandName);
        if (consented.isEmpty()) {
            return new ReviewListResponse(List.of(), null);
        }
        Map<Long, Store> storeById = new HashMap<>();
        for (Store s : consented) {
            storeById.put(s.getId(), s);
        }
        List<Long> storeIds = new ArrayList<>(storeById.keySet());

        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(RECENT_DAYS - 1L));
        long rangeDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (rangeDays <= 0 || rangeDays > REVIEW_MAX_LOOKBACK_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    Map.of("range", "조회 기간은 최근 " + REVIEW_MAX_LOOKBACK_DAYS + "일 이내여야 합니다."));
        }
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        UnifiedReview boundary = cursor == null ? null : reviewQueryRepository.findByPublicId(cursor)
                .filter(r -> storeById.containsKey(r.getStoreId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        List<UnifiedReview> fetched = hqQueryRepository.hqReviewSearch(storeIds, blankToNull(platform),
                toShort(rating), toShort(riskLevel), blankToNull(replyStatus), from, to,
                boundary == null ? OPEN_END : boundary.getWrittenAt(), boundary == null ? Long.MAX_VALUE : boundary.getId(),
                PageRequest.of(0, size + 1));

        boolean hasMore = fetched.size() > size;
        List<UnifiedReview> page = hasMore ? fetched.subList(0, size) : fetched;
        List<Long> reviewIds = page.stream().map(UnifiedReview::getId).toList();
        Map<Long, ReviewAnalysis> analysisByReviewId = new HashMap<>();
        Map<Long, ReplyDraft> draftByReviewId = new HashMap<>();
        if (!reviewIds.isEmpty()) {
            for (ReviewAnalysis a : reviewQueryRepository.findAnalysesByReviewIds(reviewIds)) {
                analysisByReviewId.put(a.getReviewId(), a);
            }
            for (ReplyDraft d : reviewQueryRepository.findLatestDraftsByReviewIds(reviewIds)) {
                draftByReviewId.put(d.getReviewId(), d);
            }
        }
        List<ReviewItem> items = page.stream()
                .map(r -> toReviewItem(r, storeById.get(r.getStoreId()), analysisByReviewId.get(r.getId()),
                        draftByReviewId.get(r.getId())))
                .toList();
        String nextCursor = hasMore ? page.get(page.size() - 1).getPublicId().toString() : null;
        return new ReviewListResponse(items, nextCursor);
    }

    @Transactional
    public ReviewDetailResponse getReview(UUID userPublicId, String brandName, UUID reviewPublicId) {
        requireReviewFeature();
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        UnifiedReview review = reviewQueryRepository.findByPublicId(reviewPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Store store = hqQueryRepository.findById(review.getStoreId())
                .filter(s -> brandName.equals(s.getBrandName()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!reviewAccessAllowed(store)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        audit(user, "HQ_REVIEW_DETAIL_VIEW", "UNIFIED_REVIEW", review.getId(), brandName);

        ReviewAnalysis analysis = reviewQueryRepository.findAnalysesByReviewIds(List.of(review.getId())).stream()
                .findFirst().orElse(null);
        ReplyDraft draft = reviewQueryRepository.findLatestDraftsByReviewIds(List.of(review.getId())).stream()
                .findFirst().orElse(null);
        ReviewItem summary = toReviewItem(review, store, analysis, draft);
        String maskedBody = PersonalIdentifierMasker.mask(review.getBody());
        return new ReviewDetailResponse(summary.reviewId(), summary.storeName(), summary.platform(),
                summary.writtenAt(), summary.rating(), summary.excerpt(), summary.authorDisplay(),
                summary.category(), summary.issueTags(), summary.riskLevel(), summary.riskReasons(),
                summary.replyStatus(), maskedBody);
    }

    private ReviewItem toReviewItem(UnifiedReview r, Store store, ReviewAnalysis analysis, ReplyDraft draft) {
        String maskedBody = PersonalIdentifierMasker.mask(r.getBody());
        String excerpt = maskedBody == null ? "" : maskedBody.substring(0, Math.min(maskedBody.length(),
                REVIEW_EXCERPT_LENGTH));
        return new ReviewItem(r.getPublicId().toString(), store == null ? null : store.getName(), r.getPlatform(),
                toIso(r.getWrittenAt()), toInt(r.getRating()), excerpt, r.getAuthorMasked(),
                analysis == null ? null : analysis.getCategory(),
                analysis == null ? List.of() : List.of(analysis.getIssueTags()),
                analysis == null ? null : (int) analysis.getRiskLevel(),
                analysis == null ? List.of() : List.of(analysis.getRiskReasons()),
                draft == null ? "NONE" : draft.getStatus());
    }

    /** docs/26 §4.2 5조건 중 owner 동의 관련 2개 — 나머지(세션·멤버·브랜드·매장소속·플래그)는 앞단에서 확인한다. */
    private boolean reviewAccessAllowed(Store store) {
        Long ownerId = store.getOwnerId();
        UserAgreement consent = userAgreementRepository
                .findTopByUserIdAndAgreementCodeOrderByCreatedAtDesc(ownerId, AgreementService.HQ_REVIEW_SHARING)
                .orElse(null);
        if (consent == null || !consent.isAgreed()) {
            return false;
        }
        return !auditLogRepository.existsByActionAndActorIdAndCreatedAtAfter(
                "HQ_AFFILIATION_WITHDRAWAL_REQUESTED", ownerId, consent.getCreatedAt());
    }

    private void requireReviewFeature() {
        if (!reviewAccessProperties.isEnabled()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    // ── 내부 — 매장 목록 계산(listStores/overview 공용) ─────────────────────

    private List<HqStoreResponse> computeStoreRows(String brandName) {
        List<Store> stores = hqQueryRepository.findStoresByBrandName(brandName);
        if (stores.isEmpty()) {
            return List.of();
        }
        List<Long> storeIds = stores.stream().map(Store::getId).toList();

        Map<Long, List<PlatformLinkStatus>> linksByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.platformLinkStatuses(storeIds)) {
            Long storeId = ((Number) row[0]).longValue();
            linksByStore.computeIfAbsent(storeId, k -> new ArrayList<>())
                    .add(new PlatformLinkStatus((String) row[1], (String) row[2]));
        }

        Map<Long, String> subStatusByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.subscriptionStatuses(storeIds)) {
            subStatusByStore.put(((Number) row[0]).longValue(), (String) row[1]);
        }

        Map<Long, Instant> lastCollectedByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.lastCollectedAtByStore(storeIds)) {
            lastCollectedByStore.put(((Number) row[0]).longValue(), (Instant) row[1]);
        }

        Instant recentFrom = LocalDate.now(KST).minusDays(RECENT_DAYS - 1L).atStartOfDay(KST).toInstant();
        Map<Long, Object[]> recentStatsByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.recentReviewStatsByStore(storeIds, recentFrom)) {
            recentStatsByStore.put(((Number) row[0]).longValue(), new Object[] {row[1], row[2]});
        }
        Map<Long, Object[]> allTimeStatsByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.allTimeReviewStatsByStore(storeIds)) {
            allTimeStatsByStore.put(((Number) row[0]).longValue(), new Object[] {row[1], row[2]});
        }

        Map<Long, Map<String, Long>> draftStatusByStore = draftStatusCountsByStore(storeIds);
        Map<Long, Long> highRiskByStore = highRiskCountsByStore(storeIds);

        return stores.stream().map(store -> {
            Long id = store.getId();
            Map<String, Long> statusCounts = draftStatusByStore.getOrDefault(id, Map.of());
            long pending = statusCounts.getOrDefault("DRAFT", 0L);
            long blocked = statusCounts.getOrDefault("BLOCKED", 0L);
            long highRisk = highRiskByStore.getOrDefault(id, 0L);
            Object[] recent = recentStatsByStore.get(id);
            long recentCount = recent == null ? 0L : ((Number) recent[0]).longValue();
            Double recentAvg = recent == null || recent[1] == null ? null : ((Number) recent[1]).doubleValue();

            boolean pendingBelow = revealsIndividual(pending);
            boolean blockedBelow = revealsIndividual(blocked);
            boolean highRiskBelow = revealsIndividual(highRisk);
            boolean recentBelow = revealsIndividual(recentCount);
            boolean belowThreshold = pendingBelow || blockedBelow || highRiskBelow || recentBelow;

            List<PlatformLinkStatus> links = linksByStore.getOrDefault(id, List.of());
            String linkStatus = overallLinkStatus(links);
            Object[] allTime = allTimeStatsByStore.get(id);
            long allTimeCount = allTime == null ? 0L : ((Number) allTime[0]).longValue();
            Double allTimeAvg = allTime == null || allTime[1] == null ? null : ((Number) allTime[1]).doubleValue();
            long publishedLike = statusCounts.getOrDefault("PUBLISHED", 0L)
                    + statusCounts.getOrDefault("ALREADY_REPLIED", 0L);
            boolean allTimeBelow = revealsIndividual(allTimeCount);
            Double replyRate = allTimeBelow || allTimeCount == 0 ? null
                    : round4((double) publishedLike / allTimeCount);

            return new HqStoreResponse(store.getPublicId().toString(), store.getName(), store.getAddress(),
                    store.getActivatedAt() != null, toServiceStatus(subStatusByStore.get(id)), links,
                    toIso(lastCollectedByStore.get(id)),
                    pendingBelow ? null : pending, blockedBelow ? null : blocked,
                    highRiskBelow ? null : highRisk, recentBelow ? null : recentCount,
                    recentBelow ? null : recentAvg, belowThreshold,
                    linkStatus, allTimeBelow ? null : allTimeCount, allTimeBelow ? null : allTimeAvg, replyRate);
        }).toList();
    }

    private List<Long> storeIdsOf(String brandName) {
        return hqQueryRepository.findStoresByBrandName(brandName).stream().map(Store::getId).toList();
    }

    private static String overallLinkStatus(List<PlatformLinkStatus> links) {
        if (links.isEmpty()) {
            return "NONE";
        }
        if (links.stream().anyMatch(l -> "ERROR".equals(l.linkStatus()))) {
            return "ERROR";
        }
        if (links.stream().anyMatch(l -> "LINKED".equals(l.linkStatus()))) {
            return "LINKED";
        }
        return links.get(0).linkStatus();
    }

    private static String priorityReason(HqStoreResponse s) {
        if (nz(s.highRiskCount()) > 0) {
            return "고위험 리뷰 " + s.highRiskCount() + "건";
        }
        if (nz(s.blockedCount()) > 0) {
            return "차단된 초안 " + s.blockedCount() + "건";
        }
        return "검수 대기 " + s.pendingCount() + "건";
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    // ── 내부 — analytics 데이터 조립(analytics/report 공용, 감사로그 없음) ────

    private HqAnalyticsResponse analyticsData(List<Store> stores, String fromStr, String toStr) {
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_ANALYTICS_RANGE_DAYS - 1L));
        LocalDate earliest = LocalDate.now(KST).minusDays(HQ_MAX_LOOKBACK_DAYS - 1L);
        if (fromDate.isBefore(earliest)) {
            fromDate = earliest;
        }
        long rangeDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (rangeDays <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    Map.of("from", fromDate.toString(), "to", toDate.toString()));
        }
        LocalDate previousToDate = fromDate.minusDays(1);
        LocalDate previousFromDate = previousToDate.minusDays(rangeDays - 1);
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();
        Instant previousFrom = previousFromDate.atStartOfDay(KST).toInstant();
        Instant previousTo = from;

        if (stores.isEmpty()) {
            return new HqAnalyticsResponse(fromDate.toString(), toDate.toString(), previousFromDate.toString(),
                    previousToDate.toString(), null, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<Long> storeIds = stores.stream().map(Store::getId).toList();

        Object[] countAvg = hqQueryRepository.brandReviewCountAndAvgRating(storeIds, from, to).get(0);
        long total = ((Number) countAvg[0]).longValue();
        Double avgRating = countAvg[1] == null ? null : round1(((Number) countAvg[1]).doubleValue());
        long analyzed = hqQueryRepository.analyzedReviewCount(storeIds, from, to);
        long previousAnalyzed = hqQueryRepository.analyzedReviewCount(storeIds, previousFrom, previousTo);
        double analysisCoverageRate = total == 0 ? 0 : round4((double) analyzed / total);

        String dataAsOf = hqQueryRepository.lastCollectedAtByStore(storeIds).stream()
                .map(row -> (Instant) row[1]).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder())
                .map(Instant::toString).orElse(null);

        List<RatingBucket> ratingDist = hqQueryRepository.brandRatingDistribution(storeIds, from, to).stream()
                .map(row -> new RatingBucket(((Number) row[0]).intValue(), ((Number) row[1]).longValue())).toList();
        List<CategoryBucket> categoryDist = hqQueryRepository.brandCategoryDistribution(storeIds, from, to).stream()
                .map(row -> new CategoryBucket((String) row[0], ((Number) row[1]).longValue())).toList();

        List<RawIssueTag> rawIssueTags = topIssues(storeIds, from, to, previousFrom, previousTo, analyzed,
                previousAnalyzed);
        long issueTagsBelowThreshold = rawIssueTags.stream()
                .filter(i -> revealsIndividual(i.count()) || revealsIndividual(i.previousCount())).count();
        List<IssueTagItem> issueTags = rawIssueTags.stream().map(i -> {
            boolean below = revealsIndividual(i.count()) || revealsIndividual(i.previousCount());
            return new IssueTagItem(i.tag(), below ? null : i.count(), below ? null : i.previousCount(),
                    below ? null : i.rate(), below ? null : i.previousRate(), below ? null : i.delta(),
                    below ? null : i.affectedStores(), below ? null : i.avgRating(),
                    below ? "BELOW_THRESHOLD" : i.signal(), below);
        }).toList();

        Map<String, Object[]> currentRisks = rowsByKey(hqQueryRepository.brandRiskClusters(storeIds, from, to));
        Map<String, Object[]> previousRisks = rowsByKey(
                hqQueryRepository.brandRiskClusters(storeIds, previousFrom, previousTo));
        Set<String> allReasons = new LinkedHashSet<>(currentRisks.keySet());
        allReasons.addAll(previousRisks.keySet());
        List<RawRiskCluster> rawRiskClusters = allReasons.stream()
                .map(reason -> new RawRiskCluster(reason, number(currentRisks.get(reason), 1),
                        number(previousRisks.get(reason), 1), number(currentRisks.get(reason), 2)))
                .sorted(Comparator.comparingLong(RawRiskCluster::count).reversed()
                        .thenComparing(RawRiskCluster::reason))
                .toList();
        long riskClustersBelowThreshold = rawRiskClusters.stream()
                .filter(r -> revealsIndividual(r.count()) || revealsIndividual(r.previousCount())).count();
        List<RiskClusterItem> riskClusters = rawRiskClusters.stream().map(r -> {
            boolean below = revealsIndividual(r.count()) || revealsIndividual(r.previousCount());
            return new RiskClusterItem(r.reason(), below ? null : r.count(), below ? null : r.previousCount(),
                    below ? null : r.affectedStores(), below);
        }).toList();

        Object[] highRiskSummary = hqQueryRepository.brandHighRiskSummary(storeIds, from, to).get(0);
        long highRiskReviews = ((Number) highRiskSummary[0]).longValue();
        long highRiskAffectedStores = ((Number) highRiskSummary[1]).longValue();

        List<Object[]> rawMenuRows = hqQueryRepository.brandMenuIssues(storeIds, from, to);
        long menuIssuesBelowThreshold = rawMenuRows.stream()
                .filter(row -> revealsIndividual(((Number) row[2]).longValue())).count();
        List<MenuIssueItem> menuIssues = rawMenuRows.stream().map(row -> {
            long count = ((Number) row[2]).longValue();
            boolean below = revealsIndividual(count);
            long affected = ((Number) row[3]).longValue();
            Double avg = row[4] == null ? null : round1(((Number) row[4]).doubleValue());
            return new MenuIssueItem((String) row[0], (String) row[1], below ? null : count,
                    below ? null : affected, below ? null : avg, below);
        }).toList();

        List<Object[]> rawDailyRows = hqQueryRepository.brandDailyRiskTrend(storeIds, from, to);
        long dailyRiskBelowThreshold = rawDailyRows.stream().filter(row -> {
            long issueCount = ((Number) row[2]).longValue();
            long highRisk = ((Number) row[3]).longValue();
            return revealsIndividual(issueCount) || revealsIndividual(highRisk);
        }).count();
        List<DailyRiskItem> dailyRiskTrend = rawDailyRows.stream().map(row -> {
            long analyzedCount = ((Number) row[1]).longValue();
            long issueCount = ((Number) row[2]).longValue();
            long highRisk = ((Number) row[3]).longValue();
            boolean issueBelow = revealsIndividual(issueCount);
            boolean highRiskBelow = revealsIndividual(highRisk);
            return new DailyRiskItem(row[0].toString(), analyzedCount, issueBelow ? null : issueCount,
                    highRiskBelow ? null : highRisk, issueBelow || highRiskBelow);
        }).toList();

        Map<Long, Object[]> periodStatsByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.perStorePeriodReviewStats(storeIds, from, to)) {
            periodStatsByStore.put(((Number) row[0]).longValue(), new Object[] {row[1], row[2]});
        }
        Map<Long, Map<String, Long>> periodDraftStatusByStore = new HashMap<>();
        for (Object[] row : hqQueryRepository.perStorePeriodDraftStatusCounts(storeIds, from, to)) {
            Long storeId = ((Number) row[0]).longValue();
            periodDraftStatusByStore.computeIfAbsent(storeId, k -> new HashMap<>())
                    .put((String) row[1], ((Number) row[2]).longValue());
        }
        Map<Long, Map<String, Long>> allTimeDraftStatusByStore = draftStatusCountsByStore(storeIds);
        Map<Long, Long> highRiskByStore = highRiskCountsByStore(storeIds);

        List<StoreComparisonItem> comparison = stores.stream().map(store -> {
            Long id = store.getId();
            Object[] periodStats = periodStatsByStore.get(id);
            long reviewCount = periodStats == null ? 0L : ((Number) periodStats[0]).longValue();
            Double avg = periodStats == null || periodStats[1] == null ? null
                    : round1(((Number) periodStats[1]).doubleValue());
            Map<String, Long> periodStatus = periodDraftStatusByStore.getOrDefault(id, Map.of());
            long publishedLike = periodStatus.getOrDefault("PUBLISHED", 0L)
                    + periodStatus.getOrDefault("ALREADY_REPLIED", 0L);
            double completionRate = reviewCount == 0 ? 0.0 : round4((double) publishedLike / reviewCount);
            Map<String, Long> allTimeStatus = allTimeDraftStatusByStore.getOrDefault(id, Map.of());
            long unprocessed = allTimeStatus.getOrDefault("DRAFT", 0L) + allTimeStatus.getOrDefault("BLOCKED", 0L)
                    + highRiskByStore.getOrDefault(id, 0L);
            boolean reviewBelow = revealsIndividual(reviewCount);
            boolean unprocessedBelow = revealsIndividual(unprocessed);
            boolean below = reviewBelow || unprocessedBelow;
            return new StoreComparisonItem(store.getPublicId().toString(), store.getName(),
                    reviewBelow ? null : reviewCount,
                    reviewBelow ? null : avg,
                    reviewBelow ? null : completionRate,
                    unprocessedBelow ? null : unprocessed,
                    below);
        }).toList();

        return new HqAnalyticsResponse(fromDate.toString(), toDate.toString(), previousFromDate.toString(),
                previousToDate.toString(), dataAsOf, total, analyzed, analysisCoverageRate, avgRating,
                highRiskReviews, highRiskAffectedStores, issueTagsBelowThreshold, riskClustersBelowThreshold,
                menuIssuesBelowThreshold, dailyRiskBelowThreshold, ratingDist, categoryDist, issueTags, riskClusters,
                menuIssues, dailyRiskTrend, comparison);
    }

    private List<RawIssueTag> topIssues(List<Long> storeIds, Instant from, Instant to, Instant previousFrom,
            Instant previousTo, long analyzed, long previousAnalyzed) {
        Map<String, Object[]> currentIssues = rowsByKey(hqQueryRepository.brandIssueTagStats(storeIds, from, to));
        Map<String, Object[]> previousIssues = rowsByKey(
                hqQueryRepository.brandIssueTagStats(storeIds, previousFrom, previousTo));
        Set<String> allTags = new LinkedHashSet<>(currentIssues.keySet());
        allTags.addAll(previousIssues.keySet());
        return allTags.stream().map(tag -> {
            Object[] current = currentIssues.get(tag);
            Object[] previous = previousIssues.get(tag);
            long count = number(current, 1);
            long previousCount = number(previous, 1);
            long affectedStores = number(current, 2);
            Double rate = ratePer100(count, analyzed);
            Double previousRate = ratePer100(previousCount, previousAnalyzed);
            Double delta = rate == null || previousRate == null ? null : round1(rate - previousRate);
            Double issueAvgRating = current == null || current[3] == null ? null
                    : round1(((Number) current[3]).doubleValue());
            return new RawIssueTag(tag, count, previousCount, affectedStores, rate, previousRate, delta,
                    issueAvgRating, issueSignal(count, previousCount, affectedStores, delta));
        }).sorted(Comparator.comparingInt((RawIssueTag i) -> signalPriority(i.signal()))
                .thenComparing(RawIssueTag::count, Comparator.reverseOrder()).thenComparing(RawIssueTag::tag))
                .toList();
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private record RawIssueTag(String tag, long count, long previousCount, long affectedStores, Double rate,
            Double previousRate, Double delta, Double avgRating, String signal) {
    }

    private record RawRiskCluster(String reason, long count, long previousCount, long affectedStores) {
    }

    private static boolean revealsIndividual(long count) {
        return count > 0 && count < MIN_AGGREGATION_THRESHOLD;
    }

    private Map<Long, Map<String, Long>> draftStatusCountsByStore(List<Long> storeIds) {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        for (Object[] row : hqQueryRepository.latestDraftStatusCountsByStore(storeIds)) {
            Long storeId = ((Number) row[0]).longValue();
            result.computeIfAbsent(storeId, k -> new HashMap<>()).put((String) row[1], ((Number) row[2]).longValue());
        }
        return result;
    }

    private Map<Long, Long> highRiskCountsByStore(List<Long> storeIds) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : hqQueryRepository.highRiskPendingCountsByStore(storeIds)) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }

    private static String toServiceStatus(String subscriptionStatus) {
        if (subscriptionStatus == null) {
            return "IN_SERVICE";
        }
        return switch (subscriptionStatus) {
            case "SUSPENDED", "CANCELED" -> "SUSPENDED";
            default -> "IN_SERVICE";
        };
    }

    /** FR-805 — 본부의 모든 조회를 감사로그에 남긴다. 리뷰 본문·작성자 정보는 절대 넣지 않는다(H7). */
    private void audit(AppUser user, String action, String targetType, Long targetId, String brandNameDetail) {
        String detail = null;
        if (brandNameDetail != null) {
            try {
                detail = objectMapper.writeValueAsString(Map.of("brandName", brandNameDetail));
            } catch (JsonProcessingException e) {
                detail = null;
            }
        }
        auditLogRepository.save(AuditLog.builder().actorId(user.getId()).actorType("HQ").action(action)
                .targetType(targetType).targetId(targetId).detail(detail).build());
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static Map<String, Object[]> rowsByKey(List<Object[]> rows) {
        Map<String, Object[]> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], row);
        }
        return result;
    }

    private static long number(Object[] row, int index) {
        return row == null || row[index] == null ? 0 : ((Number) row[index]).longValue();
    }

    private static Double ratePer100(long count, long analyzed) {
        return analyzed == 0 ? null : round1((double) count * 100 / analyzed);
    }

    private static String issueSignal(long count, long previousCount, long affectedStores, Double deltaRatePoints) {
        if (count >= 3 && affectedStores >= 2 && previousCount == 0) {
            return "NEW";
        }
        if (count >= 3 && affectedStores >= 2 && deltaRatePoints != null && deltaRatePoints >= 5) {
            return "RISING";
        }
        if (deltaRatePoints != null && deltaRatePoints <= -5) {
            return "FALLING";
        }
        return "STABLE";
    }

    private static int signalPriority(String signal) {
        return switch (signal) {
            case "NEW" -> 0;
            case "RISING" -> 1;
            case "STABLE" -> 2;
            default -> 3;
        };
    }

    private static LocalDate parseOrDefault(String s, LocalDate fallback) {
        return s == null || s.isBlank() ? fallback : LocalDate.parse(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Short toShort(Integer v) {
        return v == null ? null : v.shortValue();
    }

    private static Integer toInt(Short v) {
        return v == null ? null : v.intValue();
    }

    // ★ 기간 필터는 null 을 넘기지 않고 넓은 경계값으로 대체한다(ReviewService 와 같은 이유 — Postgres 가
    //   null 타임스탬프 파라미터의 타입을 추론하지 못해 500 이 난다).
    private static final Instant OPEN_END = LocalDate.of(9999, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
}
