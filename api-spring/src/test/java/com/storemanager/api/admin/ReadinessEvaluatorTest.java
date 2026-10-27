package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.admin.ReadinessEvaluator.Inputs;
import com.storemanager.api.admin.ReadinessEvaluator.Item;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadinessEvaluatorTest {

    static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    static String snap(Instant hb, String we, String env) {
        return "{\"v\":1,\"heartbeatAt\":\"" + hb + "\",\"writeEnabled\":" + we + ",\"dataapiEnv\":\"" + env + "\"}";
    }

    static Inputs in(String worker) {
        return new Inputs(worker, NOW.minusSeconds(3600), NOW, "gpt", 0, true, NOW, true, true, 0, 0, 0, 0);
    }

    static Item get(List<Item> l, String id) {
        return l.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void 키없음_깨진JSON_필드누락은_UNKNOWN() {
        for (String w : new String[] {null, "", "{broken", "[]", "{\"v\":1}"}) {
            List<Item> l = ReadinessEvaluator.evaluate(in(w), NOW);
            assertThat(get(l, "workerAlive").status()).isEqualTo("UNKNOWN");
            assertThat(get(l, "dataapiEnv").status()).isEqualTo("UNKNOWN");
            assertThat(get(l, "writeGate").status()).isEqualTo("UNKNOWN");
            assertThat(ReadinessEvaluator.mode(w)).isEqualTo("unknown");
        }
    }

    @Test
    void 하트비트_10분_경계() {
        assertThat(get(ReadinessEvaluator.evaluate(in(snap(NOW.minusSeconds(600), "true", "prod")), NOW), "workerAlive")
                .status()).isEqualTo("GREEN");
        assertThat(get(ReadinessEvaluator.evaluate(in(snap(NOW.minusSeconds(601), "true", "prod")), NOW), "workerAlive")
                .status()).isEqualTo("RED");
    }

    @Test
    void 개발계는_의도된_빨강() {
        List<Item> l = ReadinessEvaluator.evaluate(in(snap(NOW, "false", "dev")), NOW);
        assertThat(get(l, "dataapiEnv").status()).isEqualTo("RED");
        assertThat(get(l, "dataapiEnv").intended()).isTrue();
        assertThat(get(l, "writeGate").status()).isEqualTo("RED");
        assertThat(get(l, "writeGate").intended()).isTrue();
    }

    @Test
    void 운영계는_스위치_꺼짐이_의도되지_않은_빨강_켜짐은_INFO() {
        List<Item> off = ReadinessEvaluator.evaluate(in(snap(NOW, "false", "prod")), NOW);
        assertThat(get(off, "dataapiEnv").status()).isEqualTo("GREEN");
        assertThat(get(off, "writeGate").status()).isEqualTo("RED");
        assertThat(get(off, "writeGate").intended()).isFalse();
        List<Item> on = ReadinessEvaluator.evaluate(in(snap(NOW, "true", "prod")), NOW);
        assertThat(get(on, "writeGate").status()).isEqualTo("INFO");
    }

    @Test
    void 수집_26시간_경계와_없음() {
        Inputs base = in(null);
        assertThat(get(ReadinessEvaluator.evaluate(withCollect(base, NOW.minusSeconds(26 * 3600)), NOW), "lastCollect")
                .status()).isEqualTo("GREEN");
        assertThat(get(ReadinessEvaluator.evaluate(withCollect(base, NOW.minusSeconds(26 * 3600 + 1)), NOW), "lastCollect")
                .status()).isEqualTo("RED");
        assertThat(get(ReadinessEvaluator.evaluate(withCollect(base, null), NOW), "lastCollect").status())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void 건수_항목과_증명_항목() {
        Inputs i = new Inputs(null, null, null, "stub", 2, false, null, false, false, 3, 2, 1, 4);
        List<Item> l = ReadinessEvaluator.evaluate(i, NOW);
        for (String id : new String[] {"aiModel", "aiBlocked", "portoneKeys", "billingScheduler", "mail",
                "scheduledStuck", "errorAccounts", "renewalFailures"}) {
            assertThat(get(l, id).status()).as(id).isEqualTo("RED");
        }
        assertThat(get(l, "scheduledStuck").detail()).contains("3건").contains("2건");
        assertThat(get(l, "lastPublish").status()).isEqualTo("UNKNOWN");
        assertThat(get(l, "paymentProof").status()).isEqualTo("UNKNOWN");
        assertThat(get(ReadinessEvaluator.evaluate(in(null), NOW), "aiModel").status()).isEqualTo("GREEN");
        assertThat(l).hasSize(17);
    }

    static Inputs full(long payFail, long restricted, ReadinessEvaluator.DeployInfo d, double disk) {
        return new Inputs(null, NOW, NOW, "gpt", 0, true, NOW, true, true, 0, 0, 0, 0, payFail, restricted, d, disk);
    }

    @Test
    void 결제실패_24시간_실패나_이용제한이_있으면_빨강() {
        assertThat(get(ReadinessEvaluator.evaluate(full(0, 0, null, 0.1), NOW), "paymentFailures").status())
                .isEqualTo("GREEN");
        Item a = get(ReadinessEvaluator.evaluate(full(1, 0, null, 0.1), NOW), "paymentFailures");
        assertThat(a.status()).isEqualTo("RED");
        assertThat(a.detail()).contains("1건");
        Item b = get(ReadinessEvaluator.evaluate(full(0, 2, null, 0.1), NOW), "paymentFailures");
        assertThat(b.status()).isEqualTo("RED");
        assertThat(b.detail()).contains("2곳");
    }

    @Test
    void 디스크_80_경계() {
        assertThat(ReadinessEvaluator.diskItem(0.699).status()).isEqualTo("GREEN");
        assertThat(ReadinessEvaluator.diskItem(0.70).status()).isEqualTo("GREEN");
        assertThat(ReadinessEvaluator.diskItem(0.799).status()).isEqualTo("GREEN");
        assertThat(ReadinessEvaluator.diskItem(0.80).status()).isEqualTo("RED");
        assertThat(ReadinessEvaluator.diskItem(Double.NaN).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void 배포반영_판정() {
        Instant start = NOW.minusSeconds(100000);
        var off = new ReadinessEvaluator.DeployInfo("abc1234def", start, false, null, null);
        assertThat(ReadinessEvaluator.deployItem(off, NOW).status()).isEqualTo("INFO");
        assertThat(ReadinessEvaluator.deployItem(null, NOW).status()).isEqualTo("UNKNOWN");
        var noSha = new ReadinessEvaluator.DeployInfo("", start, true, "zzz", NOW);
        assertThat(ReadinessEvaluator.deployItem(noSha, NOW).status()).isEqualTo("UNKNOWN");
        var failed = new ReadinessEvaluator.DeployInfo("abc1234def", start, true, null, null);
        assertThat(ReadinessEvaluator.deployItem(failed, NOW).status()).isEqualTo("UNKNOWN");
        var same = new ReadinessEvaluator.DeployInfo("abc1234def", start, true, "abc1234def", NOW.minusSeconds(99999));
        assertThat(ReadinessEvaluator.deployItem(same, NOW).status()).isEqualTo("GREEN");
        var recent = new ReadinessEvaluator.DeployInfo("abc1234def", start, true, "new9999", NOW.minusSeconds(3600));
        assertThat(ReadinessEvaluator.deployItem(recent, NOW).status()).isEqualTo("GREEN");
        var stale = new ReadinessEvaluator.DeployInfo("abc1234def", start, true, "new9999", NOW.minusSeconds(3601));
        assertThat(ReadinessEvaluator.deployItem(stale, NOW).status()).isEqualTo("RED");
    }

    static Inputs withCollect(Inputs b, Instant at) {
        return new Inputs(b.workerJson(), at, b.lastPublishAt(), b.latestModel(), b.aiBlocked(), b.portoneEnabled(),
                b.lastPaidAt(), b.billingScheduler(), b.mailConfigured(), b.scheduledStuck(),
                b.scheduledStuckWriteDisabled(), b.errorAccounts(), b.renewalFailures());
    }
}
