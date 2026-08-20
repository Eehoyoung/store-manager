"""
T0~T3 모델 라우터 (Sprint 3 (a)).

CLAUDE.md LLM 정책의 티어 매핑을 그대로 따른다. 원가가 사업 모델 그 자체이므로
과분류(고비용 티어 남용)를 피하고, 상위 티어 호출이 실패하면 한 단계 아래로
안전하게 내려간다(문서 12 §8 폴백 체인).
"""
from __future__ import annotations

# CLAUDE.md 확정 매핑 — 모델 ID 에 날짜 접미사를 붙이지 않는다.
# T0 은 룰 템플릿(LLM 미사용)이라 모델이 없다 — prompts.render_t0_template 이 담당.
TIER_MODELS: dict[str, str | None] = {
    "T0": None,
    "T1": "claude-haiku-4-5",
    "T2": "claude-sonnet-5",
    "T3": "claude-opus-5",
}

# 분류(CLASSIFY)는 라우팅 대상이 아니라 항상 저비용 모델 하나로 고정한다(리뷰 1건당
# 반드시 1회 호출되므로 원가 영향이 가장 크다).
CLASSIFY_MODEL = "claude-haiku-4-5"

# 폴백 체인: 상위 티어 실패 시 한 단계만 아래로 재시도한다(문서 12 §8, "1회 재시도").
_FALLBACK: dict[str, str] = {"T3": "T2", "T2": "T1", "T1": "T0"}

_HIGH_RISK_THRESHOLD = 2  # risk_level >= 2 → T3 (문서 12 §8)


def route(
    rating: int,
    body: str,
    category: str,
    risk_level: int,
    force_tier: str | None = None,
) -> str:
    """리뷰 특성으로 답글 생성 티어를 결정한다. forceTier 가 오면 그대로 따른다."""
    if force_tier:
        return force_tier
    if not body.strip() or category == "NOISE":
        return "T0"
    if category == "ABUSIVE" or risk_level >= _HIGH_RISK_THRESHOLD:
        return "T3"
    if category in ("IMPROVEMENT", "COMPLAINT"):
        return "T2"
    # PRAISE, POSITIVE (risk 낮음) — 단순 긍정
    return "T1"


def fallback_tier(tier: str) -> str | None:
    """상위 티어 호출 실패 시 내려갈 티어. 더 내려갈 곳이 없으면 None(blocked 처리)."""
    return _FALLBACK.get(tier)


def demo() -> None:
    assert route(rating=5, body="맛있어요", category="POSITIVE", risk_level=0) == "T1"
    assert route(rating=5, body="맛있어요", category="PRAISE", risk_level=0) == "T1"
    assert route(rating=5, body="", category="NOISE", risk_level=0) == "T0"
    assert route(rating=1, body="   ", category="NOISE", risk_level=0) == "T0"
    assert route(rating=2, body="별로예요", category="COMPLAINT", risk_level=1) == "T2"
    assert route(rating=3, body="괜찮았어요", category="IMPROVEMENT", risk_level=0) == "T2"
    assert route(rating=1, body="XX놈들아", category="ABUSIVE", risk_level=0) == "T3"
    assert route(rating=5, body="굿", category="POSITIVE", risk_level=2) == "T3"
    assert route(rating=5, body="굿", category="POSITIVE", risk_level=0, force_tier="T2") == "T2"

    assert fallback_tier("T3") == "T2"
    assert fallback_tier("T2") == "T1"
    assert fallback_tier("T1") == "T0"
    assert fallback_tier("T0") is None

    assert TIER_MODELS["T0"] is None
    assert TIER_MODELS["T1"] == "claude-haiku-4-5"
    assert TIER_MODELS["T2"] == "claude-sonnet-5"
    assert TIER_MODELS["T3"] == "claude-opus-5"
    print("router demo OK")


if __name__ == "__main__":
    demo()
