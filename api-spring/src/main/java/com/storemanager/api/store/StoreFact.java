package com.storemanager.api.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사장님이 확정 입력한 매장 사실. 답글에서 그대로 인용할 수 있는 유일한 사실 출처다.
 *
 * <p>★ [절대 규칙] 8번("확인·확정하지 않은 조치를 약속하지 마라") 때문에 주차·좌석·소음·
 * 접근성·대기시간 지침이 전부 "확정하지 않은 ~를 약속하지 마라" 로 끝난다. 그 결과 방문
 * 리뷰의 절반이 "확인해 보겠습니다" 한 줄로 수렴했다 — 손님은 아무것도 얻지 못하고
 * 사장님은 답글이 다 똑같다고 느낀다.
 *
 * <p>★ 여기 들어오는 값은 <b>사장님이 직접 확정한 사실</b>이다. 지어낸 것이 아니라 받아
 * 적은 것이므로 답글에 써도 8번을 어기지 않는다. <b>AI 가 이 테이블을 채우게 하지 말 것</b> —
 * 그 순간 8번을 우회하는 통로가 된다.
 *
 * <p>★ 전부 선택 입력이다. 비어 있으면 지금과 똑같이 동작한다.
 */
@Entity
@Table(name = "store_fact")
@IdClass(StoreFact.Key.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoreFact {

    /** 답글 프롬프트가 issue_tags 와 맞춰 보는 키다. 값은 ai-python 의 태그 사전과 같아야 한다. */
    public static final java.util.List<String> ALLOWED_KEYS =
            java.util.List.of("주차", "대기시간", "좌석", "소음", "접근성", "영업시간", "예약", "포장");

    public static final int MAX_TEXT_LENGTH = 200;

    @Id
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Id
    @Column(name = "fact_key", nullable = false, length = 20)
    private String factKey;

    @Column(name = "fact_text", nullable = false, length = MAX_TEXT_LENGTH)
    private String factText;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public void updateText(String text) {
        this.factText = text;
        this.updatedAt = Instant.now();
    }

    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long storeId;
        private String factKey;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(storeId, key.storeId) && Objects.equals(factKey, key.factKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storeId, factKey);
        }
    }
}
