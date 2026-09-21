"""T0 룰 템플릿이 리뷰마다 갈리는지.

★ 실기동에서 나온 버그다(2026-09-20). "굳" 과 "👍" 두 리뷰가 **글자 하나 다르지 않은**
  답글을 받았다. render_t0_template 이 persona_seed 로만 골랐는데 그건 매장당 고정값이라,
  한 매장의 T0 리뷰가 전부 같은 문장을 받는 구조였다. 주석은 "반복을 피한다" 였다.

  매장 페이지는 공개돼 있다. 같은 문장이 쌓이면 자동 생성이라는 게 손님에게 그대로 보인다.
"""
import prompts

SHORT = ["굳", "👍", "좋아요~~", "맛있어요", "짱", "최고", "ㅎㅎ", "굿굿", "추천", "또 올게요"]


def _render(body: str, seed: int = 7, platform: str = "NAVER") -> str:
    return prompts.render_t0_template("고객님", seed, True, None, platform, body)


def test_같은_매장_안에서도_리뷰마다_문장이_갈린다():
    texts = {_render(b) for b in SHORT}
    # 5종 풀이므로 10건이면 최소 4종은 나와야 한다(전부 같던 이전과 대비).
    assert len(texts) >= 4, texts


def test_같은_리뷰는_항상_같은_문장이다():
    """★ crc32 를 쓰는 이유. 내장 hash() 는 프로세스마다 솔트가 달라 재기동하면 바뀐다."""
    assert _render("굳") == _render("굳")
    assert prompts.render_t0_template("고객님", 7, True, None, "NAVER", "굳") == _render("굳")


def test_배달도_같은_혜택을_받는다():
    texts = {_render(b, platform="BAEMIN") for b in SHORT}
    assert len(texts) >= 4, texts


def test_본문을_안_넘기면_종전과_동일하다():
    """기존 호출부가 그대로 돌아야 한다(review_body 기본값 "")."""
    for seed in range(len(prompts._T0_TEMPLATES_VISIT)):
        assert (prompts.render_t0_template("고객님", seed, True, None, "NAVER")
                == prompts._T0_TEMPLATES_VISIT[seed].format(title="고객님", emoji=" 😊"))


def test_방문판과_배달판은_여전히_갈린다():
    """★ 분리 원칙 회귀 — 본문 섞기가 플랫폼 분기를 덮지 않는다."""
    for b in SHORT:
        assert "주문" not in _render(b, platform="NAVER")
