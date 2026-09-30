package com.storemanager.api.hq;

import com.storemanager.api.billing.PricingDtos.HqBrandPricingResponse;
import com.storemanager.api.hq.HqDtos.HqAnalyticsResponse;
import com.storemanager.api.hq.HqDtos.HqBrandResponse;
import com.storemanager.api.hq.HqDtos.HqFeaturesResponse;
import com.storemanager.api.hq.HqDtos.HqOverviewResponse;
import com.storemanager.api.hq.HqDtos.HqStoreResponse;
import com.storemanager.api.hq.HqDtos.ReportResponse;
import com.storemanager.api.hq.HqReviewDtos.ReviewDetailResponse;
import com.storemanager.api.hq.HqReviewDtos.ReviewListResponse;
import com.storemanager.api.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가맹본부 조회 API (Sprint 8, FR-800 · docs/26a 본부 홈·개별 리뷰·보고서 확장). docs/10 §2.9.
 * ★★ 조회 전용이다(H8) — 이 컨트롤러(그리고 hq 패키지 전체)에는 POST/PUT/PATCH/DELETE 매핑을 절대 추가하지 않는다.
 * 승인·거절·답글수정·페르소나변경·게시 등 어떤 쓰기 경로도 여기서 노출하지 않는다.
 * (HqNoWriteEndpointTest 가 hq 패키지에 쓰기 매핑이 없음을 소스 스캔으로 단언한다.)
 */
@RestController
@RequestMapping("/api/v1/hq")
public class HqController {

    private final HqService hqService;
    private final HqReviewAccessProperties reviewAccessProperties;

    public HqController(HqService hqService, HqReviewAccessProperties reviewAccessProperties) {
        this.hqService = hqService;
        this.reviewAccessProperties = reviewAccessProperties;
    }

    @GetMapping("/features")
    public HqFeaturesResponse features() {
        return new HqFeaturesResponse(reviewAccessProperties.isEnabled());
    }

    /** FR-801 — 본부 권한이 없으면 빈 배열(403 아님). */
    @GetMapping("/brands")
    public List<HqBrandResponse> brands() {
        return hqService.listBrands(CurrentUser.publicId());
    }

    /** docs/26a — 본부 홈 요약. */
    @GetMapping("/brands/{brandName}/overview")
    public HqOverviewResponse overview(@PathVariable String brandName) {
        return hqService.overview(CurrentUser.publicId(), brandName);
    }

    /** FR-802 — 가맹점 목록 + 운영 상태. */
    @GetMapping("/brands/{brandName}/stores")
    public List<HqStoreResponse> stores(@PathVariable String brandName) {
        return hqService.listStores(CurrentUser.publicId(), brandName);
    }

    /** V49 — 가맹 브랜드 구간 단가. 매장별 결제 상태·금액은 포함하지 않는다(H9). */
    @GetMapping("/brands/{brandName}/pricing")
    public HqBrandPricingResponse pricing(@PathVariable String brandName) {
        return hqService.pricing(CurrentUser.publicId(), brandName);
    }

    /** FR-804 — 브랜드 집계(별점·카테고리 분포, 이슈 태그 랭킹, 매장별 비교). */
    @GetMapping("/brands/{brandName}/analytics")
    public HqAnalyticsResponse analytics(@PathVariable String brandName,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID storeId,
            // ★ platform·tag·riskReason 은 계약(docs/26a)에 있으나 선택 항목이라 이번 구현 범위에서
            //   비웠다(보고서 참고). 값을 받아도 지금은 무시한다 — 웹과의 계약 형태만 먼저 맞춘다.
            @RequestParam(required = false) String platform, @RequestParam(required = false) String tag,
            @RequestParam(required = false) String riskReason) {
        return hqService.analytics(CurrentUser.publicId(), brandName, from, to, storeId);
    }

    /** docs/26a — 개별 리뷰 목록(플래그 게이트, 동의 매장만). Cache-Control: no-store. */
    @GetMapping("/brands/{brandName}/reviews")
    public ResponseEntity<ReviewListResponse> reviews(@PathVariable String brandName,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID storeId, @RequestParam(required = false) String platform,
            @RequestParam(required = false) Integer rating, @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag, @RequestParam(required = false) Integer riskLevel,
            @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "20") int size) {
        ReviewListResponse body = hqService.listReviews(CurrentUser.publicId(), brandName, from, to, storeId,
                platform, rating, status, riskLevel, cursor, size);
        return noStore(body);
    }

    /** docs/26a — 개별 리뷰 상세(플래그 게이트, 동의 매장만). Cache-Control: no-store. */
    @GetMapping("/brands/{brandName}/reviews/{reviewId}")
    public ResponseEntity<ReviewDetailResponse> review(@PathVariable String brandName, @PathVariable UUID reviewId) {
        return noStore(hqService.getReview(CurrentUser.publicId(), brandName, reviewId));
    }

    /** docs/26a — 인쇄용/CSV 보고서. 개별 리뷰 원문은 포함하지 않는다. */
    @GetMapping("/brands/{brandName}/report")
    public ResponseEntity<?> report(@PathVariable String brandName, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "json") String format) {
        ReportResponse data = hqService.report(CurrentUser.publicId(), brandName, from, to);
        if ("csv".equalsIgnoreCase(format)) {
            byte[] csv = HqReportCsv.render(data);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hq-report-" + brandName + ".csv\"")
                    .body(csv);
        }
        return ResponseEntity.ok(data);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
