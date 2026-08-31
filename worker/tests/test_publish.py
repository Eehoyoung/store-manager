"""publish.py / tasks.publish_drafts 회귀 테스트.
네트워크(HTTP)와 Redis는 전부 목으로 대체한다 — 실호출 금지(CLAUDE.md)."""
import json
from pathlib import Path

import pytest

import publish
import tasks
from dataapi import Credentials, DataApiClient, parse_envelope
from publish import process_publish_job

FIXTURES = Path(__file__).parent / "fixtures"


def _fixture_create_comment(name: str):
    """DataApiClient.create_comment 와 같은 시그니처의 콜러블을 픽스처 응답으로 흉내낸다.
    parse_envelope 를 그대로 통과시켜 실제 클라이언트와 동일한 판정 로직을 쓴다."""
    resp = json.loads((FIXTURES / name).read_text(encoding="utf-8"))

    def _call(platform, credentials, contents, store_id, review_id):
        return parse_envelope(resp)

    return _call


def _payload(**overrides) -> dict:
    base = {
        "draftId": 501,
        "accountId": 42,
        "platform": "BAEMIN",
        "platformStoreId": "14292949",
        "platformReviewId": "2024033100520097",
        "content": "맛있게 드셔주셔서 감사합니다!",
        "riskLevel": 1,
        "storeActive": True,
        "dispatchToken": "test-dispatch-token",
    }
    base.update(overrides)
    return base


class FakeClient:
    """DataApiClient 대역 — create_comment 만 흉내낸다."""

    def __init__(self, create_comment_fn):
        self.calls = []

        def _wrapped(*args):
            self.calls.append(args)
            return create_comment_fn(*args)

        self.create_comment = _wrapped


class FakeQueueRedis:
    """게시 큐·락 Lua의 대역. 실제 Lua는 별도 격리 Redis 테스트에서 확인한다."""

    def __init__(self, items: list[str]):
        self._items = list(items)
        self.sets: dict[str, str] = {}
        self.processing = []
        for raw in items:
            payload = json.loads(raw)
            self.sets[f"dispatch:draft:{payload['draftId']}"] = payload.get("dispatchToken", "")

    def brpoplpush(self, key, destination, timeout):
        if not self._items:
            return None
        raw = self._items.pop(0)
        self.processing.insert(0, raw)
        return raw

    def lrange(self, key, start, end):
        return list(self.processing)

    def lrem(self, key, count, raw):
        if raw not in self.processing:
            return 0
        self.processing.remove(raw)
        return 1

    def eval(self, script, count, *args):
        if script == tasks._PUBLISH_CLAIM_SCRIPT:
            _, key, raw, token, ttl = args
            if raw not in self.processing or key in self.sets:
                return 0
            self.sets[key] = token
            return 1
        if script == tasks._PUBLISH_RENEW_SCRIPT:
            key, token, ttl = args
            return int(self.get(key) == token)
        if script == tasks._PUBLISH_ACK_SCRIPT:
            _, key, raw, token = args
            if self.get(key) != token:
                return 0
            self.lrem(tasks.PUBLISH_PROCESSING_KEY, 1, raw)
            del self.sets[key]
            return 1
        if script == tasks._PUBLISH_RECLAIM_SCRIPT:
            _, _, key, raw = args
            if key in self.sets or not self.lrem(tasks.PUBLISH_PROCESSING_KEY, 1, raw):
                return 0
            self._items.append(raw)
            return 1
        if script == publish._THROTTLE_SCRIPT:
            key, now, interval, jitter = args
            wait = max(0, float(self.get(key) or 0) + interval - now) + jitter
            self.sets[key] = str(now + wait)
            return str(wait)
        raise AssertionError("예상하지 못한 Lua")

    def get(self, key):
        return self.sets.get(key)

    def set(self, key, value, nx=None, ex=None):
        self.sets[key] = value
        return True


# (a) 정상 게시 → action=PUBLISHED + platformCommentId 추출
def test_process_publish_job_success_extracts_platform_comment_id():
    client = FakeClient(_fixture_create_comment("create_comment_success.json"))
    result = process_publish_job(_payload(), "baemin", Credentials("id", "enc"), client.create_comment)

    assert result["status"] == "SUCCESS"
    assert result["action"] == "PUBLISHED"
    assert result["ecode"] is None
    assert result["publish"]["platformCommentId"] == "998877"
    assert result["publish"]["draftId"] == 501
    assert result["accountId"] == "42"  # 고정계약: accountId 는 string
    assert result["platform"] == "BAEMIN"
    assert len(client.calls) == 1


