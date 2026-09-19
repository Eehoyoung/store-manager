"""POST /internal/ai/analyze-and-draft 스텁 계약(스키마) 검증."""
from fastapi.testclient import TestClient
import pytest

import main
import prompts
from llm import LlmResult
from main import app

client = TestClient(app)
HEADERS = {"X-Internal-Token": "test-internal-token"}


def _payload(rating: int, body: str = "맛있게 잘 먹었습니다"):
    return {
        "reviewId": "r1",
        "storeId": "s1",
        "review": {"rating": rating, "body": body, "menus": ["돌솥알밥"], "platform": "BAEMIN"},
        "persona": {
            "tone": "FRIENDLY",
            "useEmoji": True,
            "emojiLevel": 1,
            "customerTitle": "고객님",
            "signature": None,
            "bannedWords": [],
            "lengthMin": 60,
            "lengthMax": 150,
            "personaSeed": 1,
        },
        "options": {"variants": 1, "instruction": None, "forceTier": None},
    }


def test_health():
    assert client.get("/health").json() == {"status": "ok"}


def test_positive_review_produces_one_passing_draft():
    res = client.post("/internal/ai/analyze-and-draft", json=_payload(rating=5), headers=HEADERS)
    assert res.status_code == 200
    body = res.json()
    assert body["analysis"]["category"] == "POSITIVE"
    assert body["blocked"] is False
    assert len(body["drafts"]) == 1
    assert body["drafts"][0]["guardrailFlags"] == []
    assert len(body["drafts"][0]["content"]) <= 280


def test_no_body_is_noise():
    res = client.post("/internal/ai/analyze-and-draft", json=_payload(rating=5, body=""), headers=HEADERS)
    assert res.json()["analysis"]["category"] == "NOISE"


def test_rating_3_is_improvement():
    assert client.post("/internal/ai/analyze-and-draft", json=_payload(rating=3), headers=HEADERS).json()["analysis"]["category"] == "IMPROVEMENT"


def test_rating_low_is_complaint():
    assert client.post("/internal/ai/analyze-and-draft", json=_payload(rating=1), headers=HEADERS).json()["analysis"]["category"] == "COMPLAINT"


def test_internal_endpoint_rejects_missing_token():
    assert client.post("/internal/ai/analyze-and-draft", json=_payload(rating=5)).status_code == 401


def test_variants_must_be_one():
    payload = _payload(rating=5)
    payload["options"]["variants"] = 2
    assert client.post("/internal/ai/analyze-and-draft", json=payload, headers=HEADERS).status_code == 422


def test_force_tier_rejects_unknown_value():
    payload = _payload(rating=5)
    payload["options"]["forceTier"] = "EXPENSIVE"
    assert client.post("/internal/ai/analyze-and-draft", json=payload, headers=HEADERS).status_code == 422


def test_recent_reply_duplicate_is_blocked(monkeypatch):
    duplicate = "고객님, 말씀해 주신 부분을 꼼꼼히 확인하고 더 나은 모습으로 정성껏 준비하겠습니다. 리뷰 남겨주셔서 감사합니다."

    class Provider:
        client = None

        def complete(self, system, user, model, max_tokens):
            return LlmResult(duplicate, model, 10, 5, 0.1)

    class Example:
        review_text = "이전 리뷰"
        reply_text = duplicate

    monkeypatch.setattr(main.llm, "get_provider", lambda: Provider())
    monkeypatch.setattr(main.rag, "fetch_examples", lambda *_args, **_kwargs: [Example()])

    res = client.post("/internal/ai/analyze-and-draft", json=_payload(rating=5), headers=HEADERS)
    assert res.status_code == 200
    assert res.json()["blocked"] is True
    assert "G7_DUPLICATE" in res.json()["blockReasons"]


@pytest.mark.parametrize("recover", [True, False])
def test_published_reply_retries_once_without_prompt_injection(monkeypatch, recover):
    duplicate = "고객님, 말씀해 주신 부분을 꼼꼼히 확인하고 더 나은 모습으로 정성껏 준비하겠습니다. 리뷰 남겨주셔서 감사합니다."
    different = "정성스러운 후기 덕분에 오늘도 힘이 납니다. 주문하신 메뉴를 맛있게 즐기셨다니 저희도 기쁩니다. 찾아주셔서 고맙습니다."
    calls = []

    class Provider:
        client = None

        def complete(self, system, user, model, max_tokens):
            assert duplicate not in system and duplicate not in user
            calls.append(model)
            return LlmResult(different if recover and len(calls) == 2 else duplicate, model, 10, 5, 0.1)

    monkeypatch.setattr(main.llm, "get_provider", lambda: Provider())
    monkeypatch.setattr(main.rag, "fetch_examples", lambda *_args, **_kwargs: [])
    payload = _payload(5)
    payload["recentReplies"] = [duplicate]
    response = client.post("/internal/ai/analyze-and-draft", json=payload, headers=HEADERS)
    assert response.status_code == 200
    result = response.json()
    assert len(calls) == 2
    assert result["blocked"] is not recover
    if recover:
        assert result["drafts"][0]["content"] == different
        assert result["drafts"][0]["costKrw"] == 0.2
    else:
        # ★ 2026-09-19: RETRY 를 소진해도 마지막 초안을 버리지 않는다. 중복 답글이라도
        #   사장님이 고쳐 쓸 문장이 있는 편이 낫고, 플래그가 남아 blocked=True 이므로
        #   자동 게시는 그대로 막힌다(실측에서 C-L-012 가 60자 하한으로 통째로 사라졌다).
        assert result["blocked"] is True
        assert result["drafts"][0]["content"] == duplicate
        assert result["drafts"][0]["guardrailFlags"] == ["G7_DUPLICATE"]
        assert "G7_DUPLICATE" in result["blockReasons"]


