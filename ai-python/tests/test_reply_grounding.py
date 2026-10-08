"""실리뷰 평가에서 확인된 메뉴 목록·추천 대상의 근거 경계를 잠근다."""
import pytest

import main
import prompts


@pytest.mark.parametrize("platform", ["BAEMIN", "NAVER"])
def test_주문목록은_본문의_경험이나_평가대상을_특정하는_근거가_아니다(platform):
    review = main.ReviewIn(body="", rating=1, menus=["물냉면", "삼겹살"], platform=platform)
    system, user = prompts.build_generate_messages("COMPLAINT", review, main.PersonaIn(), "", platform=platform)
    assert "어떤 메뉴를 맛있게 먹었거나 함께 먹었다는 근거가 아니다" in system
    assert "목록의 특정 메뉴로 바꾸지 마라" in system
    assert "추천 대상" in system and "희망" in system
    assert "손님 본인의 취향·경험으로 바꾸지 마라" in system
    assert "[본문 없는 낮은 평가]" in system
    assert "원인이나 개선 조치를 만들지 마라" in system
    assert 'rating="1"' in user
    assert "물냉면" in user


def test_본문이_있거나_별점이_없으면_저평점무내용_지침을_주입하지_않는다():
    for rating, body, category in [(None, "", "COMPLAINT"), (5, "", "COMPLAINT"), (1, "맛있어요", "POSITIVE")]:
        review = main.ReviewIn(body=body, rating=rating, platform="BAEMIN")
        system, _ = prompts.build_generate_messages(category, review, main.PersonaIn(), "")
        assert "[본문 없는 낮은 평가]" not in system


def test_분류의_NOISE가_만족이나_불만원인을_뜻하지_않음을_명시한다():
    for system in (prompts.CLASSIFY_SYSTEM, prompts.CLASSIFY_SYSTEM_NAVER):
        assert "NOISE 는 만족을 뜻하지 않는다" in system
        assert "별점만으로 불만 원인·이슈 태그·위험 사유를 만들지 마라" in system
    assert prompts.PROMPT_VERSION == "v2.5"
    assert prompts.NAVER_PROMPT_VERSION == "naver-v0.7"