# (b) ERR_MDCOM_MSG00009 → SUCCESS/ALREADY_REPLIED, 재시도 호출 0회(호출 자체가 1회뿐)
def test_process_publish_job_duplicate_comment_is_success_already_replied():
    client = FakeClient(_fixture_create_comment("duplicate_comment.json"))
    result = process_publish_job(_payload(), "baemin", Credentials("id", "enc"), client.create_comment)

    assert result["status"] == "SUCCESS"
    assert result["action"] == "ALREADY_REPLIED"
    assert result["ecode"] == "ERR_MDCOM_MSG00009"
    assert result["publish"]["platformCommentId"] is None
    assert len(client.calls) == 1


# (c) ERR_MLCOM_MSG50059 → LINK_ERROR, 재시도 호출 0회
def test_process_publish_job_login_fail_is_link_error():
    client = FakeClient(_fixture_create_comment("login_fail.json"))
    result = process_publish_job(_payload(), "baemin", Credentials("id", "enc"), client.create_comment)

    assert result["status"] == "FAILED"
    assert result["action"] == "LINK_ERROR"
    assert result["ecode"] == "ERR_MLCOM_MSG50059"
    assert len(client.calls) == 1


# ── 사람 승인 경로 (2026-08-27) ────────────────────────────────────────
#
# ★ 위험 리뷰도 권장 답글을 만들어 두고 사람이 승인하면 게시한다.
#   방어선을 없앤 게 아니라 조건을 좁혔다 — humanApproved 가 명시적으로 True 일 때만 통과한다.


def test_사람이_승인한_고위험_초안은_게시된다():
    """risk 3 이어도 humanApproved=True 면 통과한다. 절대규칙 3 의 '사람 검수' 가 이것이다."""
    assert publish.is_risk_blocked({"riskLevel": 3, "humanApproved": True}) is False


def test_사람_승인이_없으면_고위험은_여전히_막힌다():
    assert publish.is_risk_blocked({"riskLevel": 3}) is True
    assert publish.is_risk_blocked({"riskLevel": 3, "humanApproved": False}) is True


def test_humanApproved_가_참이_아닌_값이면_막는다():
    """★ 마지막 방어선이다. 문자열·숫자·None 을 참으로 해석하면 위조 payload 가 뚫린다."""
    for bogus in ("true", 1, "1", [], {}, None, "yes"):
        assert publish.is_risk_blocked({"riskLevel": 3, "humanApproved": bogus}) is True, bogus


def test_riskLevel_이_없으면_승인_여부와_무관하게_막는다():
    """위험도를 모르는 것은 안전하다는 뜻이 아니다. 기본값 '차단' 을 바꾸지 말 것."""
    assert publish.is_risk_blocked({"humanApproved": True}) is True


def test_저위험은_승인_없이도_통과한다():
    assert publish.is_risk_blocked({"riskLevel": 1}) is False


# (d) riskLevel=3 → DataAPI 호출자가 단 한 번도 호출되지 않음
def test_process_publish_job_blocks_high_risk_without_calling_dataapi():
    def _boom(*args):
        pytest.fail("riskLevel>=3 인데 DataAPI 를 호출하면 안 됨")

    result = process_publish_job(_payload(riskLevel=3), "baemin", Credentials("id", "enc"), _boom)

    assert result["status"] == "FAILED"
    assert result["action"] == "FAIL"
    assert result["publish"]["failReason"] == "RISK_LEVEL_TOO_HIGH"


# (e) 미확인 ECODE → FAIL + ecode 전달
def test_process_publish_job_unknown_ecode_reports_fail_with_ecode():
    client = FakeClient(_fixture_create_comment("null_string_fields.json"))
    result = process_publish_job(_payload(), "baemin", Credentials("id", "enc"), client.create_comment)

    assert result["status"] == "FAILED"
    assert result["action"] == "FAIL"
    assert result["ecode"] == "ERR_XXXX_UNKNOWN"


# (g) DATAAPI_WRITE_ENABLED 가 false 면 실제 등록이 차단된다 — process_publish_job 이 이 게이트를
# 삼키지 않고 그대로 전파해야 상위(태스크 루프)의 일반 예외 처리로 FAIL 보고된다.
def test_write_disabled_blocks_real_registration(monkeypatch):
    monkeypatch.delenv("DATAAPI_WRITE_ENABLED", raising=False)
    client = DataApiClient()

    with pytest.raises(RuntimeError):
        process_publish_job(_payload(), "baemin", Credentials("id", "enc"), client.create_comment)


