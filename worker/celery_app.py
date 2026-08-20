# TODO(Sprint 2): 수집(poll_reviews) beat 스케줄 — 호출 단가 미확정으로 보류(CLAUDE.md FR-201).
"""Celery 앱 정의. 브로커/백엔드는 REDIS_URL 환경변수로 지정한다."""
import os

from celery import Celery

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")

app = Celery("worker", broker=REDIS_URL, backend=REDIS_URL, include=["tasks"])

# 게시(publish_drafts)만 큐·주기를 확정한다. 수집 주기는 여전히 TODO.
app.conf.task_routes = {"tasks.publish_drafts": {"queue": "publish"}}
app.conf.beat_schedule = {
    "publish-drafts-every-30s": {
        "task": "tasks.publish_drafts",
        "schedule": 30.0,
        "options": {"queue": "publish"},
    }
}
