"""별점이 없는 리뷰(rating=None)를 0 으로 접지 않는다.

★ 실기동에서 나온 버그다(2026-09-20). 네이버에는 별점이 안 붙는 리뷰가 실제로
  있는데(저장본 10건 중 2건) 확장·Spring 이 각각 None → 0 으로 접었다. 그 결과

    rating 0 → _stub_classify → COMPLAINT, risk 1        (11건 전부)
    rating 0 → router.route(COMPLAINT, <=2) → T2(sonnet)  (T1 의 1.4배)

  칭찬 리뷰에 사과 답글이 붙고 원가가 조용히 올랐다. 0 은 "최악의 평점" 이지
  "모름" 이 아니다.
"""
import main
import prompts
import router


class _Review:
    def __init__(self, rating, body="맛있게 잘 먹었어요", menus=None, platform="NAVER"):
        self.rating = rating
        self.body = body
        self.menus = menus or []
        self.platform = platform


def test_모르는_별점은_없음으로_렌더된다():
    _, user = prompts.build_classify_messages("맛있어요", rating=None)
    assert 'rating="없음"' in user
    assert 'rating="0"' not in user


def test_별점이_있으면_숫자_그대로다():
    _, user = prompts.build_classify_messages("맛있어요", rating=0)
    assert 'rating="0"' in user
    _, user5 = prompts.build_classify_messages("맛있어요", rating=5)
    assert 'rating="5"' in user5


def test_스텁은_별점이_없으면_불만으로_단정하지_않는다():
    out = main._stub_classify(_Review(None))
    assert out.category != "COMPLAINT"
    assert out.risk_level == 0


def test_스텁은_별점_0을_여전히_불만으로_본다():
    """★ 0 은 진짜 최저 평점이다. None 과 같이 취급하면 반대 방향 사고가 난다."""
    assert main._stub_classify(_Review(0)).category == "COMPLAINT"


def test_별점_미상은_저평점_승급을_타지_않는다():
    assert router.route(rating=None, body="좀 아쉬웠어요", category="COMPLAINT", risk_level=1) == "T1"
    assert router.route(rating=1, body="좀 아쉬웠어요", category="COMPLAINT", risk_level=1) == "T2"


def test_별점_미상이_안전_경로를_약화시키지_않는다():
    """★ 위험도는 별점이 아니라 본문에서 온다. 별점이 없어도 T3 는 그대로다."""
    assert router.route(rating=None, body="벌레가 나왔어요", category="COMPLAINT", risk_level=3) == "T3"
    assert prompts.upgrade_risk_level("음식에서 벌레가 나왔어요", 0)[0] == 3


def test_ReviewIn_은_별점_없이도_역직렬화된다():
    r = main.ReviewIn(body="맛있어요", platform="NAVER")
    assert r.rating is None
