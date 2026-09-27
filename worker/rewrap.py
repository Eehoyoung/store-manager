"""마스터키 교체 — platform_account.enc_dek 를 새 마스터키로 다시 감싼다.

deploy/rotate-secrets.sh 가 api-spring·worker 를 멈춘 상태에서 한 번 부른다.
DEK 만 다시 감싸므로 enc_password(배달앱 비밀번호 암호문)는 건드리지 않는다 — 평문이 생기지 않는다.

★ 전 행을 한 트랜잭션으로 처리한다. 한 행이라도 옛 키로 안 풀리면 전부 롤백하고 실패한다.
  일부만 바뀐 상태로 새 키가 적용되면 그 나머지 행은 영영 못 읽는다.
★ 키는 인자가 아니라 환경변수로 받는다(ps 에 남지 않게). 어떤 키도 출력하지 않는다(절대규칙 5).

    OLD_MASTER_KEY=... NEW_MASTER_KEY=... NEW_KEY_ID=... python rewrap.py
"""
from __future__ import annotations

import base64
import os
import sys

_NONCE_BYTES = 12


def _key(name: str) -> bytes:
    key = base64.b64decode(os.environ[name])
    if len(key) != 32:
        raise SystemExit(f"{name} 는 Base64 인코딩된 32바이트여야 합니다.")
    return key


def rewrap_dek(enc_dek: bytes, old_key: bytes, new_key: bytes) -> bytes:
    """nonce(12B) || AES-256-GCM(key, dek) — Spring EnvelopeCipher 와 같은 형식."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    dek = AESGCM(old_key).decrypt(enc_dek[:_NONCE_BYTES], enc_dek[_NONCE_BYTES:], None)
    nonce = os.urandom(_NONCE_BYTES)
    return nonce + AESGCM(new_key).encrypt(nonce, dek, None)


def main() -> int:
    import psycopg

    old_key, new_key = _key("OLD_MASTER_KEY"), _key("NEW_MASTER_KEY")
    new_key_id = os.environ["NEW_KEY_ID"]
    with psycopg.connect(os.environ["DATABASE_URL"]) as conn, conn.cursor() as cur:
        cur.execute("SELECT id, enc_dek FROM platform_account ORDER BY id FOR UPDATE")
        rows = cur.fetchall()
        for account_id, enc_dek in rows:
            cur.execute(
                "UPDATE platform_account SET enc_dek = %s, kms_key_id = %s WHERE id = %s",
                (rewrap_dek(bytes(enc_dek), old_key, new_key), new_key_id, account_id),
            )
        # with 블록이 정상 종료될 때만 커밋된다. 위에서 InvalidTag 가 나면 전부 롤백.
    print(f"[rewrap] {len(rows)}건 재암호화 완료 (key_id={new_key_id})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
