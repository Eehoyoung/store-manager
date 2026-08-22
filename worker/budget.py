"""DataAPI 호출 예산 게이트.

DataAPI 는 호출당 과금이고, 테스트 토큰은 이용횟수가 정해져 있다(소진되면 errCode 2003).
호출을 다 쓰고 나서 로그로 아는 것은 늦다 — 여기서 **호출 직전에** 막는다.

★ 왜 Spring 의 dataapi_call_log 로는 부족한가
  그 로그는 호출이 끝난 뒤 collect-result 로 보고돼야 쓰인다. 워커가 보고 전에 죽으면
  이미 과금된 호출이 기록조차 남지 않고, 재시도가 같은 호출을 또 태운다.

★ 왜 fail-closed 인가
  카운터를 못 세면 호출하지 않는다. 세지 못한 호출은 예산을 조용히 갉아먹는다.
  가용성보다 과금 사고를 막는 쪽을 택한다 — 수집은 다음 주기에 다시 돌면 그만이다.
"""
from __future__ import annotations

import os

# 0 이면 무제한(운영 기본값). 테스트 토큰을 쓸 때만 실제 잔여 횟수를 넣는다.
CALL_BUDGET = int(os.environ.get("DATAAPI_CALL_BUDGET", "0"))
COUNTER_KEY = os.environ.get("DATAAPI_BUDGET_KEY", "dataapi:calls:used")
REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")

# 한도 미만일 때만 증가시킨다. INCR 후 검사하면 두 워커가 동시에 마지막 1회를 통과할 수 있다.
_RESERVE_SCRIPT = """
local used = tonumber(redis.call('get', KEYS[1]) or '0')
local limit = tonumber(ARGV[1])
if limit > 0 and used >= limit then return -1 end
return redis.call('incr', KEYS[1])
"""


class BudgetExhaustedError(RuntimeError):
    """예산 소진 — 호출하지 않았다. 재시도해도 소용없으므로 호출부는 즉시 중단해야 한다."""


def _client():
    import redis as redis_lib

    return redis_lib.Redis.from_url(REDIS_URL)


def reserve(endpoint: str, redis_client=None) -> int:
    """호출 1회를 예약하고 누적 사용량을 반환한다. 예산을 넘으면 호출 없이 예외."""
    if CALL_BUDGET <= 0:
        return 0  # 무제한 — 카운터를 돌리지 않는다
    rc = redis_client if redis_client is not None else _client()
    used = rc.eval(_RESERVE_SCRIPT, 1, COUNTER_KEY, CALL_BUDGET)
    if used == -1:
        raise BudgetExhaustedError(
            f"DataAPI 호출 예산 소진 ({CALL_BUDGET}회). '{endpoint}' 를 호출하지 않았습니다. "
            f"추가 횟수를 발급받은 뒤 DATAAPI_CALL_BUDGET 을 올리거나 카운터를 조정하세요."
        )
    return int(used)


def status(redis_client=None) -> dict:
    """남은 호출 수를 본다. DataAPI 를 호출하지 않으므로 과금되지 않는다."""
    rc = redis_client if redis_client is not None else _client()
    raw = rc.get(COUNTER_KEY)
    used = int(raw) if raw else 0
    return {
        "budget": CALL_BUDGET or "무제한",
        "used": used,
        "remaining": (CALL_BUDGET - used) if CALL_BUDGET > 0 else "무제한",
    }


def set_used(count: int, redis_client=None) -> dict:
    """카운터를 실제 사용량에 맞춘다(예산 게이트 도입 전에 쓴 호출을 반영할 때)."""
    rc = redis_client if redis_client is not None else _client()
    rc.set(COUNTER_KEY, int(count))
    return status(rc)


if __name__ == "__main__":
    import json
    import sys

    args = sys.argv[1:]
    if args and args[0] == "set":
        print(json.dumps(set_used(int(args[1])), ensure_ascii=False))
    else:
        print(json.dumps(status(), ensure_ascii=False))
