package com.storemanager.api.hq;

import com.storemanager.api.hq.HqDtos.HqAnalyticsResponse;
import com.storemanager.api.hq.HqDtos.HqBrandResponse;
import com.storemanager.api.hq.HqDtos.HqReviewListResponse;
import com.storemanager.api.hq.HqDtos.HqStoreResponse;
import com.storemanager.api.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가맹본부 조회 API (Sprint 8, FR-800). docs/10 §2.9.
 * ★★ 조회 전용이다(H8) — 이 컨트롤러(그리고 hq 패키지 전체)에는 POST/PUT/PATCH/DELETE 매핑을 절대 추가하지 않는다.
 * 승인·거절·답글수정·페르소나변경·게시 등 어떤 쓰기 경로도 여기서 노출하지 않는다.
 * (HqNoWriteEndpointTest 가 hq 패키지에 쓰기 매핑이 없음을 소스 스캔으로 단언한다.)
 */
@RestController
@RequestMapping("/api/v1/hq")
public class HqController {

    private final HqService hqService;

    public HqController(HqService hqService) {
        this.hqService = hqService;
    }

    /** FR-801 — 본부 권한이 없으면 빈 배열(403 아님). */
    @GetMapping("/brands")
    public List<HqBrandResponse> brands() {
        return hqService.listBrands(CurrentUser.publicId());
    }

    /** FR-802 — 가맹점 목록 + 운영 상태. */
    @GetMapping("/brands/{brandName}/stores")
    public List<HqStoreResponse> stores(@PathVariable String brandName) {
        return hqService.listStores(CurrentUser.publicId(), brandName);
    }

    /** FR-803 — 브랜드 전체 리뷰 통합 조회. */
    @GetMapping("/brands/{brandName}/reviews")
    public HqReviewListResponse reviews(@PathVariable String brandName,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer riskLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String issueTag,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return hqService.listReviews(CurrentUser.publicId(), brandName, storeId, minRating, maxRating, category,
                riskLevel, status, issueTag, from, to, page, size);
    }

    /** FR-804 — 브랜드 집계(별점·카테고리 분포, 이슈 태그 랭킹, 매장별 비교). */
    @GetMapping("/brands/{brandName}/analytics")
    public HqAnalyticsResponse analytics(@PathVariable String brandName,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return hqService.analytics(CurrentUser.publicId(), brandName, from, to);
    }
}
