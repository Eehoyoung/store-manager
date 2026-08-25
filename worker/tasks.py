"""Celery 태스크 — DataAPI 수집(poll/backfill).

★ 이 워커가 Spring 과 통신하는 유일한 경로는 POST /internal/collect-result 뿐이다
  (CLAUDE.md 서비스 간 경계). 다른 엔드포인트를 추가하지 않는다.
★ authorRaw(닉네임)는 가공 없이 그대로 전달한다 — 가명처리는 Spring 이 담당한다
  (docs/13 §11.2). ★이 요청/응답 본문은 절대 로깅하지 않는다(원본 닉네임 포함, CLAUDE.md 규칙6).
  로그에는 건수·소요시간·ECODE 만 남긴다.
"""
from __future__ import annotations

import json
import logging
import os
import random
import time
import uuid
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Callable

import httpx

import publish
from celery_app import app
import credentials
from dataapi import Credentials, DataApiClient, DataApiError, Platform, WRITE_ENABLED_ENV, call_with_retry, ecode_action
from normalize import normalize_stores

log = logging.getLogger("worker.tasks")

SPRING_INTERNAL_URL = os.environ.get("SPRING_INTERNAL_URL", "http://localhost:8080")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "")
REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")
COLLECT_LOOKBACK_DAYS = int(os.environ.get("COLLECT_LOOKBACK_DAYS", "2"))
BACKFILL_DAYS = int(os.environ.get("BACKFILL_DAYS", "90"))
BACKFILL_CHUNK_DAYS = int(os.environ.get("BACKFILL_CHUNK_DAYS", "7"))

# 게시 큐(Sprint 4) — Spring 이 LPUSH, 워커는 BRPOP 으로 소비하는 평범한 Redis LIST 다.
# ★ Celery 메시지 프로토콜이 아니다(고정계약 참고).
PUBLISH_QUEUE_KEY = "q:publish"
PUBLISH_BATCH_SIZE = int(os.environ.get("PUBLISH_BATCH_SIZE", "20"))
PUBLISH_BRPOP_TIMEOUT_SECONDS = int(os.environ.get("PUBLISH_BRPOP_TIMEOUT_SECONDS", "1"))
PUBLISH_RATE_LIMIT_SECONDS = float(os.environ.get("PUBLISH_RATE_LIMIT_SECONDS", "5"))
PUBLISH_JITTER_MAX_SECONDS = float(os.environ.get("PUBLISH_JITTER_MAX_SECONDS", "3"))

# ponytail: 락 TTL은 env 로 빼지 않았다. 필요해지면 COLLECT_LOCK_TTL 추가.
LOCK_TTL_SECONDS = 600

_PLATFORM_UPPER = {"baemin": "BAEMIN", "yogiyo": "YOGIYO", "coupangeats": "COUPANGEATS"}


@dataclass
class AccountInfo:
    platform: Platform
    credentials: Credentials


