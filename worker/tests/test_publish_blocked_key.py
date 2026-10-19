"""선택형 요금 PR4 — 게시 직전 Redis 차단 키 확인. DataAPI 는 목."""
import tasks

PAYLOAD = {
    "draftId": 501, "accountId": 42, "platform": "BAEMIN",
    "platformStoreId": "s1", "platformReviewId": "r1", "content": "감사합니다",
    "riskLevel": 1, "storeActive": True, "dispatchToken": "tok",
}
BLOCK = "publish:blocked:draft:501"


class _Redis:
    def __init__(self, blocked=False, fail=False):
        self.blocked, self.fail = blocked, fail

    def get(self, key):
        if key == BLOCK:
            if self.fail:
                raise ConnectionError("redis down")
            return b"1" if self.blocked else None
        return "tok" if key == "dispatch:draft:501" else None


class _Client:
    def __init__(self):
        self.calls = 0

    def create_comment(self, *_a, **_k):
        self.calls += 1
        raise AssertionError("차단 시 호출되면 안 된다")


def _run(rc, client, loader):
    return tasks._run_publish_job(dict(PAYLOAD), rc, client, loader,
                                  sleep=lambda _s: None, now=lambda: 0.0, rand=lambda: 0.0,
                                  lease_key="k", token="t")


def _loader_never(*_a):
    raise AssertionError("차단 시 계정 로드도 하면 안 된다")


def test_queued_job_blocked_by_redis_key(monkeypatch):
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", "true")
    client = _Client()
    r = _run(_Redis(blocked=True), client, _loader_never)
    assert client.calls == 0
    assert r["status"] == "FAILED" and r["publish"]["failReason"] == "PLATFORM_NOT_SELECTED"


def test_no_key_proceeds_to_account_load(monkeypatch):
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", "true")
    loaded = []

    def loader(a):
        loaded.append(a)
        raise RuntimeError("stop")

    r = _run(_Redis(), _Client(), loader)
    assert loaded == ["42"] and r["publish"]["failReason"] == "INTERNAL_ERROR"


def test_redis_error_fails_closed(monkeypatch):
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", "true")
    client = _Client()
    r = _run(_Redis(fail=True), client, _loader_never)
    assert client.calls == 0 and r["publish"]["failReason"] == "INTERNAL_ERROR"
