"""네이버 스마트플레이스(방문 리뷰) 분기가 배달 3사 경로를 조금도 건드리지 않았는지 잠근다.

★ 왜 스냅샷인가 — 골든셋 564건 재검증은 유료(약 1,888원)다. 배달 프롬프트 문자열이
  바이트 단위로 지금과 완전히 같으면 재검증은 논리적으로 불필요하다. 한 글자라도
  바뀌면 이 테스트가 실패해서 알려준다 — "바뀐 줄도 몰랐다" 를 막는 것이 이 파일의
  존재 이유다. tests/fixtures/ 의 스냅샷은 네이버 작업 착수 직전(2026-09-19) 워킹트리의
  CLASSIFY_SYSTEM·SITUATION_GUIDE(배달) 를 그대로 떴다.
"""
import json
from pathlib import Path

import prompts

FIXTURES = Path(__file__).parent / "fixtures"


def test_배달_분류_프롬프트는_스냅샷과_바이트단위로_동일하다():
    snapshot = (FIXTURES / "classify_system_delivery.txt").read_text(encoding="utf-8")
    assert prompts.CLASSIFY_SYSTEM == snapshot, (
        "CLASSIFY_SYSTEM(배달)이 스냅샷과 달라졌다. 네이버 분기는 CLASSIFY_SYSTEM_NAVER 를 "
        "별도로 파생시켜야 하고, 이 상수 자체는 한 글자도 바뀌면 안 된다 — 바뀌면 골든셋 "
        "564건 유료 재검증(약 1,888원) 없이는 회귀 여부를 알 수 없다.\n"
        "\n"
        "★ 일부러 바꾼 것이라면 픽스처만 갱신하고 넘어가지 마라. 두 가지를 더 해야 한다:\n"
        "  1) 골든셋 재검증 — 배달 라인(v2.x)의 회귀 여부는 이 스냅샷이 유일한 무료 근거였다.\n"
        "  2) prompts.NAVER_PROMPT_VERSION 을 올려라 — 네이버 프롬프트는 이 상수에서 문자열\n"
        "     치환으로 **파생**되므로 내용이 함께 바뀐다. 버전을 그대로 두면 같은 값이 서로\n"
        "     다른 프롬프트를 가리키게 되고, DB 의 prompt_version 으로 초안 계보를 추적할 수 없다."
    )


def test_배달_상황지침_사전은_스냅샷과_바이트단위로_동일하다():
    snapshot = json.loads((FIXTURES / "situation_guide_delivery.json").read_text(encoding="utf-8"))
    assert prompts.SITUATION_GUIDE == snapshot, (
        "SITUATION_GUIDE(배달)가 스냅샷과 달라졌다. 네이버 전용 문구는 SITUATION_GUIDE_NAVER 로만 넣어야 한다."
    )


def test_배달_경로로_만든_생성_프롬프트도_platform_인자와_무관하게_동일하다():
    """build_generate_messages 에 platform 을 아예 안 넘긴 기존 호출부와,
    명시적으로 'BAEMIN'/'YOGIYO'/'COUPANGEATS' 를 넘긴 호출이 같은 문자열을 내야 한다."""

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

    baseline, _ = prompts.build_generate_messages(
        "COMPLAINT", _Review(), _Persona(), "", ["새어나옴"], None, [], "r1", "CALM", []
    )
    for platform in (None, "BAEMIN", "YOGIYO", "COUPANGEATS", "", "unknown-future-platform"):
        system, _ = prompts.build_generate_messages(
            "COMPLAINT", _Review(), _Persona(), "", ["새어나옴"], None, [], "r1", "CALM", [],
            platform,
        )
        assert system == baseline, platform


# ── (b) 네이버 분류 프롬프트가 Haiku 4.5 캐시 최소 길이 아래로 내려가지 않는다 ──────
# tests/test_prompt_cache_floor.py 와 같은 상수를 쓴다(사본을 만들지 않는다).
from tests.test_prompt_cache_floor import MIN_CHARS  # noqa: E402


