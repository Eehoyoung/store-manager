"""격리 Redis의 실제 Lua 예약 검증. DataAPI는 연결하거나 호출하지 않는다."""
import os
import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
import redis

import publish
import tasks


@pytest.fixture
def reservation():
    url = os.environ.get("CARRYOVER_REDIS_TEST_URL")
    if not url:
        pytest.skip("격리 Redis URL을 지정해야 실제 Lua 검증을 실행한다")
    client = redis.Redis.from_url(url)
    account_id = "carryover-test-" + uuid.uuid4().hex
    try:
        yield client, account_id
    finally:
        client.delete(f"throttle:publish:{account_id}")
        client.close()


def test_lua_sequential_reservation_keeps_interval_and_fractional_jitter(reservation):
    client, account_id = reservation
    waits = []
    for _ in range(2):
        publish.throttle(client, account_id, 5.0, 3.0, waits.append, lambda: 100.0, lambda: 0.5)
    assert waits == [1.5, 8.0]
    assert float(client.get(f"throttle:publish:{account_id}")) == 108.0


def test_lua_concurrent_reservations_do_not_overlap(reservation):
    client, account_id = reservation

    def reserve(_):
        waits = []
        publish.throttle(client, account_id, 5.0, 0.0, waits.append, lambda: 100.0, lambda: 0.0)
        return sum(waits)

    with ThreadPoolExecutor(max_workers=8) as pool:
        waits = list(pool.map(reserve, range(8)))
    assert sorted(waits) == [i * 5.0 for i in range(8)]


def test_lua_failure_does_not_use_non_atomic_fallback():
    class BrokenRedis:
        def eval(self, *args):
            raise redis.ResponseError("테스트 Lua 오류")

        def get(self, key):
            pytest.fail("운영 Lua 오류를 비원자 폴백으로 숨기면 안 된다")

    with pytest.raises(redis.ResponseError):
        publish.throttle(BrokenRedis(), "test", 5.0, 0.0, lambda _: None, lambda: 100.0, lambda: 0.0)


@pytest.fixture
def queue(reservation, monkeypatch):
    client, prefix = reservation
    monkeypatch.setattr(tasks, "PUBLISH_QUEUE_KEY", prefix + ":queue")
    monkeypatch.setattr(tasks, "PUBLISH_PROCESSING_KEY", prefix + ":processing")
    draft_id = int(uuid.uuid4().hex[:12], 16)
    raw = json.dumps({"draftId": draft_id, "accountId": 1, "platform": "BAEMIN", "riskLevel": 3,
                      "dispatchToken": "test-dispatch-token"})
    key = f"publish:inflight:{draft_id}"
    dispatch_key = f"dispatch:draft:{draft_id}"
    client.set(dispatch_key, "test-dispatch-token", ex=900)
    client.lpush(tasks.PUBLISH_QUEUE_KEY, raw)
    try:
        yield client, raw, key
    finally:
        client.delete(tasks.PUBLISH_QUEUE_KEY, tasks.PUBLISH_PROCESSING_KEY, key, dispatch_key)


def test_real_lua_ack_and_reclaim_are_owner_safe(queue, monkeypatch):
    client, raw, key = queue
    popped = client.brpoplpush(tasks.PUBLISH_QUEUE_KEY, tasks.PUBLISH_PROCESSING_KEY, 1)
    assert client.eval(tasks._PUBLISH_CLAIM_SCRIPT, 2, tasks.PUBLISH_PROCESSING_KEY, key, popped, "owner", 30)
    assert tasks.reclaim_publish_drafts(redis_client=client) == 0
    assert not client.eval(tasks._PUBLISH_ACK_SCRIPT, 2, tasks.PUBLISH_PROCESSING_KEY, key, popped, "old-owner")
    client.delete(key)  # 만료 시점부터의 재수용을 검증한다.
    assert tasks.reclaim_publish_drafts(redis_client=client) == 1
    assert not client.eval(tasks._PUBLISH_CLAIM_SCRIPT, 2, tasks.PUBLISH_PROCESSING_KEY, key, popped, "old-owner", 30)
    assert client.llen(tasks.PUBLISH_PROCESSING_KEY) == 0
    monkeypatch.setattr(tasks, "_post_collect_result", lambda _: None)
    results = tasks.publish_drafts(redis_client=client, client_factory=lambda: None, batch_size=1)
    assert results[0]["publish"]["failReason"] == "RISK_LEVEL_TOO_HIGH"
    assert client.llen(tasks.PUBLISH_PROCESSING_KEY) == 0
    assert client.get(key) is None


def test_real_heartbeat_keeps_slow_live_job_out_of_reclaim(queue, monkeypatch):
    client, raw, key = queue
    monkeypatch.setattr(tasks, "PUBLISH_INFLIGHT_TTL_SECONDS", 1)

    def slow_job(payload, *args):
        time.sleep(1.5)  # 최초 TTL보다 길게 처리해 실제 갱신 여부를 본다.
        assert client.ttl(key) >= 0
        assert tasks.reclaim_publish_drafts(redis_client=client) == 0
        return publish.blocked_result(payload)

    monkeypatch.setattr(tasks, "_run_publish_job", slow_job)
    monkeypatch.setattr(tasks, "_post_collect_result", lambda _: None)
    tasks.publish_drafts(redis_client=client, client_factory=lambda: None, batch_size=1)
    assert client.llen(tasks.PUBLISH_PROCESSING_KEY) == 0


def test_real_expired_lease_reclaims_once(queue):
    client, raw, key = queue
    popped = client.brpoplpush(tasks.PUBLISH_QUEUE_KEY, tasks.PUBLISH_PROCESSING_KEY, 1)
    assert client.eval(tasks._PUBLISH_CLAIM_SCRIPT, 2, tasks.PUBLISH_PROCESSING_KEY, key, popped, "owner", 1)
    assert tasks.reclaim_publish_drafts(redis_client=client) == 0
    time.sleep(1.1)  # DELETE 대역이 아니라 Redis 자체 TTL 만료를 확인한다.
    assert client.get(key) is None
    with ThreadPoolExecutor(max_workers=2) as pool:
        reclaimed = list(pool.map(lambda _: tasks.reclaim_publish_drafts(redis_client=client), range(2)))
    assert sum(reclaimed) == 1
    assert client.lrange(tasks.PUBLISH_QUEUE_KEY, 0, -1) == [popped]
    assert client.llen(tasks.PUBLISH_PROCESSING_KEY) == 0
