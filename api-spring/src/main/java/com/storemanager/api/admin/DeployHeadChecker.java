package com.storemanager.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub 공개 API 로 master HEAD(sha·커밋 시각)를 조회한다. 자동 배포 정지 감지용.
 * repo 가 비면(기본) 외부 호출을 하지 않는다. 성공은 10분, 실패는 30분 캐시해 점검 화면이 열릴 때마다 부르지 않는다.
 */
@Component
public class DeployHeadChecker {

    // 배포 직후 옛 HEAD 와 비교해 가짜 빨강이 나지 않게 짧게 둔다(공개 API 한도 시간당 60회 안).
    static final Duration OK_TTL = Duration.ofMinutes(10);
    static final Duration FAIL_TTL = Duration.ofMinutes(30);

    private final String repo;
    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private String sha;
    private Instant committedAt;
    private Instant fetchedAt;

    @Autowired
    public DeployHeadChecker(@Value("${app.deploy.check-repo:}") String repo) {
        this(repo, URI.create("https://api.github.com"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    DeployHeadChecker(String repo, URI base, HttpClient http) {
        this.repo = repo == null ? "" : repo.trim();
        this.base = base;
        this.http = http;
    }

    public boolean enabled() {
        return !repo.isEmpty();
    }

    /** 조회 실패·꺼짐이면 null. */
    public synchronized String headSha(Instant now) {
        refresh(now);
        return sha;
    }

    public synchronized Instant headCommittedAt(Instant now) {
        refresh(now);
        return committedAt;
    }

    private void refresh(Instant now) {
        if (!enabled()) {
            return;
        }
        Duration ttl = sha == null ? FAIL_TTL : OK_TTL;
        if (fetchedAt != null && Duration.between(fetchedAt, now).compareTo(ttl) < 0) {
            return;
        }
        fetchedAt = now;
        sha = null;
        committedAt = null;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/repos/" + repo + "/commits/master"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/vnd.github+json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return;
            }
            JsonNode n = json.readTree(r.body());
            String s = n.path("sha").asText("");
            String d = n.path("commit").path("committer").path("date").asText("");
            if (!s.isEmpty() && !d.isEmpty()) {
                committedAt = Instant.parse(d);
                sha = s;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 조회 실패는 UNKNOWN 으로 흡수한다(본문·URL 로깅 없음).
        }
    }
}