def test_throttle_waits_remaining_interval_plus_jitter_and_stores_now():
    class NonLuaRedis:
        get = FakeQueueRedis.get
        set = FakeQueueRedis.set

    rc = NonLuaRedis()
    rc.sets = {}
    rc.sets["throttle:publish:1"] = "100.0"
    waited = []

    publish.throttle(
        rc, "1", min_interval=5.0, jitter_max=3.0,
        sleep=lambda s: waited.append(s), now=lambda: 102.0, rand=lambda: 0.5,
    )

    assert waited == [3.0 + 1.5]  # 남은 간격 3.0초 + 지터(0.5 * 3.0)
    assert rc.sets["throttle:publish:1"] == "102.0"


# (f) 큐 처리 중 1건이 예외를 던져도 나머지는 처리된다
def test_publish_drafts_continues_after_one_item_raises(monkeypatch):
    posted = []
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", "true")
    monkeypatch.setattr(tasks, "_post_collect_result", lambda p: posted.append(p))

    items = [
        json.dumps(_payload(draftId=1, accountId=1)),
        json.dumps(_payload(draftId=2, accountId=999)),  # 이 계정만 로더가 예외를 던진다
        json.dumps(_payload(draftId=3, accountId=2)),
    ]
    rc = FakeQueueRedis(items)
    fake_client = FakeClient(_fixture_create_comment("create_comment_success.json"))

    def _loader(account_id):
        if account_id == "999":
            raise RuntimeError("계정 조회 실패(예: DB 오류) — 흉내")
        return tasks.AccountInfo(platform="baemin", credentials=Credentials("id", "enc"))

    results = tasks.publish_drafts(
        sleep=lambda s: None,
        client_factory=lambda: fake_client,
        redis_client=rc,
        account_loader=_loader,
        now=lambda: 0.0,
        rand=lambda: 0.0,
        batch_size=5,
    )

    assert len(results) == 3
    assert len(posted) == 3  # 예외 난 건도 결과가 보고된다
    assert results[0]["status"] == "SUCCESS" and results[0]["publish"]["draftId"] == 1
    assert results[1]["status"] == "FAILED"
    assert results[1]["publish"]["failReason"] == "INTERNAL_ERROR"
    assert results[2]["status"] == "SUCCESS" and results[2]["publish"]["draftId"] == 3
    assert fake_client.calls  # 정상 건은 실제로 create_comment 가 호출됐다
    assert rc.processing == []  # 성공·실패 모두 보고가 끝나면 처리 목록에서 제거된다.


def test_publish_drafts_skips_dataapi_call_for_blocked_risk_level(monkeypatch):
    """큐 처리 루프 자체도 riskLevel>=3 이면 계정 조회·DataAPI 호출을 생략해야 한다."""
    posted = []
    monkeypatch.setattr(tasks, "_post_collect_result", lambda p: posted.append(p))

    items = [json.dumps(_payload(draftId=9, accountId=1, riskLevel=3))]
    rc = FakeQueueRedis(items)
    fake_client = FakeClient(_fixture_create_comment("create_comment_success.json"))

    def _loader(account_id):
        pytest.fail("riskLevel>=3 인데 계정을 조회하면 안 됨")

    results = tasks.publish_drafts(
        sleep=lambda s: None,
        client_factory=lambda: fake_client,
        redis_client=rc,
        account_loader=_loader,
        now=lambda: 0.0,
        rand=lambda: 0.0,
        batch_size=5,
    )

    assert results[0]["publish"]["failReason"] == "RISK_LEVEL_TOO_HIGH"
    assert fake_client.calls == []
    assert posted == results


def test_processing_ack_only_after_successful_report(monkeypatch):
    raw = json.dumps(_payload(riskLevel=3))
    rc = FakeQueueRedis([raw])

    def report(result):
        assert rc.processing == [raw]

    monkeypatch.setattr(tasks, "_post_collect_result", report)
    tasks.publish_drafts(redis_client=rc, client_factory=lambda: None, batch_size=1)
    assert rc.processing == []
    assert "publish:inflight:501" not in rc.sets


