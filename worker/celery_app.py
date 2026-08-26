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

# ── 수집 주기 (2026-08-26 재결정 — DataAPI 정액 팩 회신 반영) ────────────────
#
# ★ 하루 1회: 10시 (KST). 이전에는 10·16·20시 3회였다.
#   10시인 이유 — 배달 리뷰는 저녁·야식(18~24시)에 몰린다. 10시 폴링이면 그 리뷰가
#   10~16시간 뒤에 잡힌다. 20시 1회로 하면 같은 리뷰가 하루를 넘겨(20~26시간) 기다린다.
#
# ★ 왜 3회에서 1회로 줄였나 — 단가가 호출당이 아니라 "월 50만원 / 10,000건" 정액 팩이다.
#   따라서 절감액이 아니라 **한 팩에 몇 매장을 담을 수 있는가**가 원가를 결정한다.
#     조회 3회 → 매장당 월 386회 → 1팩 = 25매장
#     조회 1회 → 매장당 월 219회 → 1팩 = 45매장
#   같은 50만원으로 수용 매장이 1.75배가 된다. 조회를 줄인 만큼 매장을 더 받는 구조다.
#
# ★ 1회여도 리뷰는 유실되지 않는다. 조회 윈도우가 최근 2일이라 폴링이 한 번 통째로
#   실패해도 다음 날 폴링이 같은 리뷰를 다시 가져온다. 늘리지 말 것 —
#   늘리는 순간 팩 경계를 넘고, 경계를 넘으면 50만원이 한꺼번에 더 붙는다.
#
# ★ 계정별 스케줄을 여기 넣지 말 것. dispatch_polls 가 DB 에서 대상을 골라 팬아웃한다.
POLL_HOURS = os.environ.get("COLLECT_POLL_HOURS", "10")

app.conf.beat_schedule = {
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