def test_네이버_분류_프롬프트도_haiku_캐시_최소길이_이상이다():
    n = len(prompts.CLASSIFY_SYSTEM_NAVER)
    assert n >= MIN_CHARS, (
        f"CLASSIFY_SYSTEM_NAVER 가 {n}자다. {MIN_CHARS}자 미만이면 네이버 리뷰도 프롬프트 "
        "캐시가 조용히 꺼져 원가가 4배로 뛴다."
    )


# ── (c) is_visit_platform ────────────────────────────────────────────────────


def test_is_visit_platform_은_NAVER만_True_이고_나머지는_전부_fail_safe():
    assert prompts.is_visit_platform("NAVER") is True
    for bad in (None, "", "BAEMIN", "YOGIYO", "COUPANGEATS", "naver", "Naver", "UNKNOWN"):
        assert prompts.is_visit_platform(bad) is False, bad


# ── (d)(e) issue_tags_for ─────────────────────────────────────────────────────


def test_issue_tags_for_배달은_기존_목록_그대로다():
    assert prompts.issue_tags_for("BAEMIN") == prompts.ISSUE_TAG_DICT
    assert prompts.issue_tags_for("YOGIYO") == prompts.ISSUE_TAG_DICT
    assert prompts.issue_tags_for(None) == prompts.ISSUE_TAG_DICT


def test_issue_tags_for_네이버는_배달전용_없이_방문태그를_포함한다():
    tags = prompts.issue_tags_for("NAVER")
    # ★ 2026-09-19: '용기' 를 뺐다. 네이버 영수증 리뷰에는 포장 주문이 함께 들어오고,
    #   포장상태·새어나옴·일회용품누락을 남기면서 '용기' 만 지운 것은 사전 자기모순이었다.
    for delivery_only in ("배달지연", "배달빠름", "기사응대", "오배송", "최소주문금액"):
        assert delivery_only not in tags, delivery_only
    for visit_only in ("대기시간", "주차", "좌석", "소음"):
        assert visit_only in tags, visit_only
    # 공통 태그는 그대로 남는다
    for common in ("맛", "양", "온도", "청결", "이물질"):
        assert common in tags, common


# ── (f) situation_guide_for('NAVER') ─────────────────────────────────────────


def test_네이버_상황지침에_배달_전용_안내_문구가_없다():
    guide = prompts.situation_guide_for("NAVER")
    for tag, text in guide.items():
        assert "주문하신 앱" not in text, (tag, text)
        assert "주문 요청사항" not in text, (tag, text)
        assert "요청사항 칸" not in text, (tag, text)


def test_네이버_상황지침_사전은_방문_태그와_1대1이다():
    guide = prompts.situation_guide_for("NAVER")
    for tag in guide:
        assert tag in prompts.ISSUE_TAG_DICT_NAVER, tag
    # 배달 전용이라 뺀 태그는 애초에 지침도 없어야 한다
    assert "기사응대" not in guide
    assert "오배송" not in guide


# ── (g) 신규 방문 지침은 금전 표현을 긍정으로도 부정으로도 쓰지 않는다 ──────────


def test_신규_방문_지침에_금전_표현이_없다():
    forbidden = ("환불", "보상", "할인", "쿠폰", "무료", "돌려드리", "배상")
    for tag in ("대기시간", "주차", "좌석", "소음"):
        guide = prompts.SITUATION_GUIDE_NAVER[tag]
        for word in forbidden:
            assert word not in guide, f"{tag} 지침에 금전 표현: {word}"
        # 확정하지 않은 시설 개선을 약속하지 않는다([절대 규칙] 8번과 같은 취지)
        assert "약속" in guide or "확정" in guide, tag


def test_네이버_전용_생성_프롬프트에_방문_지침이_들어간다():
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
        rating = 2
        body = "주차하기 너무 불편하고 웨이팅도 길었어요"
        menus: list[str] = []

    system, _ = prompts.build_generate_messages(
        "COMPLAINT", _Review(), _Persona(), "", ["주차", "대기시간"], None, [], "r1", "CALM", [],
        "NAVER",
    )
    assert "[이 리뷰의 상황]" in system
    situation_block = system[system.index("[이 리뷰의 상황]"):]
    situation_block = situation_block[:situation_block.index("\n\n", 1)]
    # [절대 규칙] 1번은 원래 모든 답글에 '환불·보상' 금지 문구를 담는다 — 여기서 보는 것은
    # 그 상시 규칙이 아니라 새로 넣은 [이 리뷰의 상황] 블록 자체가 오염되지 않았는가다.
    for word in ("환불", "보상", "할인", "쿠폰", "무료"):
        assert word not in situation_block


