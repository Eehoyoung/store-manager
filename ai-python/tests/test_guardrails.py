"""guardrails.py 단위 테스트 — 절대규칙 3·4 구현체이므로 최우선 검증 대상."""
from guardrails import check


def test_clean_text_passes():
    assert check("고객님, 맛있게 드셨다니 기뻐요 :)", risk_level=0) == []


def test_length_over_280_blocked():
    assert "G1_LENGTH" in check("가" * 281, risk_level=0)


def test_empty_text_blocked():
    assert "G1_LENGTH" in check("", risk_level=0)


def test_compensation_promise_blocked():
    assert "G3_COMPENSATION" in check("불편을 드려 환불해 드리겠습니다", risk_level=0)
    assert "G3_COMPENSATION" in check("포인트로 적립해드리겠습니다", risk_level=0)


def test_competitor_platform_blocked():
    for name in ["배민에서도", "요기요 리뷰", "쿠팡이츠로도", "땡겨요에서"]:
        assert "G5_COMPETITOR" in check(f"{name} 자주 찾아주세요", risk_level=0)


def test_phone_number_blocked():
    assert "G4_PHONE" in check("문의는 010-1234-5678 로 연락주세요", risk_level=0)


def test_high_risk_level_blocked_regardless_of_text():
    """절대규칙 3: risk_level >= 3 이면 무조건 자동게시 금지 플래그."""
    assert "G8_RISK" in check("정상적인 답글입니다", risk_level=3)
    assert "G8_RISK" not in check("정상적인 답글입니다", risk_level=2)


def test_multiple_violations_all_reported():
    text = "환불해 드리겠습니다. 010-1234-5678 로 연락, 요기요 이벤트도 참여하세요"
    flags = check(text, risk_level=0)
    assert "G3_COMPENSATION" in flags
    assert "G4_PHONE" in flags
    assert "G5_COMPETITOR" in flags
