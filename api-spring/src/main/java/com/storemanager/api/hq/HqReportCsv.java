package com.storemanager.api.hq;

import com.storemanager.api.hq.HqDtos.ReportResponse;
import com.storemanager.api.hq.HqDtos.StoreComparisonItem;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 매장별 집계 CSV (docs/26a endpoints.hq.report). 개별 리뷰 원문은 절대 포함하지 않는다 —
 * {@link StoreComparisonItem} 은 애초에 집계값만 가진 레코드다.
 *
 * <p>UTF-8 BOM 을 붙인다 — 엑셀이 BOM 없는 UTF-8 CSV 를 한글 깨짐으로 읽는 문제(40~60대 설계 원칙,
 * docs/14)를 막기 위함이다.
 */
final class HqReportCsv {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private HqReportCsv() {
    }

    static byte[] render(ReportResponse report) {
        StringBuilder sb = new StringBuilder();
        sb.append("매장명,리뷰수,평균별점,검수대기+차단+고위험,표시기준\n");
        for (StoreComparisonItem row : report.storeRows()) {
            if (row.belowThreshold()) {
                sb.append(csv(row.storeName())).append(",-,-,-,표시 기준 미달\n");
                continue;
            }
            sb.append(csv(row.storeName())).append(',')
                    .append(row.reviewCount() == null ? "" : row.reviewCount()).append(',')
                    .append(row.avgRating() == null ? "" : row.avgRating()).append(',')
                    .append(row.unprocessedCount() == null ? "" : row.unprocessedCount()).append(",\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
