"""encrypt_password() 회귀 테스트.

업체 값은 쓰지 않는다 — 공개된 고정 벡터로 'AES/CBC/PKCS5Padding + Base64' 구현 자체가
맞는지만 검증한다. 업체 샘플(PlainData/EncData) 대조는 verify_enc_vector() 로 로컬에서 한다.
"""
import pytest

from dataapi import encrypt_password, verify_enc_vector

# 16바이트 키/IV 원문. 실제 자격증명이 아니라 테스트용 고정값이다.
KEY = "0123456789abcdef"
IV = "abcdef9876543210"


def test_aes_cbc_pkcs5_base64():
    # 아래 기대값은 동일 파라미터로 표준 AES-128-CBC/PKCS7 를 돌린 결과다.
    got = encrypt_password("pa$$w0rd", spec="AES/CBC/PKCS5Padding", key=KEY, iv=IV, output="base64")
    assert got == "OHnQ2vLDFYRRA/NzzNA/Hg=="


def test_hex_output_is_same_ciphertext():
    import base64

    b64 = encrypt_password("pa$$w0rd", spec="AES/CBC/PKCS5Padding", key=KEY, iv=IV, output="base64")
    hexed = encrypt_password("pa$$w0rd", spec="AES/CBC/PKCS5Padding", key=KEY, iv=IV, output="hex")
    assert base64.b64decode(b64).hex() == hexed


def test_missing_params_are_blocked():
    """미설정 상태로 호출되면 막아야 한다 — 안 막으면 평문이 그대로 나갈 위험이 있다."""
    with pytest.raises(RuntimeError):
        encrypt_password("pa$$w0rd", spec="", key="", iv="")


def test_cbc_without_iv_is_blocked():
    with pytest.raises(RuntimeError):
        encrypt_password("pa$$w0rd", spec="AES/CBC/PKCS5Padding", key=KEY, iv="")


def test_bad_key_length_is_rejected():
    with pytest.raises(ValueError):
        encrypt_password("pa$$w0rd", spec="AES/CBC/PKCS5Padding", key="tooshort", iv=IV)


def test_verify_vector_reports_no_match_on_wrong_expected(monkeypatch):
    monkeypatch.setattr("dataapi.ENC_SPEC", "AES/CBC/PKCS5Padding")
    monkeypatch.setattr("dataapi.ENC_KEY", KEY)
    monkeypatch.setattr("dataapi.ENC_IV", IV)
    assert verify_enc_vector("pa$$w0rd", "OHnQ2vLDFYRRA/NzzNA/Hg==")["ok"] is True
    assert verify_enc_vector("pa$$w0rd", "wrong")["ok"] is False


# ── 봉투 파싱: 두 층의 오류 코드 ──────────────────────────────────────────
from dataapi import DataApiError, parse_envelope, ecode_action, _is_retryable  # noqa: E402


def test_플랫폼_레벨_거절은_errCode로_보고한다():
    """호출 한도 소진(2003)은 data 가 비어 온다. 최상위 errCode 로 떨어지지 않으면
    '원인 불명 실패' 가 되어 한도 소진을 로그인 실패로 오인한다."""
    resp = {"errCode": "2003", "errMsg": "need point", "data": {}}
    with pytest.raises(DataApiError) as ei:
        parse_envelope(resp)
    assert ei.value.ecode == "2003"
    assert ecode_action("2003") == "QUOTA_EXHAUSTED"
    assert _is_retryable("2003") is False   # 재시도해도 절대 성공하지 않는다


def test_data_ECODE가_최상위_errCode보다_우선한다():
    """플랫폼은 성공(0000)인데 배달앱 로그인이 실패한 정상적인 실패 형태."""
    resp = {"errCode": "0000", "result": "SUCCESS",
            "data": {"RESULT": "FAIL", "ECODE": "ERR_MLCOM_MSG50059", "ERRMSG": "로그인 실패"}}
    with pytest.raises(DataApiError) as ei:
        parse_envelope(resp)
    assert ei.value.ecode == "ERR_MLCOM_MSG50059"
    assert ecode_action(ei.value.ecode) == "LINK_ERROR"


def test_성공코드_0000을_오류코드로_보고하지_않는다():
    """errCode 0000 은 플랫폼 성공일 뿐 업무 성공이 아니다(절대규칙 2).
    이걸 오류 코드로 보고하면 '성공 코드로 실패' 라는 모순된 로그가 남는다."""
    with pytest.raises(DataApiError) as ei:
        parse_envelope({"errCode": "0000", "result": "SUCCESS", "data": {}})
    assert ei.value.ecode is None


def test_최상위_result가_SUCCESS여도_성공이_아니다():
    """★ 절대규칙 2. DataAPI 는 실패해도 HTTP 200 / result SUCCESS 를 반환한다."""
    with pytest.raises(DataApiError):
        parse_envelope({"result": "SUCCESS", "errCode": "0000",
                        "data": {"RESULT": "FAIL", "ECODE": "ERR_X", "ERRMSG": "x"}})


def test_설정오류는_사장님이_아니라_운영자_문제로_분류한다():
    assert ecode_action("3020") == "CONFIG_ERROR"   # 우리 암호화 설정이 틀림
    assert ecode_action("8003") == "CONFIG_ERROR"   # 토큰이 틀림
    assert _is_retryable("3020") is False


def test_일시장애는_재시도한다():
    for code in ("2020", "8004", "3031", "3070"):
        assert _is_retryable(code) is True, code
