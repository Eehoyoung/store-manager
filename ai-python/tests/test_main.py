"""POST /internal/ai/analyze-and-draft 스텁 계약(스키마) 검증."""
from fastapi.testclient import TestClient

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
