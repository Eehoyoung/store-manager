"""readiness 하트비트 — 판정·직렬화·예외 삼킴·TTL. 실제 Redis 연결 없음."""
import json
from unittest.mock import MagicMock

import pytest

import tasks


@pytest.mark.parametrize("val,expected", [("true", True), ("TRUE", True), ("1", False), (None, False)])
def test_write_enabled(monkeypatch, val, expected):
    if val is None:
        monkeypatch.delenv("DATAAPI_WRITE_ENABLED", raising=False)
    else:
        monkeypatch.setenv("DATAAPI_WRITE_ENABLED", val)
    assert tasks.readiness_payload()["writeEnabled"] is expected


@pytest.mark.parametrize("url,expected", [
    ("https://datahub-dev.scraping.co.kr", "dev"),
    ("https://api.mydatahub.co.kr", "prod"),
    ("https://evil.example.com/api.mydatahub.co.kr", "unknown"),
    ("", "unknown"),
])
def test_dataapi_env(monkeypatch, url, expected):
    monkeypatch.setenv("DATAAPI_BASE_URL", url)
    assert tasks.readiness_payload()["dataapiEnv"] == expected


def test_payload_fields_and_set_ttl():
    rc = MagicMock()
    tasks.readiness_heartbeat(redis_client=rc)
    key, value = rc.set.call_args.args
    assert key == "readiness:worker"
    assert rc.set.call_args.kwargs == {"ex": 600}
    assert set(json.loads(value)) == {"v", "heartbeatAt", "writeEnabled", "dataapiEnv"}


def test_redis_error_swallowed():
    rc = MagicMock()
    rc.set.side_effect = RuntimeError("down")
    tasks.readiness_heartbeat(redis_client=rc)
