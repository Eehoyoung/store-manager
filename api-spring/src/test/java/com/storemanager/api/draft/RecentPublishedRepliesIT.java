package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 실제 Postgres에서 매장·상태·기간·상한과 조회 실패의 트랜잭션 격리를 검증한다. */
@SpringBootTest(properties = {"app.scheduler.draft.enabled=false", "app.scheduler.retention.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
class RecentPublishedRepliesIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired ReplyDraftRepository drafts;
    @Autowired UnifiedReviewRepository reviews;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired CredentialService credentials;
    @Autowired StorePlatformLinkRepository links;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private record Fixture(Long storeId, Long linkId) {}

    private Fixture fixture() {
        String id = UUID.randomUUID().toString();
        var owner = users.save(AppUser.builder().email(id + "@example.test").passwordHash("dummy").name("테스트").build());
        var store = stores.save(Store.builder().ownerId(owner.getId()).name("테스트매장").build());
        var account = credentials.save(owner.getId(), "BAEMIN", id, "dummy");
        var link = links.save(StorePlatformLink.builder().storeId(store.getId()).accountId(account.getId())
                .platform("BAEMIN").platformStoreId(id).build());
        return new Fixture(store.getId(), link.getId());
    }

    private void reply(Fixture fixture, String status, Instant published, String content) {
        var review = reviews.save(UnifiedReview.builder().storeId(fixture.storeId()).linkId(fixture.linkId())
                .platform("BAEMIN").platformReviewId(UUID.randomUUID().toString()).writtenAt(Instant.now()).build());
        drafts.save(ReplyDraft.builder().reviewId(review.getId()).storeId(fixture.storeId()).status(status)
                .publishedAt(published).content(content).generatedBy("HUMAN").build());
    }

    @Test
    void 같은매장의_최근30일_PUBLISHED만_최신20건을_읽는다() {
        Fixture own = fixture();
        Fixture other = fixture();
        Instant now = Instant.now();
        Instant since = now.minus(30, ChronoUnit.DAYS);
        reply(own, "BLOCKED", now, "차단 답글");
        reply(own, "FAILED", now, "실패 답글");
        reply(other, "PUBLISHED", now, "다른 매장 답글");
        reply(own, "PUBLISHED", since.minusSeconds(1), "오래된 답글");
        reply(own, "PUBLISHED", null, "게시시각 없는 답글");
        for (int i = 0; i < 22; i++) {
            reply(own, "PUBLISHED", now.minusSeconds(i), "게시 답글 " + i);
        }
        assertThat(drafts.findRecentPublishedContents(own.storeId(), since, PageRequest.of(0, 20)))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20)
                        .mapToObj(i -> "게시 답글 " + i).toList());
    }

    @Test
    void 실제_SQL_실패가_외부_생성트랜잭션을_오염시키지_않는다() {
        // 이 클래스 전용 임시 DB다. 테이블 부재로 PostgreSQL 오류를 실제 발생시킨다.
        jdbc.execute("ALTER TABLE reply_draft RENAME TO reply_draft_temporarily_unavailable");
        try {
            new TransactionTemplate(transactions).execute(status -> {
                assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                assertThrows(RuntimeException.class, () -> drafts.findRecentPublishedContents(
                        1L, Instant.now(), PageRequest.of(0, 20)));
                assertThat(jdbc.queryForObject("SELECT 2", Integer.class)).isEqualTo(2);
                assertThat(status.isRollbackOnly()).isFalse();
                return null;
            });
        } finally {
            jdbc.execute("ALTER TABLE reply_draft_temporarily_unavailable RENAME TO reply_draft");
        }
    }
}
