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
