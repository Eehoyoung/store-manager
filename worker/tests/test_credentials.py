"""봉투암호화 복호화(T-2) 회귀 테스트.

Spring EnvelopeCipher 의 저장 형식을 파이썬에서 그대로 만들어 되돌린다.
실제 자바 상호운용은 웹에서 계정을 연동한 뒤 check_account 로 확인한다.
"""
import base64
import os

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import credentials

MASTER = base64.b64encode(bytes(range(32))).decode()


def _spring_style_envelope(master_b64: str, plaintext: str):
    """Spring 이 저장하는 형태 그대로 만든다: enc_dek = nonce(12B) || GCM(master, dek)."""
    master = base64.b64decode(master_b64)
    dek = os.urandom(32)
    dek_nonce, data_nonce = os.urandom(12), os.urandom(12)
    enc_dek = dek_nonce + AESGCM(master).encrypt(dek_nonce, dek, None)
    enc_password = AESGCM(dek).encrypt(data_nonce, plaintext.encode("utf-8"), None)
    return enc_password, enc_dek, data_nonce


def test_roundtrip(monkeypatch):
    monkeypatch.setattr(credentials, "MASTER_KEY_B64", MASTER)
    enc_pw, enc_dek, nonce = _spring_style_envelope(MASTER, "!Kwic123테스트")
    assert credentials.decrypt_envelope(enc_pw, enc_dek, nonce) == "!Kwic123테스트"


def test_wrong_master_key_is_reported_clearly(monkeypatch):
    """마스터키가 바뀌면 조용히 빈 값을 주지 말고 원인을 말해야 한다.
    이게 없으면 '로그인 실패' 로만 보여서 키 교체 사고를 추적할 수 없다."""
    enc_pw, enc_dek, nonce = _spring_style_envelope(MASTER, "secret")
    monkeypatch.setattr(credentials, "MASTER_KEY_B64", base64.b64encode(os.urandom(32)).decode())
    with pytest.raises(RuntimeError, match="CREDENTIAL_MASTER_KEY"):
        credentials.decrypt_envelope(enc_pw, enc_dek, nonce)


def test_tampered_ciphertext_is_rejected(monkeypatch):
    monkeypatch.setattr(credentials, "MASTER_KEY_B64", MASTER)
    enc_pw, enc_dek, nonce = _spring_style_envelope(MASTER, "secret")
    tampered = bytes([enc_pw[0] ^ 0xFF]) + enc_pw[1:]
    with pytest.raises(RuntimeError):
        credentials.decrypt_envelope(tampered, enc_dek, nonce)


def test_master_key_must_be_32_bytes(monkeypatch):
    monkeypatch.setattr(credentials, "MASTER_KEY_B64", base64.b64encode(b"short").decode())
    with pytest.raises(RuntimeError, match="32바이트"):
        credentials.decrypt_envelope(b"x", b"y" * 13, b"z" * 12)
