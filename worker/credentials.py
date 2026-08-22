"""platform_account 자격증명 조회 + 봉투암호화 복호화 (T-2).

Spring 의 EnvelopeCipher 와 짝을 이룬다. 저장 형식은 그쪽이 기준이다:
    enc_dek      = nonce(12B) || AES-256-GCM(masterKey, dek)
    enc_password = AES-256-GCM(dek, 평문)   ← nonce 는 enc_nonce 컬럼
    masterKey    = Base64(CREDENTIAL_MASTER_KEY) 32바이트, GCM 태그 128비트

★ 절대규칙 5: 평문 비밀번호는 이 모듈 밖으로 나가지 않는다.
  load_account() 는 평문을 반환하지 않고, DataAPI 전송용으로 재암호화한 값만 돌려준다.
  로그·예외 메시지·repr 어디에도 평문을 남기지 않는다.

★ 이 모듈은 워커가 DB 를 읽는 유일한 지점이다. 리뷰 적재·상태 전이는 여전히
  POST /internal/collect-result 로만 한다 (CLAUDE.md 서비스 간 경계).
"""
from __future__ import annotations

import base64
import os

from dataapi import Credentials, Platform, encrypt_password

DATABASE_URL = os.environ.get("DATABASE_URL", "")
MASTER_KEY_B64 = os.environ.get("CREDENTIAL_MASTER_KEY", "")

_NONCE_BYTES = 12
_PLATFORM_LOWER: dict[str, Platform] = {
    "BAEMIN": "baemin",
    "YOGIYO": "yogiyo",
    "COUPANGEATS": "coupangeats",
}


def _master_key() -> bytes:
    if not MASTER_KEY_B64:
        raise RuntimeError("CREDENTIAL_MASTER_KEY 가 없습니다 (자격증명 복호화 불가).")
    key = base64.b64decode(MASTER_KEY_B64)
    if len(key) != 32:
        raise RuntimeError("CREDENTIAL_MASTER_KEY 는 Base64 인코딩된 32바이트여야 합니다.")
    return key


def decrypt_envelope(enc_password: bytes, enc_dek: bytes, enc_nonce: bytes) -> str:
    """Spring 이 저장한 봉투암호화 값을 평문으로 되돌린다.

    ★ 반환값은 평문이다. 호출부는 즉시 재암호화하고 변수를 버려야 한다.
    복호화 실패는 마스터키 불일치나 변조를 뜻하므로, 원인 값을 예외에 담지 않는다."""
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    try:
        dek = AESGCM(_master_key()).decrypt(
            enc_dek[:_NONCE_BYTES], enc_dek[_NONCE_BYTES:], None
        )
        return AESGCM(dek).decrypt(enc_nonce, enc_password, None).decode("utf-8")
    except InvalidTag as exc:
        raise RuntimeError(
            "자격증명 복호화 실패 — CREDENTIAL_MASTER_KEY 가 저장 당시와 다르거나 값이 변조되었습니다."
        ) from exc


def load_account(account_id: str) -> tuple[Platform, Credentials]:
    """platform_account 1건을 읽어 DataAPI 전송용 자격증명으로 변환한다.

    revoked_at 이 찍힌 계정은 조회하지 않는다 — 해지된 계정으로 배달앱에 로그인하면 안 된다."""
    import psycopg

    if not DATABASE_URL:
        raise RuntimeError("DATABASE_URL 이 없습니다 (platform_account 조회 불가).")

    with psycopg.connect(DATABASE_URL) as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT platform, login_id, enc_password, enc_dek, enc_nonce, link_status
              FROM platform_account
             WHERE id = %s AND revoked_at IS NULL
            """,
            (int(account_id),),
        )
        row = cur.fetchone()

    if row is None:
        raise LookupError(f"platform_account 를 찾을 수 없습니다 (id={account_id}, 해지되었을 수 있음)")

    platform_raw, login_id, enc_password, enc_dek, enc_nonce, link_status = row
    platform = _PLATFORM_LOWER.get(platform_raw)
    if platform is None:
        raise ValueError(f"알 수 없는 플랫폼: {platform_raw}")

    plain = decrypt_envelope(bytes(enc_password), bytes(enc_dek), bytes(enc_nonce))
    try:
        # 봉투암호화 → 평문 → DataAPI 규격 암호화. 평문이 존재하는 구간을 최소로 유지한다.
        encrypted = encrypt_password(plain)
    finally:
        del plain

    return platform, Credentials(login_id=login_id, login_pwd_encrypted=encrypted)


def check_account(account_id: str) -> dict:
    """복호화가 되는지만 확인한다. DataAPI 를 호출하지 않으므로 과금되지 않는다.

    비밀번호는 길이조차 유용한 정보가 아니므로 굳이 내보내지 않는다 — 성공 여부만 본다."""
    platform, creds = load_account(account_id)
    return {
        "accountId": account_id,
        "platform": platform,
        "loginId": creds.login_id,
        "decrypted": True,
        "dataapiPayloadChars": len(creds.login_pwd_encrypted),
    }