# ── (h) 스텁 경로 — platform='NAVER' 요청이 정상 응답한다 ────────────────────


def test_스텁_경로에서_NAVER_요청이_정상_응답한다():
    from fastapi.testclient import TestClient

    import main

    main.INTERNAL_TOKEN = main.INTERNAL_TOKEN or "test-internal-token"
    client = TestClient(main.app)
    hdr = {"X-Internal-Token": main.INTERNAL_TOKEN}
    payload = {
        "reviewId": "n1", "storeId": "1",
        "review": {"rating": 2, "body": "주차가 너무 불편했어요 자리도 좁고", "menus": [], "platform": "NAVER"},
        "persona": {
            "tone": "FRIENDLY", "useEmoji": True, "emojiLevel": 1, "customerTitle": "고객님",
            "signature": None, "bannedWords": [], "lengthMin": 60, "lengthMax": 150, "personaSeed": 1,
        },
        "options": {"variants": 1, "instruction": None, "forceTier": None},
    }
    res = client.post("/internal/ai/analyze-and-draft", json=payload, headers=hdr)
    assert res.status_code == 200
    body = res.json()
    # 스텁(ANTHROPIC_API_KEY 없음) 경로이므로 rating 기반 결정적 분류 — 카테고리는
    # main._stub_classify 계약 그대로다. 여기서 확인하려는 것은 NAVER 가 예외 없이 돈다는 것.
    assert body["analysis"]["promptVersion"] == prompts.NAVER_PROMPT_VERSION
    assert len(body["drafts"]) <= 1
    if body["drafts"]:
        assert len(body["drafts"][0]["content"]) <= 280


def test_prompt_version_for_는_배달_상수를_바꾸지_않는다():
    assert prompts.prompt_version_for("BAEMIN") == prompts.PROMPT_VERSION
    assert prompts.prompt_version_for(None) == prompts.PROMPT_VERSION
    assert prompts.prompt_version_for("NAVER") == prompts.NAVER_PROMPT_VERSION


def test_네이버_프롬프트_버전은_배달과_독립된_라인이다():
    """★ 네이버는 naver-v0.x, 배달은 v2.x 로 **버전 라인 자체가 다르다.**

    배달의 2.2 는 골든셋 564건 실측으로 번 숫자다. 네이버 분기는 측정을 한 번도
    하지 않았으므로 같은 번호를 빌리면 DB 를 읽는 사람이 없는 검증 이력을 가정한다.
    접미사 방식("v2.2-naver")으로 되돌리지 말 것 — 배달이 v2.3 이 되는 순간
    네이버 지침을 건드리지 않았는데도 버전이 따라 올라간다.
    """
    naver = prompts.prompt_version_for("NAVER")
    delivery = prompts.prompt_version_for("BAEMIN")

    assert naver == prompts.NAVER_PROMPT_VERSION
    assert delivery == prompts.PROMPT_VERSION
    assert naver != delivery
    # 배달 버전 문자열을 품고 있으면 접미사 방식으로 되돌아간 것이다.
    assert prompts.PROMPT_VERSION not in naver, (
        f"네이버 버전 {naver!r} 이 배달 버전 {prompts.PROMPT_VERSION!r} 을 포함한다. "
        "두 라인은 독립이어야 한다."
    )
    # review_analysis.prompt_version / naver_review_event.prompt_version 둘 다 VARCHAR(20) 이다.
    assert len(naver) <= 20, f"{naver!r} 이 DB 컬럼(VARCHAR(20))을 넘는다."


