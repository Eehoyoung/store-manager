"""dataapi.parse_envelope 픽스처 기반 회귀 테스트.
실제 네트워크 호출 없음 — 실제 응답 JSON 을 참고한 픽스처만 사용한다 (CI 규칙)."""
import json
from pathlib import Path

import httpx
import pytest

from dataapi import (
    AlreadyRepliedError,
    Credentials,
    DataApiClient,
    DataApiError,
    _s,
    call_with_retry,
    ecode_action,
    is_retryable_exception,
    parse_envelope,
)

FIXTURES = Path(__file__).parent / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def test_success_multi_store_returns_data_with_reviewlist():
    resp = _load("success_multi_store.json")
    data = parse_envelope(resp)
    assert data["RESULT"] == "SUCCESS"
    assert len(data["REVIEWLIST"]) == 2  # 1계정 N매장 (문서 08 F-7)
    assert data["REVIEWLIST"][0]["STOREID"] == "14292949"
    assert data["REVIEWLIST"][1]["STOREID"] == "14292950"


def test_top_level_success_but_data_fail_raises():
    """★ 가장 중요한 케이스: 최상위 result 가 SUCCESS 여도 data.RESULT 가 FAIL 이면
    반드시 예외가 발생해야 한다 (CLAUDE.md 절대규칙 2, 문서 08 F-1)."""
    resp = _load("login_fail.json")
    assert resp["result"] == "SUCCESS"
    assert resp["errCode"] == "0000"
    with pytest.raises(DataApiError) as exc:
        parse_envelope(resp)
    assert exc.value.ecode == "ERR_MLCOM_MSG50059"
    assert exc.value.retryable is False
    assert not isinstance(exc.value, AlreadyRepliedError)


def test_duplicate_comment_raises_already_replied_not_generic_error():
    """댓글 중복은 실패가 아니라 정상 종료 시나리오 — 별도 예외 타입으로 구분되어야 한다."""
    resp = _load("duplicate_comment.json")
    with pytest.raises(AlreadyRepliedError) as exc:
        parse_envelope(resp)
    assert exc.value.ecode == "ERR_MDCOM_MSG00009"
    assert exc.value.retryable is False


def test_null_string_fields_are_parsed_as_none_and_unknown_code_not_retryable():
    resp = _load("null_string_fields.json")
    with pytest.raises(DataApiError) as exc:
        parse_envelope(resp)
    assert exc.value.errmsg is None  # 문자열 "null" → None (문서 08 F-2)
    assert exc.value.retryable is False  # 미확인 ECODE 는 기본 재시도 금지


def test_s_helper_treats_null_string_as_none():
    assert _s(None) is None
    assert _s("") is None
    assert _s("null") is None
    assert _s("abc") == "abc"
    assert _s(123) == "123"


def test_create_comment_blocked_when_write_disabled(monkeypatch):
    """DATAAPI_WRITE_ENABLED=true 가 아니면 네트워크 호출 전에 즉시 중단되어야 한다
    (개발 중 실수로 실제 리뷰에 답글이 달리는 것을 막는 안전장치)."""
    monkeypatch.delenv("DATAAPI_WRITE_ENABLED", raising=False)
    client = DataApiClient()
    creds = Credentials(login_id="test", login_pwd_encrypted="enc")
    with pytest.raises(RuntimeError):
        client.create_comment("baemin", creds, "감사합니다", "s1", "r1")


def test_credentials_repr_masks_password():
    """절대규칙 5: LOGINPWD 는 로그에 평문으로 남지 않는다."""
    creds = Credentials(login_id="test", login_pwd_encrypted="super-secret-encrypted-value")
    assert "super-secret-encrypted-value" not in repr(creds)


def test_ecode_action_maps_known_codes_and_defaults_unknown_to_fail():
    assert ecode_action("ERR_MLCOM_MSG50059") == "LINK_ERROR"
    assert ecode_action("ERR_MDCOM_MSG00009") == "ALREADY_REPLIED"
    assert ecode_action("ERR_XXXX_UNKNOWN") == "FAIL"
    assert ecode_action(None) == "FAIL"


def test_call_with_retry_does_not_retry_when_ecode_not_retryable():
    """retryable=False 인 DataApiError(로그인 실패 등)는 한 번만 시도하고 즉시 전파해야 한다."""
    attempts = []

    def _fail():
        attempts.append(1)
        raise DataApiError(ecode="ERR_MLCOM_MSG50059", errmsg="로그인 실패", retryable=False)

    with pytest.raises(DataApiError):
        call_with_retry(_fail, sleep=lambda s: pytest.fail("재시도해서는 안 됨"))
    assert len(attempts) == 1


def test_call_with_retry_does_not_retry_already_replied():
    """ALREADY_REPLIED 는 실패가 아닌 정상 종료 시나리오 — 재시도하면 안 된다."""
    attempts = []

    def _dup():
        attempts.append(1)
        raise AlreadyRepliedError(ecode="ERR_MDCOM_MSG00009", errmsg="중복", retryable=False)

    with pytest.raises(AlreadyRepliedError):
        call_with_retry(_dup, sleep=lambda s: pytest.fail("재시도해서는 안 됨"))
    assert len(attempts) == 1


def test_call_with_retry_retries_timeout_up_to_max_attempts_then_raises():
    """타임아웃 등 HTTP 레벨 예외는 재시도 가능으로 분류되어 최대 3회까지 시도한다."""
    attempts = []
    sleeps = []

    def _timeout():
        attempts.append(1)
        raise httpx.TimeoutException("timed out")

    with pytest.raises(httpx.TimeoutException):
        call_with_retry(_timeout, max_attempts=3, base_delay=0.01, sleep=lambda s: sleeps.append(s))
    assert len(attempts) == 3
    assert len(sleeps) == 2  # 마지막 시도 후에는 sleep 하지 않음


def test_call_with_retry_succeeds_after_transient_failure():
    attempts = []

    def _flaky():
        attempts.append(1)
        if len(attempts) < 2:
            raise httpx.TimeoutException("timed out")
        return "ok"

    result = call_with_retry(_flaky, max_attempts=3, base_delay=0.01, sleep=lambda s: None)
    assert result == "ok"
    assert len(attempts) == 2


def test_is_retryable_exception_classifies_5xx_as_retryable_and_4xx_as_not():
    request = httpx.Request("POST", "https://example.com")
    resp_500 = httpx.Response(500, request=request)
    resp_400 = httpx.Response(400, request=request)
    assert is_retryable_exception(httpx.HTTPStatusError("500", request=request, response=resp_500)) is True
    assert is_retryable_exception(httpx.HTTPStatusError("400", request=request, response=resp_400)) is False
