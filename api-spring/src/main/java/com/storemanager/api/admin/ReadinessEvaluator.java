package com.storemanager.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 출시 준비 점검 판정(순수 함수). 값·비밀 없이 상태·라벨·시각·건수만 낸다.
 */
public final class ReadinessEvaluator {

    static final Duration WORKER_STALE = Duration.ofMinutes(10);
    static final Duration COLLECT_STALE = Duration.ofHours(26);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReadinessEvaluator() {}

    public record Item(String id, String label, String status, boolean intended, String detail,
            Instant lastSuccessAt) {}

    /** worker 스냅샷. 필드가 없으면 null. */
    record Worker(Instant heartbeatAt, Boolean writeEnabled, String env) {}

    /** DB·설정에서 모은 입력. 시각·모델 null = 없음. */
    public record Inputs(String workerJson, Instant lastCollectAt, Instant lastPublishAt, String latestModel,
            long aiBlocked, boolean portoneEnabled, Instant lastPaidAt, boolean billingScheduler,
            boolean mailConfigured, long scheduledStuck, long scheduledStuckWriteDisabled,
            long errorAccounts, long renewalFailures, long paymentFailures24h, long restrictedStores,
            DeployInfo deploy, double diskUsedRatio) {

        /** 기존 13개 인자 호출용 - 새 항목은 미상(UNKNOWN)으로 둔다. */
        public Inputs(String workerJson, Instant lastCollectAt, Instant lastPublishAt, String latestModel,
                long aiBlocked, boolean portoneEnabled, Instant lastPaidAt, boolean billingScheduler,
                boolean mailConfigured, long scheduledStuck, long scheduledStuckWriteDisabled,
                long errorAccounts, long renewalFailures) {
            this(workerJson, lastCollectAt, lastPublishAt, latestModel, aiBlocked, portoneEnabled, lastPaidAt,
                    billingScheduler, mailConfigured, scheduledStuck, scheduledStuckWriteDisabled, errorAccounts,
                    renewalFailures, 0, 0, null, Double.NaN);
        }
    }

    /**
     * 배포 반영 입력. runningSha 빈 값 = 모름. checkEnabled=false 면 master 조회를 하지 않는다.
     * checkEnabled 인데 headSha 가 null 이면 조회 실패.
     */
    public record DeployInfo(String runningSha, Instant startedAt, boolean checkEnabled, String headSha,
            Instant headCommittedAt) {}

    static final Duration DEPLOY_LAG = Duration.ofHours(1);
    static final double DISK_RED = 0.80;
    static final double DISK_YELLOW = 0.70;

