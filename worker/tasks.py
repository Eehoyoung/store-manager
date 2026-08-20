"""Celery 태스크 — DataAPI 수집(poll/backfill).

★ 이 워커가 Spring 과 통신하는 유일한 경로는 POST /internal/collect-result 뿐이다
  (CLAUDE.md 서비스 간 경계). 다른 엔드포인트를 추가하지 않는다.
★ authorRaw(닉네임)는 가공 없이 그대로 전달한다 — 가명처리는 Spring 이 담당한다
  (docs/13 §11.2). ★이 요청/응답 본문은 절대 로깅하지 않는다(원본 닉네임 포함, CLAUDE.md 규칙6).
  로그에는 건수·소요시간·ECODE 만 남긴다.
"""
from __future__ import annotations

import logging
import os
import random
import time
import uuid
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Callable

import httpx

from celery_app import app
from dataapi import Credentials, DataApiClient, DataApiError, Platform, call_with_retry, ecode_action
from normalize import normalize_stores

log = logging.getLogger("worker.tasks")

SPRING_INTERNAL_URL = os.environ.get("SPRING_INTERNAL_URL", "http://localhost:8080")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "")
REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")
COLLECT_LOOKBACK_DAYS = int(os.environ.get("COLLECT_LOOKBACK_DAYS", "2"))
BACKFILL_DAYS = int(os.environ.get("BACKFILL_DAYS", "90"))
BACKFILL_CHUNK_DAYS = int(os.environ.get("BACKFILL_CHUNK_DAYS", "7"))

# ponytail: 락 TTL은 env 로 빼지 않았다. 필요해지면 COLLECT_LOCK_TTL 추가.
LOCK_TTL_SECONDS = 600

_PLATFORM_UPPER = {"baemin": "BAEMIN", "yogiyo": "YOGIYO", "coupangeats": "COUPANGEATS"}


@dataclass
class AccountInfo:
    platform: Platform
    credentials: Credentials


def _load_account(account_id: str) -> AccountInfo:
    """TODO(블로킹): platform_account 조회는 DB 연동이 필요하고, LOGINPWD 복호화(KMS) 방식도
    미확정이라 아직 실구현할 수 없다 (CLAUDE.md '미확정 사항으로 블로킹되는 작업' 참고).
    확정 전까지는 호출을 막는다. 테스트는 account_loader 인자로 이 함수를 대체한다."""
    raise NotImplementedError(
        "platform_account 조회 미구현 — DataAPI 토큰·LOGINPWD 암호화 방식 회신 후 구현"
    )


def _redis_client():
    """실제 Redis 클라이언트를 지연 생성한다. 테스트는 redis_client 인자로 목 객체를 주입한다."""
    import redis as redis_lib  # 지연 임포트 — 테스트에서 실제 연결을 만들지 않기 위함

    return redis_lib.Redis.from_url(REDIS_URL)


# 소유자만 락을 해제할 수 있게 하는 compare-and-delete 스크립트.
# 락이 TTL 로 만료된 뒤 다른 워커가 새로 잡았다면, 만료된 쪽이 그 락을 지워서는 안 된다
# (지우면 같은 계정을 두 워커가 동시에 수집 → DataAPI 호출이 이중 과금된다).
_RELEASE_SCRIPT = """
if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
"""


def _try_acquire_lock(client, account_id: str) -> str | None:
    """lock:collect:{account_id} 를 SET NX EX 로 획득하고 소유 토큰을 반환한다.
    None 이면 다른 워커가 수집 중이라는 뜻."""
    token = uuid.uuid4().hex
    if client.set(f"lock:collect:{account_id}", token, nx=True, ex=LOCK_TTL_SECONDS):
        return token
    return None


def _release_lock(client, account_id: str, token: str) -> None:
    """내가 잡은 락일 때만 해제한다."""
    key = f"lock:collect:{account_id}"
    try:
        client.eval(_RELEASE_SCRIPT, 1, key, token)
    except AttributeError:
        # eval 을 지원하지 않는 클라이언트(테스트 목 등)는 소유 확인 후 삭제로 대체한다.
        if client.get(key) in (token, token.encode()):
            client.delete(key)


def _post_collect_result(payload: dict) -> None:
    """POST /internal/collect-result. 본문은 절대 로깅하지 않는다(원본 닉네임 포함)."""
    resp = httpx.post(
        f"{SPRING_INTERNAL_URL}/internal/collect-result",
        json=payload,
        headers={"X-Internal-Token": INTERNAL_TOKEN},
        timeout=15.0,
    )
    resp.raise_for_status()


