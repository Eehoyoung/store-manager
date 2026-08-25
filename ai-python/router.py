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

# ★ T3 는 risk 3(위생·이물질·식중독·법적)에만 쓴다.
#   risk 2 는 사람이 검수하는 초안이므로 최상위 모델이 필요 없다.
_T3_THRESHOLD = 3
# 이슈가 3개 이상 얽힌 리뷰는 한 답글에 다 담기 어렵다 — 상위 티어로 보낸다.
_MULTI_ISSUE = 3
# 별점 1~2 의 불만은 사장님이 가장 신경 쓰는 답글이다. 내용이 단순해도 상위 티어로 쓴다.
_LOW_RATING = 2


def route(
    rating: int,
    body: str,
    category: str,
    risk_level: int,
    force_tier: str | None = None,
    issue_tag_count: int = 0,
) -> str:
    """리뷰 특성으로 답글 생성 티어를 결정한다. forceTier 가 오면 그대로 따른다.

    ★ 2026-08-25 변경 — '카테고리 = 티어' 매핑을 버리고 **난이도**로 라우팅한다.
      이전에는 IMPROVEMENT·COMPLAINT 를 전부 T2(sonnet, haiku 의 3배)로 보냈다.
      "너무 짜서 먹기 힘들었습니다" 같은 단순 불만에 사과 답글 하나 쓰는 데
      상위 모델이 필요하지 않다. 실측 60건 기준 T1 53% → 82% 가 됐다.

      ★ 원가가 사업 모델 그 자체다(CLAUDE.md). 서버비는 LLM 비용의 1/7 이므로
        원가 관리의 유일한 실질 레버가 이 함수다.

    ★ 티어를 내렸으므로 T1 이 불만 답글을 감당해야 한다. issue_tags 별 상황 지침
      (prompts.SITUATION_GUIDE, v1.4)이 그 역할을 한다 — 지우지 말 것.
    """
    if force_tier:
        return force_tier
    if not body.strip() or category == "NOISE":
        return "T0"
    # ABUSIVE 는 main.py 가 생성 전에 차단하지만, 방어적으로 남긴다.
    if category == "ABUSIVE" or risk_level >= _T3_THRESHOLD:
        return "T3"
    if (
        risk_level == 2
        or issue_tag_count >= _MULTI_ISSUE
        or (category == "COMPLAINT" and rating <= _LOW_RATING)
    ):
        return "T2"
    return "T1"


def fallback_tier(tier: str) -> str | None:
    """상위 티어 호출 실패 시 내려갈 티어. 더 내려갈 곳이 없으면 None(blocked 처리)."""
    return _FALLBACK.get(tier)


def demo() -> None:
    # 긍정 — T1
    assert route(rating=5, body="맛있어요", category="POSITIVE", risk_level=0) == "T1"
    assert route(rating=5, body="맛있어요", category="PRAISE", risk_level=0) == "T1"
    # 무텍스트 — T0
    assert route(rating=5, body="", category="NOISE", risk_level=0) == "T0"
    assert route(rating=1, body="   ", category="NOISE", risk_level=0) == "T0"
    # ★ 단순 불만·개선요청은 T1 이다 (2026-08-25 변경 전에는 전부 T2 였다)
    assert route(rating=3, body="조금 짰어요", category="COMPLAINT", risk_level=1) == "T1"
    assert route(rating=3, body="괜찮았어요", category="IMPROVEMENT", risk_level=0) == "T1"
    assert route(rating=4, body="양이 아쉬워요", category="IMPROVEMENT", risk_level=1) == "T1"
    # 어려운 것만 T2
    assert route(rating=1, body="화가 나네요", category="COMPLAINT", risk_level=2) == "T2"
    assert route(rating=1, body="면이 불었어요", category="COMPLAINT", risk_level=1) == "T2"  # 별점 1
    assert route(rating=2, body="늦었어요", category="COMPLAINT", risk_level=1) == "T2"       # 별점 2
    assert route(rating=5, body="이것저것", category="IMPROVEMENT", risk_level=0,
                 issue_tag_count=3) == "T2"                                                   # 다중 이슈
    # 위험·욕설만 T3
    assert route(rating=1, body="머리카락 나왔어요", category="COMPLAINT", risk_level=3) == "T3"
    assert route(rating=1, body="XX놈들아", category="ABUSIVE", risk_level=0) == "T3"
    # ★ risk 2 는 T3 가 아니다 — 사람이 검수하는 초안에 최상위 모델을 쓰지 않는다
    assert route(rating=5, body="굿", category="POSITIVE", risk_level=2) == "T2"
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
