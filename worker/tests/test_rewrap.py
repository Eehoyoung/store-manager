"""마스터키 교체 후에도 워커가 자격증명을 그대로 풀 수 있어야 한다."""
import base64
import os

import pytest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import credentials
from rewrap import rewrap_dek


def _envelope(master: bytes, plain: str):
    dek = AESGCM.generate_key(bit_length=256)
    n1, n2 = os.urandom(12), os.urandom(12)
    enc_dek = n1 + AESGCM(master).encrypt(n1, dek, None)
    return enc_dek, AESGCM(dek).encrypt(n2, plain.encode(), None), n2


def test_새_키로_다시_감싼_뒤_같은_비밀번호가_풀린다(monkeypatch):
    old, new = os.urandom(32), os.urandom(32)
    enc_dek, enc_pw, nonce = _envelope(old, "pw-1234")

    rewrapped = rewrap_dek(enc_dek, old, new)

    monkeypatch.setattr(credentials, "MASTER_KEY_B64", base64.b64encode(new).decode())
    assert credentials.decrypt_envelope(enc_pw, rewrapped, nonce) == "pw-1234"


def test_옛_키가_틀리면_실패한다():
    enc_dek, _, _ = _envelope(os.urandom(32), "pw")
    with pytest.raises(InvalidTag):
        rewrap_dek(enc_dek, os.urandom(32), os.urandom(32))