def test_recent_replies_optional_and_truncated():
    payload = _payload(5)
    assert main.AnalyzeAndDraftRequest(**payload).recent_replies == []
    payload["recentReplies"] = [str(i) for i in range(25)]
    assert main.AnalyzeAndDraftRequest(**payload).recent_replies == payload["recentReplies"][:20]
    assert client.post("/internal/ai/analyze-and-draft", json=payload, headers=HEADERS).status_code == 200


def test_recent_replies_and_samples_are_deduplicated(monkeypatch):
    from types import SimpleNamespace

    monkeypatch.setattr(main.rag, "fetch_examples", lambda *_args, **_kwargs: [
        SimpleNamespace(review_text="예시", reply_text="같은 답글"),
        SimpleNamespace(review_text="다른 예시", reply_text="샘플 답글"),
    ])
    payload = _payload(5)
    payload["recentReplies"] = ["게시 답글", "같은 답글", "게시 답글"]
    result = main._generate_draft(main.llm.StubProvider(), "T1", "POSITIVE",
                                  main.AnalyzeAndDraftRequest(**payload), 0)
    assert result[-1] == ["게시 답글", "같은 답글", "샘플 답글"]


def test_t0_template_also_checks_published_replies():
    payload = _payload(5, body="")
    first = client.post("/internal/ai/analyze-and-draft", json=payload, headers=HEADERS).json()
    payload["recentReplies"] = [first["drafts"][0]["content"]]
    result = client.post("/internal/ai/analyze-and-draft", json=payload, headers=HEADERS).json()
    assert result["blocked"] is True
    assert "G7_DUPLICATE" in result["blockReasons"]


# ── ABUSIVE 오판 구제 (2026-09-19 실측) ──────────────────────────────────
# F-003 "씹는데 이상한 게 걸려서 뱉어보니 플라스틱 같더라고요" 가 4회 중 2회만
# COMPLAINT 였다. 같은 리뷰가 절반은 초안을 받고 절반은 못 받는 상태였다.
def test_abusive_라도_실체있는_위험사유면_초안을_만든다():
    assert prompts.abusive_draft_category(["FOREIGN_OBJECT"]) == "COMPLAINT"
    assert prompts.abusive_draft_category(["HYGIENE", "THREAT"]) == "COMPLAINT"


def test_협박만_잡힌_abusive_는_초안을_만들지_않는다():
    # 사장님이 피해자인 사유뿐이면 사과 초안을 붙여 두지 않는다 — 검수자가 그대로 올릴 수 있다.
    assert prompts.abusive_draft_category(["THREAT"]) is None
    assert prompts.abusive_draft_category(["REVIEW_TRADE", "PRIVACY_LEAK"]) is None
    assert prompts.abusive_draft_category([]) is None
    assert prompts.abusive_draft_category(None) is None


def _abusive_provider(monkeypatch, text="죄송합니다. " + "확인하겠습니다 " * 6):
    class Provider:
        client = None

        def complete(self, system, user, model, max_tokens):
            return LlmResult(text, model, 10, 5, 0.1)

    monkeypatch.setattr(main.llm, "get_provider", lambda: Provider())
    monkeypatch.setattr(main.rag, "fetch_examples", lambda *_a, **_k: [])
    return Provider


def test_이물질_abusive_는_초안이_나오고_자동게시는_막힌다(monkeypatch):
    _abusive_provider(monkeypatch)
    monkeypatch.setattr(main, "_classify", lambda *_a, **_k: (
        prompts.ClassifyOutput(category="ABUSIVE", tone="CALM", sentiment=-0.9, issue_tags=["이물질"],
                               praised_tags=[], risk_level=3, risk_reasons=["FOREIGN_OBJECT"]),
        "claude-haiku-4-5", 10, 5, 0.1, 0))
    res = client.post("/internal/ai/analyze-and-draft",
                      json=_payload(1, "씹는데 이상한 게 걸려서 뱉어보니 플라스틱 같더라고요"),
                      headers=HEADERS).json()
    assert res["analysis"]["category"] == "ABUSIVE"      # 분석은 모델 판정 그대로 보고한다
    assert len(res["drafts"]) == 1                        # 초안은 만든다
    assert res["blocked"] is True                         # 절대규칙 3 — 자동 게시는 금지
    assert "G8_RISK" in res["blockReasons"]


def test_협박_abusive_는_여전히_초안이_없다(monkeypatch):
    _abusive_provider(monkeypatch)
    monkeypatch.setattr(main, "_classify", lambda *_a, **_k: (
        prompts.ClassifyOutput(category="ABUSIVE", tone="ANGRY", sentiment=-1.0, issue_tags=[],
                               praised_tags=[], risk_level=3, risk_reasons=["THREAT"]),
        "claude-haiku-4-5", 10, 5, 0.1, 0))
    res = client.post("/internal/ai/analyze-and-draft",
                      json=_payload(1, "가만 안 둘 테니까 각오해라"), headers=HEADERS).json()
    assert res["drafts"] == []
    assert res["blockReasons"] == ["ABUSIVE_MANUAL_REVIEW"]


def test_비꼼은_조롱이_아니라는_지침이_프롬프트에_있다():
    # ★ 실측(2026-09-19) S-005 "친절하시네요 전화해도 안 받으시는 거 보니" 가 4회 중 3회
    #   ABUSIVE 였다. 사람을 겨눈 반어가 '조롱' 문에 걸린다. 이 두 줄을 지우면 재발한다.
    assert "비꼼(반어)은 조롱이 아니다" in prompts.CLASSIFY_SYSTEM
    assert "비꼰 것은 여기 해당하지 않는다" in prompts.CLASSIFY_SYSTEM
