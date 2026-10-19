package com.storemanager.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V50(선택형 요금 컬럼)은 추가만 한다. 실제 PostgreSQL 에서 V49 까지 적용한 뒤 구독 행을 만들고 V50 을 얹어
 * 기존 행의 값이 그대로이고 새 컬럼이 전부 NULL 인지 본다(H2 로는 증명하지 않는다).
 */
@Testcontainers
class V50MigrationIT {

    @Container
    PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private String snapshot(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT id, store_id, plan_code, price_krw, status, trial_ends_at, promotion_code, auto_renew,
                       renewal_failures, next_billing_at, service_until
                FROM subscription ORDER BY id""")) {
            StringBuilder sb = new StringBuilder();
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    sb.append(rs.getString(i)).append('|');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    @Test
    void V49_행은_V50_적용_뒤에도_그대로이고_새_컬럼은_NULL이다() throws Exception {
        flyway("49").migrate();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement st = c.createStatement()) {
            st.execute("INSERT INTO app_user (email, name) VALUES ('v50@example.com', '사장')");
            st.execute("INSERT INTO store (owner_id, name) SELECT id, '매장' FROM app_user WHERE email='v50@example.com'");
            st.execute("INSERT INTO subscription (store_id, price_krw, status) "
                    + "SELECT id, 30000, 'ACTIVE' FROM store WHERE name='매장'");
            String before = snapshot(st);
            assertThat(before).isNotBlank();

            flyway("50").migrate();

            assertThat(snapshot(st)).isEqualTo(before);
            try (ResultSet rs = st.executeQuery("SELECT active_delivery_platforms, pending_delivery_platforms, "
                    + "plan_changed_at FROM subscription")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
                assertThat(rs.getObject(2)).isNull();
                assertThat(rs.getObject(3)).isNull();
            }
            // 새 컬럼에는 기본값이 없다 — 이후 행도 NULL 로 만들어진다.
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name='subscription' AND column_name IN "
                    + "('active_delivery_platforms','pending_delivery_platforms','plan_changed_at') "
                    + "AND (column_default IS NOT NULL OR is_nullable <> 'YES')")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void CHECK는_허용_밖_플랫폼_중복_빈배열_4개를_거절하고_정상_명단은_받는다() throws Exception {
        flyway("50").migrate();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement st = c.createStatement()) {
            st.execute("INSERT INTO app_user (email, name) VALUES ('v50chk@example.com', '사장')");
            st.execute("INSERT INTO store (owner_id, name) SELECT id, '체크매장' FROM app_user "
                    + "WHERE email='v50chk@example.com'");
            st.execute("INSERT INTO subscription (store_id, price_krw, status) "
                    + "SELECT id, 30000, 'TRIAL' FROM store WHERE name='체크매장'");
            for (String ok : new String[] {"{BAEMIN}", "{BAEMIN,YOGIYO}", "{COUPANGEATS,BAEMIN,YOGIYO}"}) {
                st.execute("UPDATE subscription SET active_delivery_platforms='" + ok + "', "
                        + "pending_delivery_platforms='" + ok + "' WHERE status='TRIAL'");
            }
            for (String bad : new String[] {"{NAVER}", "{BAEMIN,BAEMIN}", "{}", "{BAEMIN,YOGIYO,BAEMIN}",
                    "{BAEMIN,YOGIYO,COUPANGEATS,BAEMIN}", "{baemin}"}) {
                assertThatThrownBy(() -> st.execute("UPDATE subscription SET active_delivery_platforms='" + bad
                        + "' WHERE status='TRIAL'")).as(bad).hasMessageContaining("ck_subscription_active");
                assertThatThrownBy(() -> st.execute("UPDATE subscription SET pending_delivery_platforms='" + bad
                        + "' WHERE status='TRIAL'")).as(bad).hasMessageContaining("ck_subscription_pending");
            }
        }
    }
}
