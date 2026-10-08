"""T0 룰 템플릿이 리뷰마다 갈리는지.

★ 실기동에서 나온 버그다(2026-09-20). "굳" 과 "👍" 두 리뷰가 **글자 하나 다르지 않은**
  답글을 받았다. render_t0_template 이 persona_seed 로만 골랐는데 그건 매장당 고정값이라,
  한 매장의 T0 리뷰가 전부 같은 문장을 받는 구조였다. 주석은 "반복을 피한다" 였다.

  매장 페이지는 공개돼 있다. 같은 문장이 쌓이면 자동 생성이라는 게 손님에게 그대로 보인다.
"""
import prompts
import guardrails
import pytest

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


@pytest.mark.parametrize("rating", [0, 1, 2])
@pytest.mark.parametrize("platform", ["BAEMIN", "NAVER"])
def test_저평점_무내용은_기대불일치에_사과하고_의견을_요청한다(rating, platform):
    for seed in range(5):
        reply = prompts.render_t0_template("고객님", seed, True, None, platform, "",
                                           rating=rating, category="NOISE")
        assert "죄송" in reply
        assert "알려" in reply or "말씀" in reply
        for unsupported in ("반가운", "감사", "맛있", "든든", "배달", "고기", "😊", "다시 찾아"):
            assert unsupported not in reply
        assert guardrails.check(reply, 0, min_length=guardrails.min_length_for("NOISE")) == []


@pytest.mark.parametrize("rating", [None, 3, 4, 5])
def test_별점미상과_보통이상평가의_T0는_기존과_동일하다(rating):
    assert (prompts.render_t0_template("고객님", 1, True, None, "BAEMIN", "", rating=rating)
            == prompts.render_t0_template("고객님", 1, True, None, "BAEMIN", ""))


def test_저평점_이모지뿐인_NOISE에도_감사대신_내용확인요청을_쓴다():
    reply = prompts.render_t0_template("고객님", 1, True, None, "BAEMIN", "ㅠㅠ",
                                      rating=1, category="NOISE")
    assert "죄송" in reply and "감사" not in reply


def test_본문의_명시적인_칭찬은_별점만으로_없는_불만을_만들지_않는다():
    assert prompts.low_rating_noise(1, "맛있어요", "POSITIVE") is False


@pytest.mark.parametrize("rating,pool_size", [(5, 5), (1, 3)])
def test_같은매장_무본문도_리뷰ID로_템플릿을_분산한다(rating, pool_size):
    ids = [f"G-{number:04d}" for number in range(1, 101)]
    replies = [prompts.render_t0_template("고객님", 17, True, None, "BAEMIN", "",
               rating=rating, category="NOISE", review_id=rid) for rid in ids]
    assert len(set(replies)) == pool_size
    assert all(rid not in reply for rid, reply in zip(ids, replies))
    if rating == 1:
        assert all("죄송" in reply and "😊" not in reply for reply in replies)


def test_무본문_동일리뷰ID는_재기동해도_같은문구를_고른다():
    args = {"rating": 5, "category": "NOISE", "review_id": "G-0001"}
    assert (prompts.render_t0_template("고객님", 17, True, None, "BAEMIN", "", **args)
            == prompts.render_t0_template("고객님", 17, True, None, "BAEMIN", "", **args))


def test_본문있는리뷰의_템플릿은_ID추가로_바뀌지않는다():
    before = prompts.render_t0_template("고객님", 17, True, None, "BAEMIN", "👍", rating=5)
    for rid in ("G-0001", "G-0002"):
        assert before == prompts.render_t0_template("고객님", 17, True, None, "BAEMIN", "👍",
                                                    rating=5, review_id=rid)


def test_리뷰ID없는_기존무본문_호출은_변하지않는다():
    for seed in range(5):
        before = prompts.render_t0_template("고객님", seed, True, None, "BAEMIN", "")
        assert before == prompts._T0_TEMPLATES[seed].format(title="고객님", emoji=" 😊")