def _load_account(account_id: str) -> AccountInfo:
    """platform_account 를 읽어 DataAPI 전송용 자격증명으로 바꾼다 (T-2).

    복호화·재암호화는 credentials 모듈에 격리했다 — 평문이 존재하는 코드 구간을
    한곳으로 몰아 두려는 것이다(절대규칙 5). 테스트는 account_loader 인자로 이 함수를 대체한다."""
    from credentials import load_account

    platform, creds = load_account(account_id)
    return AccountInfo(platform=platform, credentials=creds)


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
    job_type: str = "POLL",
) -> dict:
    """한 구간(start~end)을 조회 → 정규화 → collect-result 전송. 요약 통계만 반환하고
    실제 요청/응답 본문은 절대 로깅하지 않는다."""
    start_s, end_s = start.strftime("%Y%m%d"), end.strftime("%Y%m%d")
    t0 = time.perf_counter()
    status, ecode, action, stores = "SUCCESS", None, None, []
    # ★ BudgetExhaustedError 는 아래 except DataApiError 에 걸리지 않고 그대로 전파된다 — 의도된 것이다.
    #   예산 소진은 우리 과금 문제지 사장님의 자격증명 문제가 아니다. collect-result 로 실패를
    #   보고하면 link_status=ERROR 가 되어 '재연동하세요' 알림이 엉뚱하게 나간다.
    #   호출 자체를 하지 않았으므로 보고할 사실도 없다.
    try:
        data = call_with_retry(
            lambda: client.fetch_reviews(account.platform, account.credentials, start_s, end_s),
            sleep=sleep,
        )
        stores = normalize_stores(data, account.platform)
    except DataApiError as exc:
        ecode = exc.ecode
        action = ecode_action(exc.ecode)  # 실제 link_status 전이는 Spring 책임 — 여기선 사실만 전달
        # 조회 결과 없음은 실패가 아니다. 로그인은 성공했고 그 기간에 리뷰가 없었을 뿐이다.
        # FAILED 로 보고하면 리뷰 없는 날마다 수집 실패로 집계되고 재시도·알림이 헛돈다.
        status = "SUCCESS" if action == "NO_DATA" else "FAILED"
        # ERRMSG 는 업체가 주는 실패 사유다. 이게 없으면 '로그인 실패' 가 비밀번호 문제인지
        # 암호화 설정 문제인지 구분할 수 없어 확인용 호출을 더 쓰게 된다.
        # ★ 자격증명은 요청에만 있고 응답 ERRMSG 에는 없다 — 그래서 남겨도 안전하다(절대규칙 5).
        log.log(
            logging.INFO if status == "SUCCESS" else logging.WARNING,
            "collect %s account=%s ecode=%s action=%s errmsg=%s",
            "결과없음" if status == "SUCCESS" else "failed", account_id, ecode, action, exc.errmsg,
        )

    latency_ms = int((time.perf_counter() - t0) * 1000)
    found = sum(len(s["reviews"]) for s in stores)

    payload: dict[str, Any] = {
        "jobId": job_id,
        "accountId": account_id,
        "platform": _PLATFORM_UPPER[account.platform],
        "status": status,
        # ★ 조회 기간과 작업 유형을 함께 보낸다. 이게 없으면 Spring 이 collection_job 을 만들 수
        #   없어(start_date/end_date 가 NOT NULL) 수집 이력이 하나도 남지 않는다 — T-5 측정 불가.
        "jobType": job_type,
        "startDate": start.isoformat(),
        "endDate": end.isoformat(),
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
            account_id, account, start, today, job_id or uuid.uuid4().hex, client, sleep, "POLL"
        )
    finally:
        _release_lock(rc, account_id, lock_token)


def _publish_error_result(payload: dict, reason: str) -> dict:
    """게시 잡 처리 중 process_publish_job 이전 단계(계정 조회·스로틀 등)에서 예외가 나거나
    payload 자체가 깨졌을 때의 폴백 보고. LOGINPWD 등 민감정보가 섞일 수 있는 예외 상세는
    담지 않고 reason 라벨만 남긴다(절대규칙 5)."""
    return {
        "jobId": uuid.uuid4().hex,
        "accountId": str(payload.get("accountId")) if isinstance(payload, dict) else None,
        "platform": payload.get("platform") if isinstance(payload, dict) else None,
        "status": "FAILED",
        "ecode": None,
        "action": "FAIL",
        "publish": {
            "draftId": payload.get("draftId") if isinstance(payload, dict) else None,
            "platformCommentId": None,
            "failReason": reason,
            "dispatchToken": payload.get("dispatchToken") if isinstance(payload, dict) else None,
        },
    }


@app.task(name="tasks.dispatch_polls")
def dispatch_polls(account_lister=None) -> dict:
    """정기 수집 팬아웃 — beat 가 하루 3회(10·16·20시 KST) 부른다.

    ★ beat 는 이 태스크 하나만 건다. 계정별 스케줄을 beat 에 넣으면 매장이 늘 때마다
      스케줄을 고쳐야 하고, 스케줄 파일이 곧 과금 대상 목록이 된다.

    ★ 호출 단가가 곧 원가다(2026-08-25 산정). 대상 선별은 credentials.active_account_ids
      가 SQL 한 번으로 끝낸다 — 구독이 끊긴 매장에 호출을 만들지 않는다.
    """
    lister = account_lister or credentials.active_account_ids
    try:
        ids = lister()
    except Exception as exc:  # DB 접근 실패 — 호출을 만들지 않고 조용히 끝낸다
        log.error("dispatch_polls 대상 조회 실패: %s", exc)
        return {"status": "ERROR", "dispatched": 0}

    for account_id in ids:
        poll_reviews.delay(str(account_id))
    log.info("dispatch_polls 팬아웃 %d건", len(ids))
    return {"status": "OK", "dispatched": len(ids)}