@pytest.mark.parametrize("crash", [True, False])
def test_abandoned_processing_reclaimed_after_lease_expiry(monkeypatch, crash):
    raw = json.dumps(_payload(riskLevel=3))
    rc = FakeQueueRedis([raw])

    def failed_report(result):
        if crash:
            raise KeyboardInterrupt("프로세스 중단 대역")
        raise RuntimeError("결과 보고 장애")

    monkeypatch.setattr(tasks, "_post_collect_result", failed_report)
    if crash:
        with pytest.raises(KeyboardInterrupt):
            tasks.publish_drafts(redis_client=rc, client_factory=lambda: None, batch_size=1)
    else:
        tasks.publish_drafts(redis_client=rc, client_factory=lambda: None, batch_size=1)
    assert rc.processing == [raw]
    assert tasks.reclaim_publish_drafts(redis_client=rc) == 0
    rc.sets.pop("publish:inflight:501")  # TTL 만료를 흉내낸다.
    assert tasks.reclaim_publish_drafts(redis_client=rc) == 1
    assert rc.processing == []
    assert rc._items == [raw]


def test_reclaim_between_move_and_claim_prevents_stale_worker_claim():
    raw = json.dumps(_payload())
    rc = FakeQueueRedis([raw])
    assert rc.brpoplpush(tasks.PUBLISH_QUEUE_KEY, tasks.PUBLISH_PROCESSING_KEY, 1) == raw
    assert tasks.reclaim_publish_drafts(redis_client=rc) == 1
    assert not rc.eval(tasks._PUBLISH_CLAIM_SCRIPT, 2, tasks.PUBLISH_PROCESSING_KEY,
                       "publish:inflight:501", raw, "old-owner", 30)


def test_expired_owner_cannot_ack_new_owner():
    raw = json.dumps(_payload())
    rc = FakeQueueRedis([])
    rc.processing = [raw]
    rc.sets["publish:inflight:501"] = "new-owner"
    assert not rc.eval(tasks._PUBLISH_ACK_SCRIPT, 2, tasks.PUBLISH_PROCESSING_KEY,
                       "publish:inflight:501", raw, "old-owner")
    assert rc.processing == [raw]
    assert rc.sets["publish:inflight:501"] == "new-owner"


@pytest.mark.parametrize("current", [None, "new-dispatch-token"])
def test_stale_dispatch_does_not_repeat_paid_call_or_callback(monkeypatch, current):
    rc = FakeQueueRedis([json.dumps(_payload())])
    rc.sets["dispatch:draft:501"] = current

    def forbidden(*args):
        pytest.fail("만료·교체된 예약으로 외부 호출을 하면 안 된다")

    monkeypatch.setattr(tasks, "_post_collect_result", forbidden)
    assert tasks.publish_drafts(redis_client=rc, client_factory=lambda: None,
                                account_loader=forbidden, batch_size=1) == []
    assert rc.processing == []
    assert rc.sets["dispatch:draft:501"] == current


def test_committed_callback_with_lost_response_does_not_repeat_publish(monkeypatch):
    raw = json.dumps(_payload())
    rc = FakeQueueRedis([raw])
    fake_client = FakeClient(_fixture_create_comment("create_comment_success.json"))
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", "true")
    callbacks = []

    def accepted_but_response_lost(result):
        callbacks.append(result)
        rc.sets.pop("dispatch:draft:501")  # 서버 반영·예약 토큰 삭제는 이미 끝났다.
        raise RuntimeError("응답 연결 단절 대역")

    monkeypatch.setattr(tasks, "_post_collect_result", accepted_but_response_lost)
    kwargs = dict(redis_client=rc, client_factory=lambda: fake_client, batch_size=1,
                  account_loader=lambda _: tasks.AccountInfo("baemin", Credentials("id", "enc")),
                  sleep=lambda _: None, now=lambda: 100.0, rand=lambda: 0.0)
    tasks.publish_drafts(**kwargs)
    assert rc.processing == [raw]
    rc.sets.pop("publish:inflight:501")
    assert tasks.reclaim_publish_drafts(redis_client=rc) == 1
    assert tasks.publish_drafts(**kwargs) == []
    assert rc.processing == []
    assert len(fake_client.calls) == len(callbacks) == 1


def test_dispatch_expiring_during_throttle_never_calls_dataapi(monkeypatch):
    rc = FakeQueueRedis([json.dumps(_payload())])
    fake_client = FakeClient(_fixture_create_comment("create_comment_success.json"))
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", "true")

    def expired(*args):
        rc.sets.pop("dispatch:draft:501")

    monkeypatch.setattr(publish, "throttle", expired)
    assert tasks.publish_drafts(
        redis_client=rc, client_factory=lambda: fake_client, batch_size=1,
        account_loader=lambda _: tasks.AccountInfo("baemin", Credentials("id", "enc")),
    ) == []
    assert fake_client.calls == []
    assert rc.processing == []
