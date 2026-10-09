"""DATAAPI_WRITE_ENABLED 게시 게이트 — 'true' 정확히 하나만 연다(2026-10-09).

테스트 중 운영 서버는 등록을 꺼 둔다. 태스크 루프(_run_publish_job)가 계정 조회·스로틀·
DataAPI 호출 전에 막는지, 값의 변형(false·1·yes·공백)이 실수로 등록을 열지 않는지 잠근다.
"""
import pytest

import tasks
from dataapi import Credentials, DataApiClient

PAYLOAD = {
    "draftId": 501, "accountId": 42, "platform": "BAEMIN",
    "platformStoreId": "s1", "platformReviewId": "r1", "content": "감사합니다",
    "riskLevel": 1, "storeActive": True, "dispatchToken": "tok",
}


class _Redis:
    def get(self, key):
        return "tok" if key == "dispatch:draft:501" else None


def _never(*_a, **_k):
    raise AssertionError("등록이 꺼져 있으면 호출되면 안 된다")


class _Client:
    create_comment = staticmethod(_never)


@pytest.mark.parametrize("value", [None, "false", "FALSE", "0", "1", "yes", "on", " true", ""])
def test_publish_job_blocked_before_any_call_unless_exactly_true(monkeypatch, value):
    if value is None:
        monkeypatch.delenv("DATAAPI_WRITE_ENABLED", raising=False)
    else:
        monkeypatch.setenv("DATAAPI_WRITE_ENABLED", value)

    result = tasks._run_publish_job(
        dict(PAYLOAD), _Redis(), _Client(), _never,
        sleep=_never, now=lambda: 0.0, rand=lambda: 0.0, lease_key="k", token="t",
    )

    assert result["status"] == "FAILED"
    assert result["publish"]["failReason"] == "DATAAPI_WRITE_DISABLED"


@pytest.mark.parametrize("value", ["false", "1", "yes", "TRUE "])
def test_client_create_comment_refuses_unless_exactly_true(monkeypatch, value):
    monkeypatch.setenv("DATAAPI_WRITE_ENABLED", value)
    with pytest.raises(RuntimeError):
        DataApiClient().create_comment("baemin", Credentials("id", "enc"), "감사합니다", "s1", "r1")
