FROM python:3.12-slim
WORKDIR /app
# ★ PYTHONPATH 를 명시한다. celery 명령으로 뜨면 sys.path[0] 이 /usr/local/bin 이라
#   함수 안의 지연 임포트(from credentials import ...)가 ModuleNotFoundError 로 죽는다.
#   모듈 최상단 임포트는 시작 시점에 해결돼 통과하므로 이 버그는 실행 중에만 드러난다.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . ./
# -Q 로 publish 큐를 함께 듣는다. 없으면 게시 태스크가 실행되지 않고 쌓이기만 한다.
CMD ["celery", "-A", "celery_app", "worker", "--beat", "-Q", "celery,publish", "--loglevel=INFO"]
