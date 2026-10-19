"""운영 Compose 의 DataAPI 기본값이 안전한 쪽인지 잠근다.

env 에서 키가 빠졌을 때 운영계·댓글 등록이 켜지면, 테스트 중에도 실매장 리뷰에
되돌릴 수 없는 답글이 달린다. 운영계와 등록은 env 에 명시해야만 켜져야 한다.
"""
import re
from pathlib import Path

COMPOSE = Path(__file__).resolve().parents[2] / "deploy" / "docker-compose.prod.yml"


def _default(key: str) -> str:
    m = re.search(rf"^\s*{key}:\s*\$\{{{key}:-([^}}]*)\}}", COMPOSE.read_text(encoding="utf-8"), re.M)
    assert m, f"{key} 기본값 줄을 찾지 못했다 — 형식이 바뀌었으면 이 테스트도 고칠 것"
    return m.group(1)


def test_댓글_등록은_env_에_명시해야만_켜진다():
    assert _default("DATAAPI_WRITE_ENABLED") == "false"


def test_키가_없으면_개발계로_떨어진다():
    assert "datahub-dev" in _default("DATAAPI_BASE_URL")


# 운영 compose 기본값 잠금. 바꾸려면 이 표와 문서(deploy/README.md 등)를 함께 고친다.
LOCKED = {
    "DATAAPI_WRITE_ENABLED": "false",
    "DATAAPI_BASE_URL": "https://datahub-dev.scraping.co.kr",
    "DATAAPI_CALL_BUDGET": "0",
    "BILLING_SCHEDULER_ENABLED": "true",
    "MAIL_REQUIRED": "true",
    "BUSINESS_INFO_REQUIRED": "false",
    "DRAFT_SCHEDULER_ENABLED": "true",
    "RETENTION_SCHEDULER_ENABLED": "true",
    "CREDENTIAL_REQUIRE_KMS": "false",
    "GOLDENSET_EVAL_ENABLED": "false",
    "TIERED_PRICING_ENABLED": "false",
}


def test_운영_compose_기본값이_잠긴_값과_같다():
    for key, want in LOCKED.items():
        got = _default(key)
        assert got == want, (
            f"{key} 기본값이 {want!r} 에서 {got!r} 로 바뀌었다 — "
            "이 표를 고치면서 문서(deploy/README.md 등)도 함께 고쳐라"
        )