@app.task(name="tasks.publish_drafts")
def publish_drafts(
    sleep: Callable[[float], None] = time.sleep,
    client_factory: Callable[[], DataApiClient] = DataApiClient,
    redis_client=None,
    account_loader: Callable[[str], AccountInfo] = _load_account,
    now: Callable[[], float] = time.time,
    rand: Callable[[], float] = random.random,
    batch_size: int = PUBLISH_BATCH_SIZE,
) -> list[dict]:
    """Redis 리스트 'q:publish' 에서 최대 batch_size 건을 꺼내 순차 게시하고 건마다
    /internal/collect-result 로 보고한다(고정계약 - 새 엔드포인트 추가 금지).

    ★ 한 건이 예외로 죽어도 나머지 건은 계속 처리한다(건별 try/except).
    ★ 한계: BRPOP 으로 큐에서 꺼낸 뒤 처리 중 프로세스가 죽으면 그 잡은 유실된다.
      Spring 의 'dispatch:draft:{draftId}' 키가 TTL(900초) 만료되면 재디스패치로 복구된다 —
      워커는 이 키를 지우지 않는다(Spring 이 결과 수신 시 삭제).
    """
    rc = redis_client if redis_client is not None else _redis_client()
    client = client_factory()
    results = []
    for _ in range(batch_size):
        popped = rc.brpop(PUBLISH_QUEUE_KEY, PUBLISH_BRPOP_TIMEOUT_SECONDS)
        if not popped:
            break
        _, raw = popped
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")

        payload = None
        try:
            payload = json.loads(raw)
            if publish.is_risk_blocked(payload):
                # ★ 절대규칙 3 이중 검증 — DataAPI 호출·계정 조회·스로틀 전부 생략한다.
                result = publish.blocked_result(payload)
            elif publish.is_store_inactive(payload):
                result = publish.blocked_result(payload, "STORE_INACTIVE")
            elif os.environ.get(WRITE_ENABLED_ENV, "false").lower() != "true":
                result = publish.blocked_result(payload, "DATAAPI_WRITE_DISABLED")
            else:
                account = account_loader(str(payload["accountId"]))
                publish.throttle(
                    rc,
                    str(payload["accountId"]),
                    PUBLISH_RATE_LIMIT_SECONDS,
                    PUBLISH_JITTER_MAX_SECONDS,
                    sleep,
                    now,
                    rand,
                )
                result = publish.process_publish_job(
                    payload, account.platform, account.credentials, client.create_comment
                )
        except Exception as exc:
            # ★ 예외 종류만 남기면 원인을 못 찾는다(ModuleNotFoundError 하나로 30분을 썼다).
            #   메시지까지 남긴다 — 자격증명은 payload 에만 있고 예외 메시지에는 없다.
            log.warning("publish job 처리 실패 draftId=%s error=%s: %s",
                        (payload or {}).get("draftId"), type(exc).__name__, exc)
            result = _publish_error_result(payload if isinstance(payload, dict) else {}, "INTERNAL_ERROR")

        try:
            _post_collect_result(result)
        except Exception:
            log.warning("collect-result 보고 실패 draftId=%s", result.get("publish", {}).get("draftId"))
        results.append(result)
    return results


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
                    account_id, account, start, end, job_id or uuid.uuid4().hex, client, sleep,
                    "BACKFILL",
                )
            )
            sleep(random.uniform(0.2, 0.6))  # 구간 사이 지터 — 레이트리밋/탐지 회피
        return results
    finally:
        _release_lock(rc, account_id, lock_token)
