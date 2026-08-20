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
    """BRPOP(list.pop) + GET/SET(스로틀) 만 흉내내는 최소 목."""

    def __init__(self, items: list[str]):
        self._items = list(items)
        self.sets: dict[str, str] = {}

    def brpop(self, key, timeout):
        if not self._items:
            return None
        return (key, self._items.pop(0))

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
    rc = FakeQueueRedis([])
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
