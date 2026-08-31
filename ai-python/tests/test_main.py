"""POST /internal/ai/analyze-and-draft 스텁 계약(스키마) 검증."""
from fastapi.testclient import TestClient
import pytest

import main
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
        assert result["drafts"] == []
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
