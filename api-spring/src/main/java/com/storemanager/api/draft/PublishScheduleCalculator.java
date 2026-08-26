package com.storemanager.api.draft;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * scheduledAt 계산(docs/13 §6, S7). 순수 함수 — DB·Redis 를 건드리지 않는다.
 * ★ collected_at + delayHours 를 KST(Asia/Seoul) 로 해석하고, publishWindows 가 있으면
 * 그 시각 이후 가장 이른 윈도우 시작으로 당긴다. 이미 윈도우 안이면 그대로 둔다.
 * unified_review.written_at 은 시각 정보가 없으므로(writtenDateOnly) 기준으로 쓰지 않는다 — collected_at 만 쓴다.
 */
public final class PublishScheduleCalculator {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private PublishScheduleCalculator() {
    }

    /** HH:mm 기준 하루짜리 구간. start <= end 를 가정한다(자정을 넘는 윈도우는 지원하지 않는다). */
    /**
     * persona.publish_windows(JSON)을 파싱한다.
     *
     * <p>★ DraftService 와 RiskApprovalService 가 같은 계산을 해야 한다 — 자동 예약과 사람
     * 승인이 서로 다른 시각을 계산하면 사장님 화면과 실제 게시가 어긋난다. 그래서 사본을
     * 만들지 않고 여기 한 벌만 둔다.
     *
     * <p>파싱에 실패하면 빈 목록을 준다. 게시를 막는 게 아니라 시간대 제약만 사라지므로
     * fail-open 이 맞다 — 여기서 fail-closed 하면 설정 오타 하나로 답글이 통째로 멈춘다.
     */
    public static List<Window> parseWindows(String publishWindowsJson) {
        if (publishWindowsJson == null || publishWindowsJson.isBlank()) {
            return List.of();
        }
        try {
            List<java.util.Map<String, String>> raw = MAPPER.readValue(publishWindowsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, String>>>() {
                    });
            List<Window> windows = new java.util.ArrayList<>();
            for (java.util.Map<String, String> w : raw) {
                windows.add(new Window(LocalTime.parse(w.get("start")), LocalTime.parse(w.get("end"))));
            }
            return windows;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public record Window(LocalTime start, LocalTime end) {
    }

    public static Instant compute(Instant collectedAt, int delayHours, List<Window> windows) {
        ZonedDateTime baseline = collectedAt.atZone(KST).plusHours(delayHours);
        if (windows == null || windows.isEmpty()) {
            return baseline.toInstant();
        }
        List<Window> sorted = windows.stream().sorted(Comparator.comparing(Window::start)).toList();
        LocalTime t = baseline.toLocalTime();

        // 이미 어느 윈도우 안이면 그대로 둔다.
        for (Window w : sorted) {
            if (!t.isBefore(w.start()) && t.isBefore(w.end())) {
                return baseline.toInstant();
            }
        }
        // 오늘 남은 윈도우 중 가장 이른 시작으로 당긴다.
        for (Window w : sorted) {
            if (t.isBefore(w.start())) {
                return baseline.toLocalDate().atTime(w.start()).atZone(KST).toInstant();
            }
        }
        // 오늘 윈도우가 모두 지났으면 다음날 첫 윈도우로 넘어간다(자정 경계).
        Window first = sorted.get(0);
        return baseline.toLocalDate().plusDays(1).atTime(first.start()).atZone(KST).toInstant();
    }
}
