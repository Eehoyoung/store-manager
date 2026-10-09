#!/bin/sh
# 자동 배포 — master 에 CI 가 통과한 새 커밋이 있으면 끌어와 다시 올린다 (2026-09-29).
#
# 서버가 GitHub 을 당겨 온다. GitHub Actions 가 SSH 로 밀어 넣는 방식은 쓰지 않는다 —
#   22 포트를 GitHub 대역(수천 개)에 열어야 하고, 공개 저장소에 서버 접속 키를 둬야 한다.
#
# cron (/etc/cron.d/storemanager-deploy) — 5분마다:
#   */5 * * * * root /opt/storemanager/deploy/auto-deploy.sh >> /var/log/storemanager-deploy.log 2>&1
#
# ★ CI 가 전부 초록이 아닌 커밋은 올리지 않는다. 실패한 커밋은 다음 커밋이 올 때까지 멈춰 있다.
# ★ 빌드가 실패하면 기존 컨테이너가 그대로 돈다(compose 는 빌드가 끝나야 교체한다).
set -eu
cd "$(dirname "$0")/.."

exec 9>/run/storemanager-deploy.lock
flock -n 9 || exit 0 # 이전 배포가 아직 빌드 중

REPO=Eehoyoung/store-manager
# git 은 저장소 소유자로 돌린다. root 로 돌리면 .git 안에 root 파일이 생겨 사람이 치는 git pull 이 깨진다.
G="sudo -u $(stat -c %U .) git"
DC="docker compose -f deploy/docker-compose.prod.yml --env-file ${ENV_FILE:-/etc/storemanager/env}"
FAILED_MARK=/run/storemanager-deploy.failed
log() { echo "[deploy $(date -u +%FT%TZ)] $*"; }

$G fetch -q origin master
HEAD=$($G rev-parse HEAD)
NEW=$($G rev-parse origin/master)
[ "$HEAD" = "$NEW" ] && exit 0

# 공개 저장소라 토큰 없이 조회한다. 새 커밋이 있을 때만 부르므로 시간당 60회 제한에 닿지 않는다.
CI=$(curl -fsS "https://api.github.com/repos/$REPO/commits/$NEW/check-runs?per_page=100" | python3 -c '
import json, sys
runs = json.load(sys.stdin)["check_runs"]
done = [r["conclusion"] for r in runs if r["status"] == "completed"]
if not runs or len(done) < len(runs):
    print("wait")
elif all(c in ("success", "skipped") for c in done):
    print("ok")
else:
    print("fail")') || { log "CI 조회 실패(curl·python3) — 다음 주기에 재시도"; CI=wait; }

case $CI in
  wait) exit 0 ;; # CI 실행 중 — 다음 주기에 다시 본다
  fail)
    # 같은 커밋 실패를 5분마다 다시 적지 않는다
    [ "$(cat "$FAILED_MARK" 2>/dev/null)" = "$NEW" ] || { log "$NEW CI 실패 — 배포 안 함"; echo "$NEW" >"$FAILED_MARK"; }
    exit 0 ;;
esac

CHANGED=$($G diff --name-only "$HEAD" "$NEW")
$G merge -q --ff-only "$NEW"
log "${HEAD%"${HEAD#???????}"} → ${NEW%"${NEW#???????}"}"

# 바뀌지 않은 서비스는 빌드 캐시로 끝나고 컨테이너도 교체되지 않는다.
if ! $DC up -d --build; then
  log "빌드 실패 — 기존 컨테이너 유지. 원인 확인 후 수동: dc up -d --build"
  exit 1
fi
# Caddyfile 은 바인드 마운트라 컨테이너가 교체되지 않는다
if echo "$CHANGED" | grep -qx 'deploy/Caddyfile'; then $DC restart caddy; fi
log "완료"
