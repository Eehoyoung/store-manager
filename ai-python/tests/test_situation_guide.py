"""상황별 지침(issue_tags → 답글 지침) 회귀 테스트.

★ 왜 생겼나: CATEGORY_GUIDE 만으로는 "국물이 샜다" 와 "배달이 좀 늦었다" 가 같은
  COMPLAINT 한 줄로 뭉뚱그려졌다. 태그 사전에 '누락'·'새어나옴' 이 있는데도
  생성 프롬프트로 흘러가지 않고 있었다(2026-08-23, 사장/소비자 관점 검토에서 공통 지적).

★ 비용 주의: 상황 지침은 해당 태그가 있을 때만 붙는다. 전부 상시 주입하면 답글 한 건마다
  프롬프트가 길어져 매 호출 비용이 오른다.
"""
import prompts


class _Persona:
    tone = "POLITE"
    use_emoji = True
    emoji_level = 1
    customer_title = "고객님"
    signature = None
    opening_style = None
    banned_words: list[str] = []
    length_min = 60
    length_max = 150
    persona_seed = 1


class _Review:
    rating = 1
    body = "국물이 다 새서 봉투 안이 엉망이었어요"
    menus = ["김치찌개"]


def _build(tags):
    system, _ = prompts.build_generate_messages("COMPLAINT", _Review(), _Persona(), "", tags)
    return system


def test_해당_태그가_있을_때만_상황지침이_붙는다():
    assert "[이 리뷰의 상황]" in _build(["새어나옴"])
    assert "[이 리뷰의 상황]" in _build(["누락"])
    # ★ 2026-08-25: 라우팅이 단순 불만을 T1(haiku)로 내리면서 '맛'·'양'·'간'·'조리상태'
    #   등 품질 태그에도 지침이 생겼다. 상위 모델이 알아서 해주던 몫을 지침이 대신한다.
    assert "[이 리뷰의 상황]" in _build(["조리상태"])
    assert "[이 리뷰의 상황]" in _build(["간"])
    # 상황 지침이 없는 태그는 프롬프트를 늘리지 않는다 — 매 호출 비용이 걸린 문제다.
    # (긍정 태그는 카테고리 지침만으로 충분하다)
    assert "[이 리뷰의 상황]" not in _build(["배달빠름", "서비스증정"])
    assert "[이 리뷰의 상황]" not in _build([])
    assert "[이 리뷰의 상황]" not in _build(None)


def test_국물샘과_누락은_서로_다른_지침을_받는다():
    """같은 COMPLAINT 라도 답글이 달라야 한다. 이게 이 기능의 존재 이유다."""
    leak = _build(["새어나옴"])
    missing = _build(["누락"])
    assert "포장 마감" in leak
    assert "출고 전 확인" in missing
    assert leak != missing


def test_어떤_상황지침도_금전_보상을_약속하지_않는다():
    """절대규칙 4. 지침에 환불·보상 표현이 새어들면 답글이 그대로 약속하게 된다."""
    forbidden = ("환불", "보상", "할인", "쿠폰", "무료", "돌려드리", "배상")
    for tag, guide in prompts.SITUATION_GUIDE.items():
        for word in forbidden:
            assert word not in guide, f"{tag} 지침에 금전 표현: {word}"


def test_상황지침_태그는_전부_태그사전에_있다():
    """사전에 없는 태그로 지침을 만들면 분류기가 그 태그를 낼 수 없어 영원히 안 쓰인다."""
    for tag in prompts.SITUATION_GUIDE:
        assert tag in prompts.ISSUE_TAG_DICT, f"태그 사전에 없음: {tag}"


def test_책임회피_표현을_금지한다():
    """소비자 관점 검토: '배달 특성상' 같은 정황 설명은 변명으로 읽혀 역효과다."""
    system = _build(["새어나옴"])
    assert "배달 특성상" in system  # 금지 대상으로 명시돼 있어야 한다
    assert "주어는 매장" in system


def test_사과뒤_재방문권유를_막는다():
    """소비자 관점 검토: 사과 직후 영업 멘트는 문제를 가볍게 여기는 것으로 읽힌다."""
    assert "재방문 권유나 칭찬조 문장을 붙이지 마라" in _build(["누락"])


def test_칭찬은_여전히_재방문을_권한다():
    """COMPLAINT 만 막는다. PRAISE 까지 막으면 정상 답글이 밋밋해진다."""
    assert "재방문" in prompts.CATEGORY_GUIDE["PRAISE"]
