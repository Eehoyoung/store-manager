package com.storemanager.api.hq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.draft.ReplyDraft;
import com.storemanager.api.draft.ReviewAnalysis;
import com.storemanager.api.hq.HqDtos.CategoryBucket;
import com.storemanager.api.hq.HqDtos.HqAnalysisResponse;
import com.storemanager.api.hq.HqDtos.HqAnalyticsResponse;
import com.storemanager.api.hq.HqDtos.HqBrandResponse;
import com.storemanager.api.hq.HqDtos.HqDraftSummaryResponse;
import com.storemanager.api.hq.HqDtos.HqReviewItem;
import com.storemanager.api.hq.HqDtos.HqReviewListResponse;
import com.storemanager.api.hq.HqDtos.HqStoreResponse;
import com.storemanager.api.hq.HqDtos.IssueTagItem;
import com.storemanager.api.hq.HqDtos.PlatformLinkStatus;
import com.storemanager.api.hq.HqDtos.RatingBucket;
import com.storemanager.api.hq.HqDtos.StoreComparisonItem;
import com.storemanager.api.review.ReviewQueryRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가맹본부 조회 서비스 (Sprint 8, FR-802~804).
 * ★ 조회 전용이다 — 이 클래스에 쓰기 메서드를 추가하지 않는다(H8). 기존 서비스의 쓰기 메서드도 호출하지 않는다.
 * ★ 모든 조회는 HqAccessGuard 로 접근통제를 거치고, 반드시 AuditLog 를 남긴다(H7, FR-805).
 * ★ 집계는 HqQueryRepository 의 DB 쿼리 결과를 조립만 한다 — 매장별 반복 쿼리(N+1) 없음.
 */
@Service
public class HqService {

    private static final ZoneId KST = PublishScheduleCalculator.KST;
    private static final int RECENT_DAYS = 30;
    private static final int DEFAULT_ANALYTICS_RANGE_DAYS = 30;

    private final HqAccessGuard hqAccessGuard;
    private final FranchiseHqMemberRepository hqMemberRepository;
    private final HqQueryRepository hqQueryRepository;
    private final ReviewQueryRepository reviewQueryRepository;
    private final StoreRepository storeRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public HqService(HqAccessGuard hqAccessGuard, FranchiseHqMemberRepository hqMemberRepository,
            HqQueryRepository hqQueryRepository, ReviewQueryRepository reviewQueryRepository,
            StoreRepository storeRepository, AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.hqAccessGuard = hqAccessGuard;
        this.hqMemberRepository = hqMemberRepository;
        this.hqQueryRepository = hqQueryRepository;
        this.reviewQueryRepository = reviewQueryRepository;
        this.storeRepository = storeRepository;
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

        Map<Long, Map<String, Long>> draftStatusByStore = draftStatusCountsByStore(storeIds);
        Map<Long, Long> highRiskByStore = highRiskCountsByStore(storeIds);

        return stores.stream().map(store -> {
            Long id = store.getId();
            Map<String, Long> statusCounts = draftStatusByStore.getOrDefault(id, Map.of());
            Object[] recent = recentStatsByStore.get(id);
            long recentCount = recent == null ? 0L : ((Number) recent[0]).longValue();
            Double recentAvg = recent == null || recent[1] == null ? null : ((Number) recent[1]).doubleValue();
            return new HqStoreResponse(store.getPublicId().toString(), store.getName(), store.getAddress(),
                    store.getActivatedAt() != null, toServiceStatus(subStatusByStore.get(id)),
                    linksByStore.getOrDefault(id, List.of()), toIso(lastCollectedByStore.get(id)),
                    statusCounts.getOrDefault("DRAFT", 0L), statusCounts.getOrDefault("BLOCKED", 0L),
                    highRiskByStore.getOrDefault(id, 0L), recentCount, recentAvg);
        }).toList();
    }

