package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** GitHub 호출은 로컬 HTTP 목으로만 검증한다. */
class DeployHeadCheckerTest {

    static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");

    @Test
    void repo_비면_호출하지_않는다() {
        var c = new DeployHeadChecker("", URI.create("http://127.0.0.1:1"), HttpClient.newHttpClient());
        assertThat(c.enabled()).isFalse();
        assertThat(c.headSha(T0)).isNull();
    }

    @Test
    void 성공은_캐시되고_실패는_null() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        int[] status = {200};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/commits/master", ex -> {
            hits.incrementAndGet();
            byte[] b = "{\"sha\":\"abc123\",\"commit\":{\"committer\":{\"date\":\"2026-10-25T23:00:00Z\"}}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status[0], status[0] == 200 ? b.length : -1);
            if (status[0] == 200) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
        try {
            var c = new DeployHeadChecker("o/r", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    HttpClient.newHttpClient());
            assertThat(c.headSha(T0)).isEqualTo("abc123");
            assertThat(c.headCommittedAt(T0)).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
            assertThat(hits.get()).isEqualTo(1);
            assertThat(c.headSha(T0.plusSeconds(3600))).isEqualTo("abc123");
            assertThat(hits.get()).isEqualTo(1);
            status[0] = 500;
            assertThat(c.headSha(T0.plusSeconds(6 * 3600 + 1))).isNull();
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
