package com.storemanager.api.hq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.hq.HqDtos.CategoryBucket;
import com.storemanager.api.hq.HqDtos.HqAnalyticsResponse;
import com.storemanager.api.hq.HqDtos.HqBrandResponse;
import com.storemanager.api.hq.HqDtos.HqStoreResponse;
import com.storemanager.api.hq.HqDtos.DailyRiskItem;
import com.storemanager.api.hq.HqDtos.IssueTagItem;
import com.storemanager.api.hq.HqDtos.MenuIssueItem;
import com.storemanager.api.hq.HqDtos.PlatformLinkStatus;
import com.storemanager.api.hq.HqDtos.RatingBucket;
import com.storemanager.api.hq.HqDtos.RiskClusterItem;
import com.storemanager.api.hq.HqDtos.StoreComparisonItem;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가맹본부 조회 서비스 (Sprint 8, FR-802·804).
 * ★ 조회 전용이다 — 이 클래스에 쓰기 메서드를 추가하지 않는다(H8). 기존 서비스의 쓰기 메서드도 호출하지 않는다.
 * ★ 모든 조회는 HqAccessGuard 로 접근통제를 거치고, 반드시 AuditLog 를 남긴다(H7, FR-805).
 * ★ 집계는 HqQueryRepository 의 DB 쿼리 결과를 조립만 한다 — 매장별 반복 쿼리(N+1) 없음.
 * ★ WP-01(2026-08-28) — FR-803 개별 리뷰 통합 조회를 제거했다. hq-data-sharing.md 가
 * "개별 리뷰 내용·사진·주문 메뉴·작성일·작성자 표시는 볼 수 없다"고 명시했는데 코드가 그걸 어기고
 * 있었다. 본부는 이제 analytics(집계)로만 브랜드 상태를 본다.
 */
@Service
public class HqService {

    private static final ZoneId KST = PublishScheduleCalculator.KST;
    private static final int RECENT_DAYS = 30;
    private static final int DEFAULT_ANALYTICS_RANGE_DAYS = 30;

    /**
     * 본부가 조회할 수 있는 최대 소급 기간(일).
     *
     * <p>★ 이 값은 성능 튜닝이 아니라 <b>노출 범위 정책</b>이다. 본부는 가맹점 리뷰를
     * 조회만 할 수 있고, 그 조회조차 최근 90일로 제한한다. 개인정보 최소 원칙이고,
     * 브랜드 운영 판단에 3년 전 리뷰가 필요하지 않다.
     *
     * <p>부수 효과로 조회 부하도 준다 — 상한이 없으면 본부 화면 한 번에
     * 브랜드 전체 × 보유기간 전체(현재 3년)를 스캔한다.
     *
     * <p>★ 저장 기간과 혼동하지 말 것. 데이터는 {@code PRIVACY_RETENTION_DAYS} 까지
     * 보관된다. 이 상수는 <b>본부에게 보여 주는 창</b>의 크기일 뿐이고,
     * 가맹점주 본인의 조회는 이 제한을 받지 않는다.
     */
    static final int HQ_MAX_LOOKBACK_DAYS = 90;

    /**
     * 최소 집계 기준(WP-02) — 이 값 미만인 항목은 개수·비율 등 수치를 내려보내지 않는다.
     *
     * <p>★ hq-data-sharing.md · privacy.md 가 "적은 건수 … 조합으로 특정 리뷰 작성자를 다시
     * 알아볼 수 없도록 최소 집계 기준을 적용합니다"고 약속한 바로 그 값이다. 통상 k-익명성
     * 논의에서 쓰는 k=5 를 채택했다 — 집단이 4명 이하면 배경지식과 결합해 개인을 특정하기
     * 쉬워진다는 것이 일반적 근거다.
     *
     * <p>★ 파일럿처럼 매장 수가 적은 브랜드는 이 값 때문에 대부분의 항목이 가려질 수 있다.
     * 그렇다고 기준을 낮추지 말 것 — 매장이 적을수록 오히려 재식별 위험이 크다. 대신
     * belowThreshold 로 항목은 남기고 "몇 건이 가려졌는지"(issueTagsBelowThreshold 등)를
     * 항상 함께 내려 본부가 "문제 없음"으로 오독하지 않게 한다(T-3, analysisCoverageRate 와
     * 같은 원칙).
     */
    static final long MIN_AGGREGATION_THRESHOLD = 5;