def _fetch_and_report(
    account_id: str,
    account: AccountInfo,
    start: date,
    end: date,
    job_id: str,
    client: DataApiClient,
    sleep: Callable[[float], None],
) -> dict:
    """한 구간(start~end)을 조회 → 정규화 → collect-result 전송. 요약 통계만 반환하고
    실제 요청/응답 본문은 절대 로깅하지 않는다."""
    start_s, end_s = start.strftime("%Y%m%d"), end.strftime("%Y%m%d")
    t0 = time.perf_counter()
    status, ecode, action, stores = "SUCCESS", None, None, []
    try:
        data = call_with_retry(
            lambda: client.fetch_reviews(account.platform, account.credentials, start_s, end_s),
            sleep=sleep,
        )
        stores = normalize_stores(data, account.platform)
    except DataApiError as exc:
        status = "FAILED"
        ecode = exc.ecode
        action = ecode_action(exc.ecode)  # 실제 link_status 전이는 Spring 책임 — 여기선 사실만 전달
        log.warning("collect failed account=%s ecode=%s action=%s", account_id, ecode, action)

    latency_ms = int((time.perf_counter() - t0) * 1000)
    found = sum(len(s["reviews"]) for s in stores)

    payload: dict[str, Any] = {
        "jobId": job_id,
        "accountId": account_id,
        "platform": _PLATFORM_UPPER[account.platform],
        "status": status,
        "stores": stores,
        # ponytail: new 는 워커가 dedupe 정보를 모르므로 found 로 대체 신고한다.
        # 실제 신규 건수는 Spring 의 (platform, REVIEWID) UPSERT 결과가 정답이며,
        # 정확한 신규 판정이 필요해지면 Spring 이 응답으로 되돌려주는 방식으로 바꾼다.
        "stats": {"found": found, "new": found, "latencyMs": latency_ms},
    }
    if status == "FAILED":
        payload["ecode"] = ecode
        payload["action"] = action

    _post_collect_result(payload)
    log.info(
        "collect reported account=%s window=%s~%s status=%s found=%d latencyMs=%d",
        account_id, start_s, end_s, status, found, latency_ms,
    )
    return {"status": status, "found": found, "latencyMs": latency_ms, "ecode": ecode}


def _backfill_windows(today: date, total_days: int, chunk_days: int) -> list[tuple[date, date]]:
    """오늘로부터 과거 total_days 일을 chunk_days 일 단위로 분할한다(오래된 순).
    기본값(90일/7일)이면 정확히 13구간이 나온다(마지막 구간만 6일) — 문서 08 F-4 대비."""
    start_of_range = today - timedelta(days=total_days - 1)
    windows = []
    cursor = start_of_range
    while cursor <= today:
        window_end = min(cursor + timedelta(days=chunk_days - 1), today)
        windows.append((cursor, window_end))
        cursor = window_end + timedelta(days=1)
    return windows


@app.task(name="tasks.poll_reviews")
def poll_reviews(
    account_id: str,
    job_id: str | None = None,
    sleep: Callable[[float], None] = time.sleep,
    client_factory: Callable[[], DataApiClient] = DataApiClient,
    redis_client=None,
    account_loader: Callable[[str], AccountInfo] = _load_account,
) -> dict:
    """정기 수집(F-4): 최근 COLLECT_LOOKBACK_DAYS 일 재조회 → 정규화 → /internal/collect-result.
    증분 조회가 없으므로 매번 최근 구간을 통째로 재조회한다. dedupe 는 Spring UPSERT 가 담당."""
    rc = redis_client if redis_client is not None else _redis_client()
    lock_token = _try_acquire_lock(rc, account_id)
    if lock_token is None:
        log.info("collect skipped (lock held) account=%s", account_id)
        return {"status": "SKIPPED"}
    try:
        account = account_loader(account_id)
        today = date.today()
        start = today - timedelta(days=COLLECT_LOOKBACK_DAYS)
        client = client_factory()
        return _fetch_and_report(
            account_id, account, start, today, job_id or uuid.uuid4().hex, client, sleep
        )
    finally:
        _release_lock(rc, account_id, lock_token)


@app.task(name="tasks.backfill")
def backfill(
    account_id: str,
    job_id: str | None = None,
    sleep: Callable[[float], None] = time.sleep,
    client_factory: Callable[[], DataApiClient] = DataApiClient,
    redis_client=None,
    account_loader: Callable[[str], AccountInfo] = _load_account,
) -> list[dict]:
    """최초 연동 백필: 90일을 7일 단위로 분할 호출한다(문서 08 F-4 — 타임아웃·응답크기 대비).
    RC_LIST(기존 답글)가 이때 대량 수집되며, 이는 말투 학습 RAG 코퍼스의 핵심 소스가 된다(문서 08 §5)."""
    rc = redis_client if redis_client is not None else _redis_client()
    lock_token = _try_acquire_lock(rc, account_id)
    if lock_token is None:
        log.info("backfill skipped (lock held) account=%s", account_id)
        return [{"status": "SKIPPED"}]
    try:
        account = account_loader(account_id)
        today = date.today()
        client = client_factory()
        results = []
        for start, end in _backfill_windows(today, BACKFILL_DAYS, BACKFILL_CHUNK_DAYS):
            results.append(
                _fetch_and_report(
                    account_id, account, start, end, job_id or uuid.uuid4().hex, client, sleep
                )
            )
            sleep(random.uniform(0.2, 0.6))  # 구간 사이 지터 — 레이트리밋/탐지 회피
        return results
    finally:
        _release_lock(rc, account_id, lock_token)
