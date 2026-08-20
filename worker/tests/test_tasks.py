"""tasks.poll_reviews / tasks.backfill 회귀 테스트.
네트워크(HTTP)와 Redis는 전부 목으로 대체한다 — 실호출 금지(CLAUDE.md)."""
from datetime import date, timedelta

import pytest

import tasks
from dataapi import Credentials, DataApiError
from tasks import AccountInfo, _backfill_windows, backfill, poll_reviews


class FakeRedis:
    """SET NX EX 흉내만 내는 최소 목 객체 (fakeredis 등 새 의존성 없이 표준 방식으로 해결)."""

    def __init__(self, acquire: bool = True):
        self._acquire = acquire
        self._store: dict[str, str] = {}
        self.deleted: list[str] = []

    def set(self, key, value, nx=True, ex=None):
        if not self._acquire:
            return False
        self._store[key] = value
        return True

    def get(self, key):
        return self._store.get(key)

    def delete(self, key):
        self._store.pop(key, None)
        self.deleted.append(key)

    def eval(self, script, numkeys, key, token):
        """compare-and-delete: 내가 잡은 락일 때만 지운다."""
        if self._store.get(key) == token:
            self.delete(key)
            return 1
        return 0


class FakeClient:
    """DataApiClient.fetch_reviews 를 흉내내며 호출 인자를 기록한다. 네트워크 호출 없음."""

    def __init__(self, data: dict | None = None, error: Exception | None = None):
        self.calls: list[tuple[str, str, str]] = []
        self._data = data or {"RESULT": "SUCCESS", "REVIEWLIST": []}
        self._error = error

    def fetch_reviews(self, platform, credentials, start_date, end_date):
        self.calls.append((platform, start_date, end_date))
        if self._error:
            raise self._error
        return self._data


def _dummy_account() -> AccountInfo:
    return AccountInfo(platform="baemin", credentials=Credentials("test", "enc"))


def test_backfill_windows_split_90_days_into_13_chunks_of_7():
    windows = _backfill_windows(date(2026, 8, 20), total_days=90, chunk_days=7)
    assert len(windows) == 13
    # 가장 오래된 구간부터 오늘까지 이어져야 하고, 마지막 구간은 오늘로 끝나야 한다
    assert windows[0][0] == date(2026, 8, 20) - timedelta(days=89)
    assert windows[-1][1] == date(2026, 8, 20)
    # 구간끼리 하루도 겹치거나 비지 않아야 한다
    for (s1, e1), (s2, _e2) in zip(windows, windows[1:]):
        assert e1 + timedelta(days=1) == s2


def test_backfill_calls_fetch_reviews_13_times_with_disjoint_windows(monkeypatch):
    posted = []
    monkeypatch.setattr(tasks, "_post_collect_result", lambda payload: posted.append(payload))

    fake_client = FakeClient()
    results = backfill(
        "acct-1",
        sleep=lambda s: None,
        client_factory=lambda: fake_client,
        redis_client=FakeRedis(acquire=True),
        account_loader=lambda account_id: _dummy_account(),
    )

    assert len(fake_client.calls) == 13
    assert len(posted) == 13
    assert all(status["status"] == "SUCCESS" for status in results)
    # STARTDATE/ENDDATE 는 yyyyMMdd 8자리 문자열이어야 한다
    for _platform, start_s, end_s in fake_client.calls:
        assert len(start_s) == 8 and len(end_s) == 8


def test_poll_skipped_when_lock_not_acquired(monkeypatch):
    """분산락 획득 실패 시 DataAPI 호출 없이 즉시 SKIPPED 로 끝나야 한다."""
    load_calls = []
    monkeypatch.setattr(tasks, "_post_collect_result", lambda payload: pytest.fail("호출되면 안 됨"))

    result = poll_reviews(
        "acct-1",
        sleep=lambda s: None,
        client_factory=lambda: pytest.fail("락 미획득 시 클라이언트를 만들면 안 됨"),
        redis_client=FakeRedis(acquire=False),
        account_loader=lambda account_id: load_calls.append(account_id) or _dummy_account(),
    )

    assert result == {"status": "SKIPPED"}
    assert load_calls == []  # 계정 조회조차 하지 않아야 한다


def test_poll_reports_failed_status_and_ecode_on_login_failure(monkeypatch):
    posted = []
    monkeypatch.setattr(tasks, "_post_collect_result", lambda payload: posted.append(payload))
    error = DataApiError(ecode="ERR_MLCOM_MSG50059", errmsg="로그인 실패", retryable=False)
    fake_client = FakeClient(error=error)

    result = poll_reviews(
        "acct-1",
        sleep=lambda s: None,
        client_factory=lambda: fake_client,
        redis_client=FakeRedis(acquire=True),
        account_loader=lambda account_id: _dummy_account(),
    )

    assert result["status"] == "FAILED"
    assert result["ecode"] == "ERR_MLCOM_MSG50059"
    assert len(fake_client.calls) == 1  # retryable=False 이므로 재시도하지 않음
    assert posted[0]["status"] == "FAILED"
    assert posted[0]["action"] == "LINK_ERROR"


def test_lock_is_released_even_when_account_loader_raises(monkeypatch):
    monkeypatch.setattr(tasks, "_post_collect_result", lambda payload: pytest.fail("호출되면 안 됨"))
    redis = FakeRedis(acquire=True)

    def _boom(account_id):
        raise NotImplementedError("account 조회 실패")

    with pytest.raises(NotImplementedError):
        poll_reviews(
            "acct-1",
            sleep=lambda s: None,
            client_factory=lambda: pytest.fail("여기까지 오면 안 됨"),
            redis_client=redis,
            account_loader=_boom,
        )

    assert redis.deleted == ["lock:collect:acct-1"]


def test_release_lock_does_not_delete_another_workers_lock():
    """락이 TTL 로 만료된 뒤 다른 워커가 새로 잡았다면, 만료된 쪽은 그 락을 지우면 안 된다.
    지우면 같은 계정을 두 워커가 동시에 수집해 DataAPI 호출이 이중 과금된다."""
    rc = FakeRedis()
    my_token = tasks._try_acquire_lock(rc, "acct-1")
    assert my_token is not None

    # TTL 만료를 흉내: 다른 워커가 같은 키를 새 토큰으로 다시 잡은 상태
    rc._store["lock:collect:acct-1"] = "other-worker-token"

    tasks._release_lock(rc, "acct-1", my_token)

    assert rc.get("lock:collect:acct-1") == "other-worker-token"
