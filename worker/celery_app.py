"""Celery 앱 정의. 브로커/백엔드는 REDIS_URL 환경변수로 지정한다."""
import os

from celery import Celery
from celery.schedules import crontab

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")

app = Celery("worker", broker=REDIS_URL, backend=REDIS_URL, include=["tasks"])

# ★ 시간대를 반드시 고정한다. 기본값은 UTC 라서, 안 걸면 수집이 한국시간 19·01·05시에 돈다.
app.conf.timezone = "Asia/Seoul"
app.conf.enable_utc = False

app.conf.task_routes = {"tasks.publish_drafts": {"queue": "publish"}}

# ── 수집 주기 (2026-08-26 재결정 — DataAPI 요금제 회신 반영) ────────────────
#
# ★ 하루 1회: 10시 (KST). 이전에는 10·16·20시 3회였다.
#   10시인 이유 — 배달 리뷰는 저녁·야식(18~24시)에 몰린다. 10시 폴링이면 그 리뷰가
#   10~16시간 뒤에 잡힌다. 20시 1회로 하면 같은 리뷰가 하루를 넘겨(20~26시간) 기다린다.
#
# ★ 왜 3회에서 1회로 줄였나 — 요금이 "월 50만원에 10,000건 포함, 초과분은 호출당 종량"
#   이다. 즉 포함분을 넘긴 뒤로는 폴링 1회가 곧바로 돈이다.
#     조회 3회 → 매장당 월 386회 → 포함분에 25매장
#     조회 1회 → 매장당 월 219회 → 포함분에 45매장
#   100매장 기준으로 3회는 1회보다 월 호출이 16,686건 많다.
#   초과 단가 50원 가정이면 월 83만원 차이다.
#
# ★ 1회여도 리뷰는 유실되지 않는다. 조회 윈도우가 최근 2일이라 폴링이 한 번 통째로
#   실패해도 다음 날 폴링이 같은 리뷰를 다시 가져온다.
#   늘리지 말 것 — 늘린 호출은 초과 구간에서 전부 과금된다.
#
# ★ 계정별 스케줄을 여기 넣지 말 것. dispatch_polls 가 DB 에서 대상을 골라 팬아웃한다.
POLL_HOURS = os.environ.get("COLLECT_POLL_HOURS", "10")

app.conf.beat_schedule = {
    "reclaim-publish-drafts-every-30s": {
        "task": "tasks.reclaim_publish_drafts",
        "schedule": 30.0,
    },
    "dispatch-polls": {
        "task": "tasks.dispatch_polls",
        "schedule": crontab(hour=POLL_HOURS, minute=0),
    },
    "publish-drafts-every-30s": {
        "task": "tasks.publish_drafts",
        "schedule": 30.0,
        "options": {"queue": "publish"},
    },
}