    /** 깨진 JSON·누락 필드는 null 로 흡수한다(UNKNOWN 판정). */
    static Worker parseWorker(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(json);
            if (n == null || !n.isObject()) {
                return null;
            }
            Instant hb = null;
            if (n.hasNonNull("heartbeatAt") && n.get("heartbeatAt").isTextual()) {
                String t = n.get("heartbeatAt").asText();
                try {
                    hb = OffsetDateTime.parse(t).toInstant();
                } catch (RuntimeException e) {
                    hb = Instant.parse(t);
                }
            }
            Boolean we = n.hasNonNull("writeEnabled") && n.get("writeEnabled").isBoolean()
                    ? n.get("writeEnabled").asBoolean() : null;
            String env = n.hasNonNull("dataapiEnv") && n.get("dataapiEnv").isTextual()
                    ? n.get("dataapiEnv").asText() : null;
            return new Worker(hb, we, env);
        } catch (Exception e) {
            return null;
        }
    }

    /** 'dev'|'prod'|'unknown' */
    static String mode(Worker w) {
        if (w == null || w.env() == null) {
            return "unknown";
        }
        return switch (w.env()) {
            case "dev" -> "dev";
            case "prod" -> "prod";
            default -> "unknown";
        };
    }

    public static String mode(String workerJson) {
        return mode(parseWorker(workerJson));
    }

    public static List<Item> evaluate(Inputs in, Instant now) {
        Worker w = parseWorker(in.workerJson());
        String mode = mode(w);
        List<Item> out = new ArrayList<>();

        // workerAlive
        if (w == null || w.heartbeatAt() == null) {
            out.add(item("workerAlive", "워커 작동", "UNKNOWN", false, "워커 상태 기록이 없습니다.", null));
        } else if (Duration.between(w.heartbeatAt(), now).compareTo(WORKER_STALE) > 0) {
            out.add(item("workerAlive", "워커 작동", "RED", false, "워커 신호가 10분 넘게 끊겼습니다.", w.heartbeatAt()));
        } else {
            out.add(item("workerAlive", "워커 작동", "GREEN", false, "워커가 정상 신호를 보내고 있습니다.", w.heartbeatAt()));
        }

        // dataapiEnv
        switch (mode) {
            case "prod" -> out.add(item("dataapiEnv", "DataAPI 환경", "GREEN", false, "운영계에 연결돼 있습니다.", null));
            case "dev" -> out.add(item("dataapiEnv", "DataAPI 환경", "RED", true, "개발계에 연결돼 있습니다.", null));
            default -> out.add(item("dataapiEnv", "DataAPI 환경", "UNKNOWN", false, "환경을 확인할 수 없습니다.", null));
        }

        // writeGate
        if (w == null || w.writeEnabled() == null) {
            out.add(item("writeGate", "댓글 등록 스위치", "UNKNOWN", false, "스위치 상태를 확인할 수 없습니다.", null));
        } else if (w.writeEnabled()) {
            out.add(item("writeGate", "댓글 등록 스위치", "INFO", false, "켜져 있음 — 사람이 의도적으로 켰는지 확인", null));
        } else if ("dev".equals(mode)) {
            out.add(item("writeGate", "댓글 등록 스위치", "RED", true, "꺼져 있어 답글이 나가지 않습니다(개발계).", null));
        } else {
            out.add(item("writeGate", "댓글 등록 스위치", "RED", false, "꺼져 있어 답글이 나가지 않음", null));
        }

        // lastCollect
        Instant lc = in.lastCollectAt();
        if (lc == null) {
            out.add(item("lastCollect", "최근 수집 성공", "UNKNOWN", false, "수집 성공 기록이 없습니다.", null));
        } else if (Duration.between(lc, now).compareTo(COLLECT_STALE) > 0) {
            out.add(item("lastCollect", "최근 수집 성공", "RED", false, "마지막 수집 성공이 26시간을 넘었습니다.", lc));
        } else {
            out.add(item("lastCollect", "최근 수집 성공", "GREEN", false, "최근 26시간 안에 수집에 성공했습니다.", lc));
        }

        // lastPublish
        out.add(in.lastPublishAt() == null
                ? item("lastPublish", "답글 게시 증명", "UNKNOWN", false, "아직 게시 성공이 증명되지 않았습니다.", null)
                : item("lastPublish", "답글 게시 증명", "GREEN", false, "게시 성공 기록이 있습니다.", in.lastPublishAt()));

        // aiModel
        if (in.latestModel() == null) {
            out.add(item("aiModel", "AI 모델", "UNKNOWN", false, "분석 기록이 없습니다.", null));
        } else if ("stub".equals(in.latestModel())) {
            out.add(item("aiModel", "AI 모델", "RED", false, "최근 분석이 stub 모델로 처리됐습니다.", null));
        } else {
            out.add(item("aiModel", "AI 모델", "GREEN", false, "실제 모델로 분석되고 있습니다.", null));
        }

        // aiBlocked
        out.add(in.aiBlocked() == 0
                ? item("aiBlocked", "AI 사용 불가 차단", "GREEN", false, "AI 사용 불가로 차단된 답글이 없습니다.", null)
                : item("aiBlocked", "AI 사용 불가 차단", "RED", false,
                        "AI 사용 불가로 차단된 답글이 " + in.aiBlocked() + "건 있습니다.", null));

        // portoneKeys
        out.add(in.portoneEnabled()
                ? item("portoneKeys", "포트원 키", "GREEN", false, "포트원 키가 설정돼 있습니다.", null)
                : item("portoneKeys", "포트원 키", "RED", false, "포트원 키가 설정되지 않았습니다.", null));

        // paymentProof
        out.add(in.lastPaidAt() == null
                ? item("paymentProof", "실결제 증명", "UNKNOWN", false, "실결제 미증명", null)
                : item("paymentProof", "실결제 증명", "GREEN", false, "결제 성공 기록이 있습니다.", in.lastPaidAt()));

        // billingScheduler
        out.add(in.billingScheduler()
                ? item("billingScheduler", "자동결제 배치", "GREEN", false, "자동결제 배치가 켜져 있습니다.", null)
                : item("billingScheduler", "자동결제 배치", "RED", false, "자동결제 배치가 꺼져 있습니다.", null));

        // mail
        out.add(in.mailConfigured()
                ? item("mail", "메일 발송", "GREEN", false, "메일 계정이 설정돼 있습니다.", null)
                : item("mail", "메일 발송", "RED", false, "메일 계정이 설정되지 않았습니다.", null));

        // scheduledStuck
        out.add(in.scheduledStuck() == 0
                ? item("scheduledStuck", "예약 정체", "GREEN", false, "밀린 예약 답글이 없습니다.", null)
                : item("scheduledStuck", "예약 정체", "RED", false, "30분 넘게 밀린 예약 답글 " + in.scheduledStuck()
                        + "건(댓글 등록 스위치 꺼짐 사유 " + in.scheduledStuckWriteDisabled() + "건).", null));

        // errorAccounts
        out.add(in.errorAccounts() == 0
                ? item("errorAccounts", "연동 오류 계정", "GREEN", false, "연동 오류 계정이 없습니다.", null)
                : item("errorAccounts", "연동 오류 계정", "RED", false, "연동 오류 계정 " + in.errorAccounts() + "개.", null));

        // renewalFailures
        out.add(in.renewalFailures() == 0
                ? item("renewalFailures", "갱신 실패", "GREEN", false, "자동결제 갱신 실패가 없습니다.", null)
                : item("renewalFailures", "갱신 실패", "RED", false, "자동결제 갱신 실패 누적 " + in.renewalFailures() + "건.", null));

        // paymentFailures - 카드·개인정보 없이 건수만
        out.add(in.paymentFailures24h() == 0 && in.restrictedStores() == 0
                ? item("paymentFailures", "결제 실패", "GREEN", false, "최근 24시간 정기결제 실패와 이용 제한 매장이 없습니다.", null)
                : item("paymentFailures", "결제 실패", "RED", false, "최근 24시간 정기결제 실패 " + in.paymentFailures24h()
                        + "건, 이용 제한(RESTRICTED) 매장 " + in.restrictedStores() + "곳.", null));

        out.add(deployItem(in.deploy(), now));
        out.add(diskItem(in.diskUsedRatio()));
        return out;
    }

    static Item deployItem(DeployInfo d, Instant now) {
        String id = "deploy";
        String label = "배포 반영";
        if (d == null || d.runningSha() == null || d.runningSha().isBlank()) {
            return d != null && d.checkEnabled()
                    ? item(id, label, "YELLOW", false, "실행 중인 커밋을 알 수 없어 master 와 비교하지 못합니다.", null)
                    : item(id, label, "UNKNOWN", false, "실행 중인 커밋을 알 수 없습니다.", null);
        }
        String run = d.runningSha();
        String shortSha = run.length() > 7 ? run.substring(0, 7) : run;
        String base = "실행 커밋 " + shortSha;
        if (!d.checkEnabled()) {
            return item(id, label, "INFO", false, base + " (master 비교 꺼짐).", d.startedAt());
        }
        if (d.headSha() == null || d.headCommittedAt() == null) {
            return item(id, label, "YELLOW", false, base + " - master 조회에 실패했습니다.", d.startedAt());
        }
        if (d.headSha().startsWith(run) || run.startsWith(d.headSha())) {
            return item(id, label, "GREEN", false, base + " - master 와 같습니다.", d.startedAt());
        }
        // ponytail: 배포 건너뜀(CI 실패 커밋)도 같은 빨강으로 나온다. 구분하려면 CI 상태 조회 추가.
        return Duration.between(d.headCommittedAt(), now).compareTo(DEPLOY_LAG) > 0
                ? item(id, label, "RED", false, base + " - master 최신 커밋이 1시간 넘게 반영되지 않았습니다.", d.startedAt())
                : item(id, label, "GREEN", false, base + " - 새 커밋 반영 대기 중(1시간 이내).", d.startedAt());
    }

    /**
     * 앱 컨테이너에서 본 루트 파일시스템 사용률. docker overlay2 는 호스트 디스크(/var/lib/docker 가 있는 파티션)를
     * 그대로 반영하지만, 호스트에 파티션이 나뉘어 있거나 컨테이너에 용량 제한이 있으면 실제와 다를 수 있다.
     */
    static Item diskItem(double used) {
        String label = "디스크";
        if (Double.isNaN(used)) {
            return item("disk", label, "UNKNOWN", false, "디스크 사용률을 확인할 수 없습니다.", null);
        }
        String pct = Math.round(used * 100) + "%";
        if (used >= DISK_RED) {
            return item("disk", label, "RED", false, "루트 디스크 사용률 " + pct + " (80% 이상).", null);
        }
        if (used >= DISK_YELLOW) {
            return item("disk", label, "YELLOW", false, "루트 디스크 사용률 " + pct + " (70% 이상).", null);
        }
        return item("disk", label, "GREEN", false, "루트 디스크 사용률 " + pct + ".", null);
    }

    private static Item item(String id, String label, String status, boolean intended, String detail, Instant at) {
        return new Item(id, label, status, intended, detail, at);
    }
}
