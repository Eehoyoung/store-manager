"""DataAPI 응답(data) → /internal/collect-result 페이로드(stores[]) 정규화.

docs/08_DataAPI_연동명세서.md §2.2(응답 구조)·§2.3(플랫폼별 필드 지원)·§5(매핑표)와
docs/13_내부API명세.md §11.2(collect-result 스키마)를 동시에 만족해야 한다.

★ authorRaw(닉네임)는 가공하지 않고 원문 그대로 담는다 — 가명처리는 Spring 책임이다
  (CLAUDE.md 규칙6, 문서 08 F-5: 배민은 마스킹되지 않은 원문이 온다).
★ 모든 값은 dataapi._s() 로 문자열 "null" 을 걸러 None 으로 정규화한다 (문서 08 F-2).
★ 필드 부재·타입 이상에 관대하게 동작해야 한다 — 플랫폼별로 지원 필드가 다르다(§2.3).
"""
from __future__ import annotations

from typing import Any

from dataapi import Platform, _s


def _to_int(v: Any) -> int | None:
    s = _s(v)
    if s is None:
        return None
    try:
        return int(s)
    except (TypeError, ValueError):
        return None


def _to_float(v: Any) -> float | None:
    s = _s(v)
    if s is None:
        return None
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def _written_date(review_date: Any) -> str | None:
    """yyyyMMdd → yyyy-MM-dd. 시각 정보는 원래 없다(문서 08 F-3)."""
    s = _s(review_date)
    if s is None or len(s) != 8 or not s.isdigit():
        return s
    return f"{s[0:4]}-{s[4:6]}-{s[6:8]}"


def _image_urls(review: dict) -> list[str]:
    """review['LIST'][].IMAGE — 리뷰 하위의 LIST 는 이미지 배열이다(§2.2, 매장 하위 LIST=리뷰 배열과 다름)."""
    urls = []
    for item in review.get("LIST") or []:
        url = _s(item.get("IMAGE"))
        if url:
            urls.append(url)
    return urls


def _ordered_menus(review: dict) -> list[str]:
    """review['ORDER_LIST'][].LIST[].MENUNM 을 평탄화."""
    menus = []
    for order in review.get("ORDER_LIST") or []:
        for item in order.get("LIST") or []:
            name = _s(item.get("MENUNM"))
            if name:
                menus.append(name)
    return menus


def _existing_reply(review: dict) -> dict | None:
    """RC_LIST[0] 만 사용 — 리뷰 1건당 사장님 댓글은 1개다(문서 08 F-8)."""
    rc_list = review.get("RC_LIST") or []
    if not rc_list:
        return None
    rc = rc_list[0]
    return {"id": _s(rc.get("RCID")), "contents": _s(rc.get("RCCONTENTS"))}


def _platform_extra(review: dict, platform: Platform) -> dict:
    """플랫폼 전용 필드만 담는다(문서 08 §2.3/§5). 배민은 전용 필드가 없어 빈 dict."""
    if platform == "yogiyo":
        return {"taste": _s(review.get("TASTEVALUE")), "quantity": _s(review.get("QUANTITYVALUE"))}
    if platform == "coupangeats":
        order_num = pck_mthd = None
        for order in review.get("ORDER_LIST") or []:
            order_num = _s(order.get("ORDERNUM"))
            pck_mthd = _s(order.get("PCKMTHD"))
            break  # 리뷰 1건당 주문 그룹은 통상 1개
        return {"order_num": order_num, "pck_mthd": pck_mthd}
    return {}


def _normalize_review(review: dict, platform: Platform) -> dict:
    body = _s(review.get("REVIEWCONTENTS"))
    return {
        "platformReviewId": _s(review.get("REVIEWID")),
        "rating": _to_int(review.get("EVALUE")),
        "body": body.strip() if body else "",  # trim, 빈값 허용(사진만 리뷰)
        "authorRaw": _s(review.get("NICKNAME")),
        "orderedMenus": _ordered_menus(review),
        "imageUrls": _image_urls(review),
        "platformExtra": _platform_extra(review, platform),
        "reviewStatus": _s(review.get("REVIEWSTATUS")),  # 의미 미확인 — 원본 그대로 보관(CLAUDE.md #10)
        "writtenDate": _written_date(review.get("REVIEWDATE")),
        "existingReply": _existing_reply(review),
    }


def normalize_stores(data: dict, platform: Platform) -> list[dict]:
    """dataapi.parse_envelope() 를 통과한 data 를 collect-result 의 stores[] 로 변환한다.

    REVIEWLIST[] 를 그대로 순회한다 — 1계정 N매장(문서 08 F-7)을 절대 단일 매장으로
    가정하지 않는다.
    """
    stores = []
    for store in data.get("REVIEWLIST") or []:
        reviews = [_normalize_review(r, platform) for r in (store.get("LIST") or [])]
        stores.append(
            {
                "platformStoreId": _s(store.get("STOREID")),
                "storeName": _s(store.get("STORENAME")),
                "avgRating": _to_float(store.get("AVGEVALUE")),
                "reviews": reviews,
            }
        )
    return stores
