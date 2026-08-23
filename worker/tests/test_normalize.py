"""normalize.normalize_stores() 회귀 테스트 — docs/13 §11.2(collect-result 스키마) 계약과
docs/08 §2.3(플랫폼별 필드 지원)을 3사 픽스처로 검증한다."""
import json
from pathlib import Path

from dataapi import parse_envelope
from normalize import normalize_stores

FIXTURES = Path(__file__).parent / "fixtures"

REQUIRED_REVIEW_KEYS = {
    "platformReviewId", "rating", "body", "authorRaw", "orderedMenus", "menus",
    "imageUrls", "platformExtra", "reviewStatus", "writtenDate", "existingReply",
}
REQUIRED_STORE_KEYS = {"platformStoreId", "storeName", "avgRating", "reviews"}


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def test_baemin_schema_keys_and_unmasked_nickname_passthrough():
    data = parse_envelope(_load("baemin_reviews.json"))
    stores = normalize_stores(data, "baemin")
    assert len(stores) == 1
    store = stores[0]
    assert set(store.keys()) == REQUIRED_STORE_KEYS
    assert store["platformStoreId"] == "14292949"
    assert store["avgRating"] == 4.6
    assert len(store["reviews"]) == 2

    review = store["reviews"][0]
    assert set(review.keys()) == REQUIRED_REVIEW_KEYS
    assert review["platformReviewId"] == "2024033100520097"
    assert review["rating"] == 5
    assert review["authorRaw"] == "히리릴"  # 배민은 가공하지 않고 원문 그대로(F-5, Spring이 가명처리)
    assert review["orderedMenus"] == ["돌솥김치알밥", "잔치국수"]
    # ★ MENUID 는 3사 모두 제공한다(리뷰관리 스펙 §4). 이름만 쓰면 메뉴명이 바뀌는 순간
    #   같은 메뉴가 둘로 갈라져 메뉴별 통계가 어긋난다(T-8).
    assert review["menus"] == [
        {"menuId": "1001", "menuName": "돌솥김치알밥"},
        {"menuId": "1002", "menuName": "잔치국수"},
    ]
    assert review["imageUrls"] == ["https://cdn.example.com/review1.jpg"]
    assert review["platformExtra"] == {}  # 배민은 전용 필드 없음


def test_only_review_with_rc_list_has_existing_reply():
    data = parse_envelope(_load("baemin_reviews.json"))
    reviews = normalize_stores(data, "baemin")[0]["reviews"]
    assert reviews[0]["existingReply"] == {
        "id": "2024033103186049",
        "contents": "히리릴님, 별 5개 리뷰 감사드립니다",
    }
    assert reviews[1]["existingReply"] is None  # RC_LIST 없는 리뷰는 None


def test_yogiyo_platform_extra_has_taste_and_quantity():
    data = parse_envelope(_load("yogiyo_reviews.json"))
    review = normalize_stores(data, "yogiyo")[0]["reviews"][0]
    assert review["authorRaw"] == "cl**"  # 요기요는 이미 마스킹된 원문
    assert review["platformExtra"] == {"taste": "5", "quantity": "2"}


def test_coupangeats_platform_extra_has_order_num_and_pck_mthd():
    data = parse_envelope(_load("coupangeats_reviews.json"))
    review = normalize_stores(data, "coupangeats")[0]["reviews"][0]
    assert review["authorRaw"] == "김**"
    assert review["platformExtra"] == {"order_num": "ORD-99213", "pck_mthd": "배달"}


def test_null_string_fields_normalized_to_none_or_empty():
    """coupangeats_reviews.json 의 REVIEWCONTENTS="null", IMAGE="null" 이 걸러지는지 확인."""
    data = parse_envelope(_load("coupangeats_reviews.json"))
    review = normalize_stores(data, "coupangeats")[0]["reviews"][0]
    assert review["body"] == ""  # "null" → None → 빈 문자열로 취급
    assert review["imageUrls"] == []  # IMAGE="null" 은 이미지 목록에서 제외


def test_written_date_converted_from_yyyymmdd():
    data = parse_envelope(_load("baemin_reviews.json"))
    review = normalize_stores(data, "baemin")[0]["reviews"][0]
    assert review["writtenDate"] == "2024-03-31"


def test_multi_store_reviewlist_not_collapsed_to_single_store():
    """1계정 N매장(문서 08 F-7) — 기존 success_multi_store.json 픽스처로도 확인."""
    data = parse_envelope(_load("success_multi_store.json"))
    stores = normalize_stores(data, "baemin")
    assert len(stores) == 2
    assert [s["platformStoreId"] for s in stores] == ["14292949", "14292950"]
