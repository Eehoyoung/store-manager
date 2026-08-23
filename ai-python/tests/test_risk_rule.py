"""절대규칙 3 을 지키는 코드의 회귀 테스트.

★ 이 룰이 제품에서 가장 중요한 안전장치다. 위생·이물질·식중독·법적분쟁 리뷰가 자동으로
  게시되면 매장이 끝난다. 그런데 이 함수에는 테스트가 하나도 없었다(2026-08-23 발견).

★ 모델은 이 판단을 믿을 수 없다. 실측으로 확인했다 — claude-haiku-4-5 는
  "이거 완전 사기다 고소할 거야 각오해라" 를 risk_level=1 로 분류했다.
  키워드 룰이 3 으로 승격하지 않았다면 그 리뷰는 자동 게시됐을 것이다.

★ 이 테스트는 API 를 호출하지 않는다. 비용 0 이므로 CI 에서 항상 돌려야 한다.
  골든셋 평가(유료)를 못 돌리는 상황에서도 안전은 여기서 지켜진다.
"""
import pytest

from prompts import RISK_REASON_VALUES, upgrade_risk_level

# docs/12 §1.2 의 트리거 키워드 전종. 하나라도 빠지면 그 유형이 자동 게시된다.
_MUST_BLOCK = [
    # 식중독
    ("식중독 걸렸어요", "FOOD_POISONING"),
    ("먹고 배탈났습니다", "FOOD_POISONING"),
    ("밤새 설사했어요", "FOOD_POISONING"),
    ("계속 구토했습니다", "FOOD_POISONING"),
    ("병원 다녀왔어요", "FOOD_POISONING"),
    ("응급실 갈 뻔했습니다", "FOOD_POISONING"),
    ("진단서 받아뒀습니다", "FOOD_POISONING"),
    # 이물질
    ("이물질이 나왔어요", "FOREIGN_OBJECT"),
    ("벌레가 들어있었어요", "FOREIGN_OBJECT"),
    ("머리카락 나왔습니다", "FOREIGN_OBJECT"),
    ("비닐 조각이 있었어요", "FOREIGN_OBJECT"),
    ("플라스틱이 씹혔어요", "FOREIGN_OBJECT"),
    ("유리 조각이 나왔어요", "FOREIGN_OBJECT"),
    # 위생
    ("위생 상태가 심각해요", "HYGIENE"),
    ("보건소에 알려야겠어요", "HYGIENE"),
    ("식약처에 문의하겠습니다", "HYGIENE"),
    # 법적분쟁
    ("신고하겠습니다", "LEGAL"),
    ("고소할 거예요", "LEGAL"),
    ("고발하겠습니다", "LEGAL"),
    ("소송 준비 중입니다", "LEGAL"),
    ("변호사와 상담했습니다", "LEGAL"),
    ("소비자원에 접수했어요", "LEGAL"),
    ("방송에 제보하겠습니다", "LEGAL"),
    ("기자에게 알리겠습니다", "LEGAL"),
    ("커뮤니티에 올리겠습니다", "LEGAL"),
]


@pytest.mark.parametrize("text,expected_reason", _MUST_BLOCK)
def test_위험_키워드는_무조건_risk3(text, expected_reason):
    level, reasons = upgrade_risk_level(text, base_level=0)
    assert level == 3, f"자동 게시될 뻔했다: {text}"
    assert expected_reason in reasons


def test_모델이_0으로_판단해도_승격한다():
    """실측 사례. 모델은 이 문장을 risk_level=1 로 봤다."""
    level, reasons = upgrade_risk_level("이거 완전 사기다 고소할 거야 각오해라", base_level=0)
    assert level == 3
    assert "LEGAL" in reasons


def test_하향은_절대_하지_않는다():
    """모델이 3 으로 봤는데 키워드가 없다고 낮추면, 모델만 아는 위험을 놓친다."""
    level, reasons = upgrade_risk_level("맛있게 잘 먹었습니다", base_level=3)
    assert level == 3
    assert reasons == []


def test_평범한_리뷰는_올리지_않는다():
    """모든 것을 3 으로 만들면 자동 게시가 전부 멈춰 제품이 동작하지 않는다."""
    for text in ["맛있어요", "배달이 조금 늦었어요", "양이 적네요", "다시 시켜먹을게요"]:
        level, reasons = upgrade_risk_level(text, base_level=0)
        assert level == 0, f"과잉 차단: {text}"
        assert reasons == []


def test_reasons_는_DB_허용값만_쓴다():
    """review_analysis.risk_reasons 는 CHECK 제약이 걸려 있다. 목록 밖 값이 나오면 적재가 실패한다."""
    for text, _ in _MUST_BLOCK:
        _, reasons = upgrade_risk_level(text, base_level=0)
        assert set(reasons) <= set(RISK_REASON_VALUES)


def test_빈_입력에서_죽지_않는다():
    """본문 없는 사진 리뷰는 실제로 들어온다(실측 확인)."""
    assert upgrade_risk_level("", base_level=0) == (0, [])
    assert upgrade_risk_level(None, base_level=0) == (0, [])
