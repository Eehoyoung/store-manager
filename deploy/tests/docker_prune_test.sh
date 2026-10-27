#!/usr/bin/env bash
# deploy/docker-prune.sh 정적 검사 - 볼륨·컨테이너·네트워크·system prune 명령이 없어야 한다.
#   bash deploy/tests/docker_prune_test.sh
set -u
cd "$(dirname "$0")/../.."
# 주석 줄은 제외하고 실행되는 줄만 본다.
if grep -vE '^\s*#' deploy/docker-prune.sh | grep -qE 'volume|container (prune|rm)|network|system prune|rm -rf'; then
  echo "FAIL docker-prune.sh 에 볼륨·컨테이너·네트워크 정리 명령이 있다"; exit 1
fi
grep -q 'image prune' deploy/docker-prune.sh && grep -q 'builder prune' deploy/docker-prune.sh || { echo "FAIL 이미지·빌드 캐시 정리 명령이 없다"; exit 1; }
echo "ok   docker-prune.sh 는 이미지·빌드 캐시만 정리한다"
