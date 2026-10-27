#!/bin/sh
# 쓰지 않는 도커 이미지·빌드 캐시만 정리한다 (디스크 점검 70% 노랑 / 80% 빨강 대응).
# ★ 볼륨·컨테이너·네트워크는 절대 지우지 않는다 - DB·Redis 데이터가 볼륨에 있다.
#
# cron 예시 (경로는 자기 환경에 맞게 바꿀 것) - 매주 일요일 04:00:
#   0 4 * * 0 root <저장소 경로>/deploy/docker-prune.sh >> <로그 경로> 2>&1
set -eu

echo "[prune $(date -u +%FT%TZ)] 정리 전"
df -h /
docker image prune -af --filter until=168h
docker builder prune -af --filter until=168h
echo "[prune $(date -u +%FT%TZ)] 정리 후"
df -h /
