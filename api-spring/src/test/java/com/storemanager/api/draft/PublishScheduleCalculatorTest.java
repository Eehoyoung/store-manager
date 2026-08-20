package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.draft.PublishScheduleCalculator.Window;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** scheduledAt 계산 순수함수 단위테스트(S7, S12-a). KST 경계·자정넘김 포함. */
class PublishScheduleCalculatorTest {

    private static final ZoneId KST = PublishScheduleCalculator.KST;

    private Instant kst(int y, int m, int d, int h, int mi) {
        return ZonedDateTime.of(y, m, d, h, mi, 0, 0, KST).toInstant();
    }

    @Test
    void 윈도우가_없으면_지연시간만_더한값을_그대로_쓴다() {
        Instant collectedAt = kst(2026, 8, 19, 9, 0);
        Instant result = PublishScheduleCalculator.compute(collectedAt, 6, List.of());
        assertThat(result).isEqualTo(kst(2026, 8, 19, 15, 0));
    }

    @Test
    void 기준시각이_이미_윈도우_안이면_그대로_둔다() {
        Instant collectedAt = kst(2026, 8, 19, 4, 30); // +6h = 10:30
        List<Window> windows = List.of(new Window(LocalTime.of(10, 0), LocalTime.of(11, 30)));
        Instant result = PublishScheduleCalculator.compute(collectedAt, 6, windows);
        assertThat(result).isEqualTo(kst(2026, 8, 19, 10, 30));
    }

    @Test
    void 기준시각이_윈도우_전이면_해당_윈도우_시작으로_당긴다() {
        Instant collectedAt = kst(2026, 8, 19, 1, 0); // +6h = 07:00, 윈도우 10:00 이전
        List<Window> windows = List.of(
                new Window(LocalTime.of(15, 0), LocalTime.of(16, 0)),
                new Window(LocalTime.of(10, 0), LocalTime.of(11, 30)));
        Instant result = PublishScheduleCalculator.compute(collectedAt, 6, windows);
        assertThat(result).isEqualTo(kst(2026, 8, 19, 10, 0)); // 가장 이른 윈도우
    }

    @Test
    void 기준시각이_오늘_윈도우를_모두_지났으면_다음날_첫윈도우로_넘어간다() {
        Instant collectedAt = kst(2026, 8, 19, 12, 0); // +6h = 18:00, 오늘 윈도우(10:00-11:30, 15:00-16:00) 모두 지남
        List<Window> windows = List.of(
                new Window(LocalTime.of(10, 0), LocalTime.of(11, 30)),
                new Window(LocalTime.of(15, 0), LocalTime.of(16, 0)));
        Instant result = PublishScheduleCalculator.compute(collectedAt, 6, windows);
        assertThat(result).isEqualTo(kst(2026, 8, 20, 10, 0)); // 자정 넘겨 다음날
    }

    @Test
    void delayHours로_인해_자정을_넘겨도_KST_기준으로_정확히_계산한다() {
        Instant collectedAt = kst(2026, 8, 19, 22, 0); // +6h = 다음날 04:00 KST
        List<Window> windows = List.of(new Window(LocalTime.of(10, 0), LocalTime.of(11, 30)));
        Instant result = PublishScheduleCalculator.compute(collectedAt, 6, windows);
        assertThat(result).isEqualTo(kst(2026, 8, 20, 10, 0));
    }
}
