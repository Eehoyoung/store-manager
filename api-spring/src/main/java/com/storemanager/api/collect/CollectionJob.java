package com.storemanager.api.collect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** collection_job 테이블 매핑 (docs/11 §2.8). 워커가 만들고, collect-result 수신 시 Spring 이 결과로 갱신한다. */
@Entity
@Table(name = "collection_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CollectionJob {

    @Id
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * 워커가 생성한 작업 식별자(uuid4). 우리 id 와는 무관하다.
     * 백필 한 번이 13개 구간으로 쪼개지므로 같은 값을 가진 여러 행이 한 번의 백필을 이룬다.
     */
    @Column(name = "job_key")
    private String jobKey;

    @Column(name = "job_type", nullable = false)
    private String jobType; // POLL | BACKFILL

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private String status; // RUNNING | SUCCESS | FAILED | SKIPPED

    @Builder.Default
    @Column(name = "reviews_found", nullable = false)
    private int reviewsFound = 0;

    @Builder.Default
    @Column(name = "reviews_new", nullable = false)
    private int reviewsNew = 0;

    private String ecode;

    @Builder.Default
    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * collect-result 수신 결과로 작업 상태를 마감 처리한다.
     * reviewsNew 는 워커가 보낸 stats.new 를 신뢰하지 않고, Spring 이 UPSERT 결과로 직접 센 값을 받는다.
     */
    public void applyResult(String status, Integer reviewsFound, int reviewsNew, String ecode) {
        this.status = status;
        if (reviewsFound != null) {
            this.reviewsFound = reviewsFound;
        }
        this.reviewsNew = reviewsNew;
        this.ecode = ecode;
        this.finishedAt = Instant.now();
    }
}