# ── 생성 프롬프트 분리 (2026-09-19) ─────────────────────────────────────────
# ★ 기존 테스트는 [이 리뷰의 상황] 블록만 봤다. 그래서 프롬프트 첫 줄이
#   "너는 배달앱 리뷰에 답글을 작성한다" 인 채로 14건이 전부 통과했다.
#   가장 큰 구멍을 안 덮는 테스트는 없는 것보다 나쁘다 — 초록불이 거짓말을 한다.
_DELIVERY_WORDS = ("배달", "기사", "라이더", "배송")


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


def _build(platform, issue_tags, body="웨이팅 40분에 음식도 식어서 나왔어요"):
    class _Review:
        rating = 2
        menus: list[str] = []

    _Review.body = body
    system, _ = prompts.build_generate_messages(
        "COMPLAINT", _Review(), _Persona(), "", issue_tags, None, [], "r1", "DISAPPOINTED", [], platform,
    )
    return system


def test_방문_생성_프롬프트에_배달_어휘가_하나도_없다():
    # 상황 지침이 붙는 태그를 최대한 태워 본다(온도·포장상태·새어나옴·조리상태가 배달판이었다).
    system = _build("NAVER", ["대기시간", "온도", "포장상태", "새어나옴", "조리상태", "용기", "주차"])
    hits = [ln.strip() for ln in system.splitlines() if any(w in ln for w in _DELIVERY_WORDS)]
    assert not hits, "방문 생성 프롬프트에 배달 어휘가 남았다:\n" + "\n".join(hits)


def test_방문_생성_프롬프트_첫_줄이_방문판이다():
    assert _build("NAVER", ["주차"]).splitlines()[0] == (
        "너는 매장 사장님을 대신해 네이버 플레이스 방문 리뷰에 답글을 작성한다."
    )


def test_온도_지침이_배달_소요를_변명으로_주지_않는다():
    # ★ 홀에서 식어 나온 음식은 전적으로 매장 책임이다. 배달판 지침은
    #   "배달 소요는 매장이 통제하지 못하는 부분이 있으니" 로 없는 변명을 쥐여줬다.
    guide = prompts.SITUATION_GUIDE_NAVER["온도"]
    for word in _DELIVERY_WORDS:
        assert word not in guide, guide
    assert "매장이 책임지는" in guide


def test_배달_생성_프롬프트는_NAVER_외_모든_값에서_바이트_단위로_같다():
    baseline = _build(None, ["새어나옴", "온도"])
    for platform in ("BAEMIN", "YOGIYO", "COUPANGEATS", "", "naver", "NAVER_PLACE"):
        assert _build(platform, ["새어나옴", "온도"]) == baseline, platform
    assert _build("NAVER", ["새어나옴", "온도"]) != baseline


def test_T0_방문판에는_주문_어휘가_없다():
    # T0 은 LLM 을 타지 않는 룰 템플릿이라 프롬프트를 갈라도 여기는 갈라지지 않는다.
    for seed in range(len(prompts._T0_TEMPLATES_VISIT)):
        text = prompts.render_t0_template("고객님", seed, True, None, "NAVER")
        assert "주문" not in text, text
        assert len(text) >= 60, text  # 폴백 경로에서 COMPLAINT 하한을 넘어야 한다
    assert "주문" in prompts.render_t0_template("고객님", 1, True, None)  # 배달판은 그대로


def test_분류_치환_원문이_전부_배달_프롬프트에_실재한다():
    # ★ 이 파생 방식의 유일한 약점은 **조용한 실패**다. 배달 원문이 한 글자만 바뀌어도
    #   replace 가 no-op 이 되고 배달 전제가 네이버에 그대로 남는데, 스냅샷 테스트는
    #   "배달이 바뀌었다" 만 잡고 "치환이 빗나갔다" 는 못 잡는다(방향이 반대다).
    for old, _new in prompts._NAVER_CLASSIFY_SWAPS:
        assert old in prompts.CLASSIFY_SYSTEM, f"치환 원문이 배달 프롬프트에 없다: {old[:60]!r}"


def test_방문_분류_프롬프트에_배달_전용_어휘가_남지_않는다():
    s = prompts.CLASSIFY_SYSTEM_NAVER
    for word in ("배달", "기사응대", "라이더", "오배송", "배달지연"):
        assert word not in s, [ln for ln in s.splitlines() if word in ln]
