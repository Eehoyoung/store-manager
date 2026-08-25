"""티어 분포 회귀 테스트 — 원가가 사업 모델 그 자체다(CLAUDE.md).

★ 서버비는 LLM 비용의 1/7 이다. 원가 관리의 유일한 실질 레버가 라우팅이므로,
  분포가 조용히 상위 티어로 밀리면 아무도 모르는 사이에 원가가 배로 뛴다.

★ 이 테스트는 API 를 호출하지 않는다. 비용 0.
"""
import collections

import pytest

import llm
import router

# 실측 트래픽 표본 — 실연동 매장의 review_analysis 60건(2026-08-25).
# (category, risk_level, rating, issue_tag_count)
# ★ COMPLAINT 8건은 모델이 risk 2 로 부풀린 것을 docs/12 §1.2 기준으로 1 로 교정했다.
#   "면이 다 불어 있어서 그냥 버렸습니다" 에는 강한 표현·환불 요구·반복 불만이 없다.
REAL_TRAFFIC = (
    [("PRAISE", 0, 5, 0)] * 28
    + [("POSITIVE", 0, 4, 0)] * 4
    + [("IMPROVEMENT", 1, 3, 1)] * 16
    + [("IMPROVEMENT", 0, 4, 0)] * 1
    + [("COMPLAINT", 1, 1, 1)] * 4
    + [("COMPLAINT", 1, 2, 1)] * 4
    + [("COMPLAINT", 3, 1, 2)] * 2
    + [("NOISE", 0, 5, 0)] * 1
)


def _distribution(rows):
    counts = collections.Counter(
        router.route(rating, "본문", category, risk, issue_tag_count=tags)
        for category, risk, rating, tags in rows
    )
    return {t: 100 * counts.get(t, 0) / len(rows) for t in ("T0", "T1", "T2", "T3")}


def test_티어_분포가_목표_범위_안에_있다():
    """목표 T1 85% / T2 10% / T3 5% (운영자 지시, 2026-08-25).

    표본이 60건이라 ±5%p 는 의미가 없다. 범위로 잠근다.
    """
    d = _distribution(REAL_TRAFFIC)
    assert d["T1"] >= 75, f"T1 이 너무 적다 — 원가가 뛴다: {d}"
    assert d["T2"] <= 20, f"T2(sonnet, haiku 의 3배)로 너무 많이 간다: {d}"
    assert d["T3"] <= 8, f"T3(opus, haiku 의 5배)로 너무 많이 간다: {d}"


def test_단순_불만은_T1_이다():
    """★ 이게 깨지면 '카테고리 = 티어' 로 되돌아간 것이다.

    "너무 짜서 먹기 힘들었습니다" 에 사과 답글 하나 쓰는 데 상위 모델이 필요 없다.
    """
    for rating, category in ((3, "COMPLAINT"), (3, "IMPROVEMENT"), (4, "IMPROVEMENT")):
        assert router.route(rating, "조금 아쉬웠어요", category, 1) == "T1"


def test_어려운_것만_상위_티어로_간다():
    assert router.route(1, "화가 나네요", "COMPLAINT", 2) == "T2"      # 강한 표현
    assert router.route(1, "면이 불었어요", "COMPLAINT", 1) == "T2"    # 별점 1~2
    assert router.route(5, "여러 문제", "IMPROVEMENT", 0, issue_tag_count=3) == "T2"


def test_T3는_risk3와_ABUSIVE_에만_쓴다():
    """★ risk 2 는 사람이 검수하는 초안이다. 최상위 모델을 쓸 이유가 없다."""
    assert router.route(1, "머리카락 나왔어요", "COMPLAINT", 3) == "T3"
    assert router.route(1, "XX놈들아", "ABUSIVE", 0) == "T3"
    assert router.route(1, "환불해주세요", "COMPLAINT", 2) == "T2"


def test_평균_원가가_기준선을_넘지_않는다():
    """실측 프롬프트 크기 기준. 프롬프트가 길어지면 여기서 먼저 걸린다."""
    d = _distribution(REAL_TRAFFIC)
    classify = llm.cost_krw("claude-haiku-4-5", 881, 130)
    gen = {
        "T0": 0.0,
        "T1": llm.cost_krw("claude-haiku-4-5", 1125, 120),
        "T2": llm.cost_krw("claude-sonnet-5", 1125, 120),
        "T3": llm.cost_krw("claude-opus-5", 1125, 120),
    }
    avg = sum(d[t] / 100 * (classify + gen[t]) for t in d)
    assert avg <= 6.5, f"리뷰 1건 평균 원가가 {avg:.2f}원이다 (기준 6.5원)"


@pytest.mark.parametrize("risk", (0, 1, 2, 3))
def test_어떤_경우에도_생성_티어가_비지_않는다(risk):
    tier = router.route(3, "본문", "COMPLAINT", risk)
    assert tier in ("T0", "T1", "T2", "T3")
