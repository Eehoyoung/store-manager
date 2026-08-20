"""게시 잡(Redis 'q:publish' 1건) 처리 — 순수 로직.

Celery·Redis·네트워크에 직접 의존하지 않는다. 실제 DataAPI 호출은 tasks.py 가
create_comment 콜러블로 주입하므로, 여기서는 판정과 /internal/collect-result 보고용
payload 조립만 담당해 목(mock)만으로 전부 테스트 가능하게 한다.

★ 절대규칙 1: 답글 내용을 생성/변형하지 않는다. Spring 이 넘긴 content 를 그대로 게시한다.
★ 절대규칙 2: 성공 판정은 dataapi.parse_envelope(data.RESULT == "SUCCESS") 를 반드시 거친다.
  create_comment 콜러블은 dataapi.DataApiClient.create_comment 를 그대로 주입하므로
  이 판정을 다시 구현하지 않는다.
★ 절대규칙 3: riskLevel >= 3 이면 DataAPI 를 호출하지 않는다 — Spring 이 이미 막지만
  워커에서도 이중 검증한다(문서 11 §2.4/§8.2 체크리스트).
"""
from __future__ import annotations

import uuid
from typing import Any, Callable

from dataapi import AlreadyRepliedError, Credentials, DataApiError, Platform, _s, ecode_action

RISK_BLOCK_THRESHOLD = 3


def is_risk_blocked(payload: dict[str, Any]) -> bool:
    # riskLevel 은 고정계약상 항상 포함되지만, 누락 시에도 안전측(차단)으로 기본값을 둔다.
    return payload.get("riskLevel", RISK_BLOCK_THRESHOLD) >= RISK_BLOCK_THRESHOLD


def is_store_inactive(payload: dict[str, Any]) -> bool:
    # 필드 누락도 비활성으로 본다. 구버전·위조 payload가 게시를 열어서는 안 된다.
    return payload.get("storeActive") is not True


def _envelope(
    payload: dict[str, Any],
    status: str,
    action: str,
    ecode: str | None = None,
    platform_comment_id: str | None = None,
    fail_reason: str | None = None,
) -> dict[str, Any]:
    """고정계약(게시결과_보고 body) 형태로 조립한다."""
    return {
        "jobId": uuid.uuid4().hex,
        "accountId": str(payload["accountId"]),
        "platform": payload["platform"],
        "status": status,
        "ecode": ecode,
        "action": action,
        "publish": {
            "draftId": payload["draftId"],
            "platformCommentId": platform_comment_id,
            "failReason": fail_reason,
            "dispatchToken": payload.get("dispatchToken"),
        },
    }


def blocked_result(payload: dict[str, Any], reason: str = "RISK_LEVEL_TOO_HIGH") -> dict[str, Any]:
    return _envelope(payload, "FAILED", "FAIL", fail_reason=reason)


def process_publish_job(
    payload: dict[str, Any],
    platform: Platform,
    credentials: Credentials,
    create_comment: Callable[[Platform, Credentials, str, str, str], dict],
) -> dict[str, Any]:
    """게시 잡 1건 처리. create_comment 는 DataApiClient.create_comment 와 같은
    시그니처의 콜러블이며, 실패 시 dataapi.DataApiError/AlreadyRepliedError 를 던져야 한다.
    riskLevel 이 차단선 이상이면 create_comment 를 호출조차 하지 않는다."""
    if is_risk_blocked(payload):
        return blocked_result(payload)
    if is_store_inactive(payload):
        return blocked_result(payload, "STORE_INACTIVE")

    try:
        data = create_comment(
            platform,
            credentials,
            payload["content"],
            payload["platformStoreId"],
            payload["platformReviewId"],
        )
        comment_id = _s(data.get("REVIEWCOMMENTID"))
        return _envelope(payload, "SUCCESS", "PUBLISHED", platform_comment_id=comment_id)
    except AlreadyRepliedError as exc:
        # ★ 댓글 중복은 실패가 아니다 — 정상 종료(SUCCESS/ALREADY_REPLIED), 재시도 금지.
        return _envelope(payload, "SUCCESS", "ALREADY_REPLIED", ecode=exc.ecode)
    except DataApiError as exc:
        # ecode_action: 로그인 실패 → LINK_ERROR, 그 외(미확인 포함) → FAIL.
        return _envelope(
            payload, "FAILED", ecode_action(exc.ecode), ecode=exc.ecode, fail_reason=exc.errmsg
        )


def throttle(
    redis_client,
    account_id: str,
    min_interval: float,
    jitter_max: float,
    sleep: Callable[[float], None],
    now: Callable[[], float],
    rand: Callable[[], float],
) -> None:
    """계정당 게시 호출 간격을 min_interval 초 이상으로 두고 0~jitter_max 초 랜덤 지터를 더한다.
    DataAPI 는 호출당 과금되므로, 정교한 동시성 제어보다 '마지막 호출 시각 1개'만 Redis 에
    기록하는 단순한 방식으로 충분하다.
    ponytail: read-then-write 라 두 워커가 동시에 같은 계정을 처리하면 레이스가 날 수 있는
    근사 스로틀이다(원자성 없음). 게시는 이미 dispatch:draft:{draftId} 락으로 계정별 동시
    중복 처리를 막고 있어 실질 충돌 가능성은 낮다 — 문제가 되면 Lua 스크립트로 원자화한다."""
    key = f"throttle:publish:{account_id}"
    raw_last = redis_client.get(key)
    last = float(raw_last) if raw_last else 0.0
    wait = max(0.0, min_interval - (now() - last)) + rand() * jitter_max
    if wait > 0:
        sleep(wait)
    redis_client.set(key, str(now()))