    private final HqAccessGuard hqAccessGuard;
    private final FranchiseHqMemberRepository hqMemberRepository;
    private final HqQueryRepository hqQueryRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public HqService(HqAccessGuard hqAccessGuard, FranchiseHqMemberRepository hqMemberRepository,
            HqQueryRepository hqQueryRepository, AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper) {
        this.hqAccessGuard = hqAccessGuard;
        this.hqMemberRepository = hqMemberRepository;
        this.hqQueryRepository = hqQueryRepository;
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
            long pending = statusCounts.getOrDefault("DRAFT", 0L);
            long blocked = statusCounts.getOrDefault("BLOCKED", 0L);
            long highRisk = highRiskByStore.getOrDefault(id, 0L);
            Object[] recent = recentStatsByStore.get(id);
            long recentCount = recent == null ? 0L : ((Number) recent[0]).longValue();
            Double recentAvg = recent == null || recent[1] == null ? null : ((Number) recent[1]).doubleValue();

            // ★ WP-03 — analytics 와 같은 최소 집계 기준을 매장 목록 통계에도 적용한다.
            boolean pendingBelow = revealsIndividual(pending);
            boolean blockedBelow = revealsIndividual(blocked);
            boolean highRiskBelow = revealsIndividual(highRisk);
            boolean recentBelow = revealsIndividual(recentCount);
            boolean belowThreshold = pendingBelow || blockedBelow || highRiskBelow || recentBelow;

            return new HqStoreResponse(store.getPublicId().toString(), store.getName(), store.getAddress(),
                    store.getActivatedAt() != null, toServiceStatus(subStatusByStore.get(id)),
                    linksByStore.getOrDefault(id, List.of()), toIso(lastCollectedByStore.get(id)),
                    pendingBelow ? null : pending, blockedBelow ? null : blocked,
                    highRiskBelow ? null : highRisk, recentBelow ? null : recentCount,
                    recentBelow ? null : recentAvg, belowThreshold);
        }).toList();
    }

    /** FR-804 — 브랜드 집계(별점·카테고리 분포, 이슈 태그 랭킹, 매장별 비교). ★ 감사로그 INSERT 때문에 readOnly 를 걸지 않는다. */
    @Transactional
    public HqAnalyticsResponse analytics(UUID userPublicId, String brandName, String fromStr, String toStr) {
        AppUser user = hqAccessGuard.requireBrandAccess(userPublicId, brandName);
        audit(user, "HQ_ANALYTICS_VIEW", "BRAND", null, brandName);

        List<Store> stores = hqQueryRepository.findStoresByBrandName(brandName);
        LocalDate toDate = parseOrDefault(toStr, LocalDate.now(KST));
        LocalDate fromDate = parseOrDefault(fromStr, toDate.minusDays(DEFAULT_ANALYTICS_RANGE_DAYS - 1L));
        // ★ 90일보다 이전을 요청하면 거절하지 않고 90일로 당긴다.
        //   거절하면 화면이 통째로 비어 원인을 알 수 없다. 응답의 from/to 에 실제 적용 기간이
        //   담겨 화면에 그대로 표시되므로, 조용히 다른 결과를 주는 것도 아니다.
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

        // ── 이슈 태그 랭킹 (WP-02: 최소 집계 기준 미만이면 수치를 가린다) ──────────
        Map<String, Object[]> currentIssues = rowsByKey(hqQueryRepository.brandIssueTagStats(storeIds, from, to));
        Map<String, Object[]> previousIssues = rowsByKey(
                hqQueryRepository.brandIssueTagStats(storeIds, previousFrom, previousTo));
        Set<String> allTags = new LinkedHashSet<>(currentIssues.keySet());
        allTags.addAll(previousIssues.keySet());
        List<RawIssueTag> rawIssueTags = allTags.stream().map(tag -> {
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
        long issueTagsBelowThreshold = rawIssueTags.stream()
                .filter(i -> revealsIndividual(i.count()) || revealsIndividual(i.previousCount())).count();
        List<IssueTagItem> issueTags = rawIssueTags.stream().map(i -> {
            boolean below = revealsIndividual(i.count()) || revealsIndividual(i.previousCount());
            return new IssueTagItem(i.tag(), below ? null : i.count(), below ? null : i.previousCount(),
                    below ? null : i.rate(), below ? null : i.previousRate(), below ? null : i.delta(),
                    below ? null : i.affectedStores(), below ? null : i.avgRating(),
                    below ? "BELOW_THRESHOLD" : i.signal(), below);
        }).toList();

        // ── 위험 사유 군집 (WP-02) ────────────────────────────────────────
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

        // ── 메뉴 × 이슈 (WP-02) ──────────────────────────────────────────
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

        // ── 일자별 위험 흐름 (WP-02) — 하루 표본이 가장 작다 ─────────────────
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
            // ★ 매장 비교표도 같은 기준으로 가린다. 리뷰가 1~4건인 매장은 평균 별점과 완료율이
            //   곧 그 몇 건을 가리킨다. 매장 자체는 목록에 남긴다 — 빼면 '문제 없음' 으로 읽힌다.
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

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    /** WP-02 집계 전 원본 값. 정렬은 실제 값 기준으로 하고, 가리는 것은 마지막 표시 단계에서만 한다. */
    private record RawIssueTag(String tag, long count, long previousCount, long affectedStores, Double rate,
            Double previousRate, Double delta, Double avgRating, String signal) {
    }

    private record RawRiskCluster(String reason, long count, long previousCount, long affectedStores) {
    }

    /**
     * ★ WP-02 — 이 개수를 그대로 내려보내면 특정 리뷰(들)를 다시 알아볼 수 있는지 판정한다.
     * 0 은 "그 이슈가 없다"는 뜻이라 안전하다. 1~{@code MIN_AGGREGATION_THRESHOLD-1} 은
     * 적은 인원(리뷰) 집합을 그대로 노출하는 것이라 가린다.
     */
    private static boolean revealsIndividual(long count) {
        return count > 0 && count < MIN_AGGREGATION_THRESHOLD;
    }

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

    /** 고정·투명 기준. 파일럿에서 오탐이 확인되기 전까지 별도 설정 시스템은 만들지 않는다. */
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
}
