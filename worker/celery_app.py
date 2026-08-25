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

# ── 수집 주기 (2026-08-25 운영자 결정 — T-6 해소) ─────────────────────────────
#
# ★ 하루 3회: 10시 / 16시 / 20시 (KST). 시각은 식사 시간대 뒤에 붙였다.
#     10시 → 전날 저녁·야식 리뷰
#     16시 → 점심 리뷰
#     20시 → 저녁 리뷰
#   균등 간격(8시간)이 아닌 이유다. 새벽 리뷰는 10시까지 기다리는데, 답글 지연
#   몇 시간은 문제가 되지 않고 그 시간대 폴링은 대부분 빈 조회다.
#
# ★ 이 숫자가 곧 원가다. 조회는 전체 호출의 약 70% 를 차지한다.
#   3플랫폼 매장 기준 월 호출: 조회 9/일 × 30 = 270 + 등록 5/일 × 30 = 150 → 420회.
#   1회를 늘리면 매장당 월 90회(약 21%)가 늘어난다. 바꾸기 전에 CLAUDE.md 의
#   호출 단가 손익표를 다시 볼 것.
#
# ★ 계정별 스케줄을 여기 넣지 말 것. dispatch_polls 가 DB 에서 대상을 골라 팬아웃한다.
POLL_HOURS = os.environ.get("COLLECT_POLL_HOURS", "10,16,20")

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