    /** FR-803 — 브랜드 전체 리뷰 통합 조회. ★ 감사로그 INSERT 를 같은 트랜잭션에서 하므로 readOnly 를 걸지 않는다. */
    @Transactional
    public HqReviewListResponse listReviews(UUID userPublicId, String brandName, UUID storePublicId,
            Integer minRating, Integer maxRating, String category, Integer riskLevel, String status, String from,
            String to, int page, int size) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);

        List<Long> storeIds;
        if (storePublicId != null) {
            Store store = hqAccessGuard.requireStoreInBrand(storePublicId, brandName); // ★ H6-2
            storeIds = List.of(store.getId());
            audit(user, "HQ_REVIEWS_VIEW", "STORE", store.getId(), null);
        } else {
            storeIds = hqQueryRepository.findStoresByBrandName(brandName).stream().map(Store::getId).toList();
            audit(user, "HQ_REVIEWS_VIEW", "BRAND", null, brandName);
        }
        if (storeIds.isEmpty()) {
            return new HqReviewListResponse(List.of(), false);
        }

        Page<UnifiedReview> result = hqQueryRepository.searchBrandReviews(storeIds, blankToNull(status),
                blankToNull(category), toShort(minRating), toShort(maxRating), toShort(riskLevel),
                parseFromDate(from), parseToDateExclusive(to), PageRequest.of(page, size));

        List<UnifiedReview> reviews = result.getContent();
        List<Long> reviewIds = reviews.stream().map(UnifiedReview::getId).toList();
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

        // ★ 페이지 전체에 대해 매장명 조회는 1회뿐 — 리뷰마다 반복 조회하지 않는다(N+1 방지).
        Map<Long, Store> storeById = new HashMap<>();
        for (Store s : storeRepository.findAllById(reviews.stream().map(UnifiedReview::getStoreId).distinct().toList())) {
            storeById.put(s.getId(), s);
        }

        List<HqReviewItem> items = reviews.stream().map(r -> {
            Store s = storeById.get(r.getStoreId());
            return new HqReviewItem(String.valueOf(r.getId()), s == null ? null : s.getPublicId().toString(),
                    s == null ? null : s.getName(), r.getPlatform(), toInt(r.getRating()), r.getBody(),
                    r.getAuthorMasked(), parseStringList(r.getOrderedMenus()), parseStringList(r.getImageUrls()),
                    toIso(r.getWrittenAt()), r.isWrittenDateOnly(), toIso(r.getCollectedAt()), r.isHasOwnerReply(),
                    toAnalysisResponse(analysisByReviewId.get(r.getId())),
                    toDraftSummary(draftByReviewId.get(r.getId())));
        }).toList();

        return new HqReviewListResponse(items, result.hasNext());
    }

    /** FR-804 — 브랜드 집계(별점·카테고리 분포, 이슈 태그 랭킹, 매장별 비교). ★ 감사로그 INSERT 때문에 readOnly 를 걸지 않는다. */
    @Transactional
    public HqAnalyticsResponse analytics(UUID userPublicId, String brandName, String fromStr, String toStr) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        audit(user, "HQ_ANALYTICS_VIEW", "BRAND", null, brandName);

        List<Store> stores = hqQueryRepository.findStoresByBrandName(brandName);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_ANALYTICS_RANGE_DAYS - 1L));
        Instant from = fromDate.atStartOfDay(KST).toInstant();
        Instant to = toDate.plusDays(1).atStartOfDay(KST).toInstant();

        if (stores.isEmpty()) {
            return new HqAnalyticsResponse(fromDate.toString(), toDate.toString(), 0, null, List.of(), List.of(),
                    List.of(), List.of());
        }
        List<Long> storeIds = stores.stream().map(Store::getId).toList();

        Object[] countAvg = hqQueryRepository.brandReviewCountAndAvgRating(storeIds, from, to).get(0);
        long total = ((Number) countAvg[0]).longValue();
        Double avgRating = countAvg[1] == null ? null : round1(((Number) countAvg[1]).doubleValue());

        List<RatingBucket> ratingDist = hqQueryRepository.brandRatingDistribution(storeIds, from, to).stream()
                .map(row -> new RatingBucket(((Number) row[0]).intValue(), ((Number) row[1]).longValue())).toList();
        List<CategoryBucket> categoryDist = hqQueryRepository.brandCategoryDistribution(storeIds, from, to).stream()
                .map(row -> new CategoryBucket((String) row[0], ((Number) row[1]).longValue())).toList();
        List<IssueTagItem> issueTags = hqQueryRepository.brandIssueTagRanking(storeIds, from, to).stream()
                .map(row -> new IssueTagItem((String) row[0], ((Number) row[1]).longValue())).toList();

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
        // 미처리 건수는 "현재 기준"(기간 무관) — listStores 와 동일한 지표를 재사용해 일관성을 유지한다(T-26 원칙).
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
            return new StoreComparisonItem(store.getPublicId().toString(), store.getName(), reviewCount, avg,
                    completionRate, unprocessed);
        }).toList();

        return new HqAnalyticsResponse(fromDate.toString(), toDate.toString(), total, avgRating, ratingDist,
                categoryDist, issueTags, comparison);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    /** listStores/analytics 공용 — 매장별 · 리뷰당 최신 초안 상태 건수(기간 무관, "지금 미처리" 기준). */
    private Map<Long, Map<String, Long>> draftStatusCountsByStore(List<Long> storeIds) {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        for (Object[] row : hqQueryRepository.latestDraftStatusCountsByStore(storeIds)) {
            Long storeId = ((Number) row[0]).longValue();
            result.computeIfAbsent(storeId, k -> new HashMap<>()).put((String) row[1], ((Number) row[2]).longValue());
        }
        return result;
    }

    /** listStores/analytics 공용 — 매장별 고위험(risk_level>=3) 미종결 리뷰 수. */
    private Map<Long, Long> highRiskCountsByStore(List<Long> storeIds) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : hqQueryRepository.highRiskPendingCountsByStore(storeIds)) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }

    /**
     * 절대규칙 5·6/H9: 구독 상세는 절대 넣지 않고 coarse 한 이용중/정지만 넘긴다.
     *
     * <p>★ 구독 레코드가 없는 것(null)은 "정지"가 아니다. 계좌이체 구독은 나중에 붙었고
     * 그 이전에 등록된 매장에는 subscription 행 자체가 없다. 이걸 SUSPENDED 로 매핑하면
     * 정상 운영 중인 매장이 본부 화면에 전부 '정지'로 보인다(실기동에서 실제로 그렇게 나왔다).
     * 정지는 <b>명시적으로 정지된 경우</b>에만 말한다.
     *
     * <p>PAST_DUE(미납)는 IN_SERVICE 다 — 실제 서비스 중단은 D+21 의 SUSPENDED 전이에서 일어난다.
     */
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

    private static HqAnalysisResponse toAnalysisResponse(ReviewAnalysis a) {
        if (a == null) {
            return null;
        }
        return new HqAnalysisResponse(a.getCategory(), a.getSentiment(), List.of(a.getIssueTags()),
                (int) a.getRiskLevel(), List.of(a.getRiskReasons()));
    }

    private static HqDraftSummaryResponse toDraftSummary(ReplyDraft d) {
        if (d == null) {
            return null;
        }
        return new HqDraftSummaryResponse(String.valueOf(d.getId()), d.getStatus(), d.getContent());
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
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

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static LocalDate parseOrDefault(String s, LocalDate fallback) {
        return s == null || s.isBlank() ? fallback : LocalDate.parse(s);
    }

    // ★ 기간 필터는 null 을 넘기지 않고 넓은 경계값으로 대체한다 — ReviewService 와 동일한 이유
    // (Postgres 가 바인드 파라미터 타입을 추론 못해 500 이 나는 문제 회피, 실기동에서 확인된 패턴).
    private static final Instant OPEN_START = Instant.EPOCH;
    private static final Instant OPEN_END = LocalDate.of(9999, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    private static Instant parseFromDate(String date) {
        if (date == null || date.isBlank()) {
            return OPEN_START;
        }
        return LocalDate.parse(date).atStartOfDay(KST).toInstant();
    }

    private static Instant parseToDateExclusive(String date) {
        if (date == null || date.isBlank()) {
            return OPEN_END;
        }
        return LocalDate.parse(date).plusDays(1).atStartOfDay(KST).toInstant();
    }
}
