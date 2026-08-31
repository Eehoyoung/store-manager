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
    """위험도 때문에 게시를 막아야 하는가.

    ★ 방어선을 없앤 게 아니라 조건을 좁혔다(2026-08-27).
      이전: riskLevel >= 3 이면 무조건 차단.
      지금: riskLevel >= 3 이고 **사람 승인이 없으면** 차단.

    ★ humanApproved 기본값은 False 다. 필드가 없는 구버전·위조 payload 가 위험 게시를
      열어서는 안 된다. riskLevel 기본값이 '차단' 인 것과 같은 이유다.
      이 두 기본값을 바꾸지 말 것 — 여기가 마지막 방어선이다.
    """
    risk = payload.get("riskLevel")
    if not isinstance(risk, int) or isinstance(risk, bool):
        # 위험도를 모르는 것은 안전하다는 뜻이 아니다. 승인 여부와 무관하게 막는다.
        # ★ 이 검사를 지우면 riskLevel 을 뺀 payload 에 humanApproved 만 실어 뚫을 수 있다.
        return True
    if risk < RISK_BLOCK_THRESHOLD:
        return False
    return payload.get("humanApproved") is not True


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


_THROTTLE_SCRIPT = """
local last = tonumber(redis.call('GET', KEYS[1]) or '0')
local now = tonumber(ARGV[1])
local wait = math.max(0, last + tonumber(ARGV[2]) - now) + tonumber(ARGV[3])
redis.call('SET', KEYS[1], string.format('%.6f', now + wait))
return tostring(wait)
"""


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
    Lua로 계정별 다음 호출 시각을 원자 예약한다. 초안별 dispatch 락은 계정 락이 아니다.
    예약은 취소하지 않는다 — 호출 실패 시에도 이미 예약한 간격을 줄이지 않는다."""
    key = f"throttle:publish:{account_id}"
    if hasattr(redis_client, "eval") or hasattr(redis_client, "evalsha"):
        args = (1, key, now(), min_interval, rand() * jitter_max)
        if hasattr(redis_client, "eval"):
            wait = float(redis_client.eval(_THROTTLE_SCRIPT, *args))
        else:
            wait = float(redis_client.evalsha(redis_client.script_load(_THROTTLE_SCRIPT), *args))
        if wait > 0:
            sleep(wait)
        return

    # Lua 미지원 테스트 대역만 기존 비원자 경로를 쓴다. 운영 Lua 오류는 폴백하지 않는다.
    raw_last = redis_client.get(key)
    last = float(raw_last) if raw_last else 0.0
    wait = max(0.0, min_interval - (now() - last)) + rand() * jitter_max
    if wait > 0:
        sleep(wait)
    redis_client.set(key, str(now()))
