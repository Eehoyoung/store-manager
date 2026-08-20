# TODO(Sprint 2): beat 스케줄(폴링 주기), 큐 라우팅, 재시도 정책(지수 백오프) 설정.
"""Celery 앱 정의. 브로커/백엔드는 REDIS_URL 환경변수로 지정한다."""
import os

from celery import Celery

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")

app = Celery("worker", broker=REDIS_URL, backend=REDIS_URL, include=["tasks"])
