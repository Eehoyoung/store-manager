package com.storemanager.api.analytics;

import com.storemanager.api.analytics.AnalyticsDtos.IssuesResponse;
import com.storemanager.api.analytics.AnalyticsDtos.MenusResponse;
import com.storemanager.api.analytics.AnalyticsDtos.ResponsePerformanceResponse;
import com.storemanager.api.analytics.AnalyticsDtos.SummaryResponse;
import com.storemanager.api.analytics.AnalyticsDtos.TrendResponse;
import com.storemanager.api.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/13 §8 대시보드 API (Sprint 5 A1/A2). page/size 가 아니라 from/to 기간 조회다. */
@RestController
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/api/v1/stores/{storeId}/analytics/summary")
    public SummaryResponse summary(@PathVariable UUID storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analyticsService.summary(CurrentUser.publicId(), storeId, from, to);
    }

    @GetMapping("/api/v1/stores/{storeId}/analytics/trend")
    public TrendResponse trend(@PathVariable UUID storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analyticsService.trend(CurrentUser.publicId(), storeId, from, to);
    }

    @GetMapping("/api/v1/stores/{storeId}/analytics/issues")
    public IssuesResponse issues(@PathVariable UUID storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analyticsService.issues(CurrentUser.publicId(), storeId, from, to);
    }

    @GetMapping("/api/v1/stores/{storeId}/analytics/menus")
    public MenusResponse menus(@PathVariable UUID storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analyticsService.menus(CurrentUser.publicId(), storeId, from, to);
    }

    @GetMapping("/api/v1/stores/{storeId}/analytics/response")
    public ResponsePerformanceResponse response(@PathVariable UUID storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return analyticsService.response(CurrentUser.publicId(), storeId, from, to);
    }
}
