#!/usr/bin/env bash
# 비밀값 교체 결과 확인 — 읽기 전용. 교체·재시작·쓰기를 하지 않는다(2026-11-08, 15번 2·4단계 확인을 한 줄로).
#
#   sudo bash /opt/storemanager/deploy/rotate-check.sh
#
# 보는 것: rotate.status 형식('<종료코드> <UTC 시각>')·종료코드 0·184일 이내(점검 화면과 같은 기준)
#          · 교체 로그의 마지막 [rotate] 줄이 '완료' · api-spring·worker running(healthcheck 있으면 healthy).
# ★ rotate.status 가 없으면 아직 교체 전이다 — INFO 로 알리고 나머지(로그)는 보지 않는다. 배포 직후에도 같은 명령을 쓴다.
# ★ 로그 내용은 출력하지 않는다(옛 env 보관 경로가 들어 있다). 판정만 낸다.
# ★ FAIL 이 하나라도 있으면 종료 코드 1. FAIL 없이 WARN 만 있으면 3(교체 로그를 읽을 수 없음).
#
# 테스트·다른 경로용으로 덮어쓸 수 있는 값:
#   REPO_DIR     저장소        (기본 이 스크립트의 상위 디렉터리)
#   STATUS_FILE  교체 결과     (기본 $REPO_DIR/deploy/ops-status/rotate.status)
#   ROTATE_LOG   교체 로그     (기본 /var/log/storemanager-rotate.log)
#   DC           compose 명령  (기본 docker compose -f $REPO_DIR/deploy/docker-compose.prod.yml --env-file /etc/storemanager/env)
set -u

REPO_DIR=${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
STATUS_FILE=${STATUS_FILE:-$REPO_DIR/deploy/ops-status/rotate.status}
ROTATE_LOG=${ROTATE_LOG:-/var/log/storemanager-rotate.log}
DC=${DC:-"docker compose -f $REPO_DIR/deploy/docker-compose.prod.yml --env-file /etc/storemanager/env"}
STALE_DAYS=184   # ReadinessEvaluator.ROTATE_STALE 와 같아야 한다

fails=0; warns=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; fails=$((fails + 1)); }
warn() { echo "WARN $1"; warns=$((warns + 1)); }

if [ ! -e "$STATUS_FILE" ]; then
  echo "INFO rotate.status 없음 — 아직 교체 전(점검 화면 '교체 cron 미등록')"
else
  line=$(head -1 "$STATUS_FILE" 2>/dev/null)
  if ! [[ $line =~ ^([0-9]+)\ ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z)$ ]]; then
    fail "rotate.status 형식이 '<종료코드> <UTC 시각>' 아님"
  else
    code=${BASH_REMATCH[1]}; at=${BASH_REMATCH[2]}
    age=$(( ($(date -u +%s) - $(date -u -d "$at" +%s)) / 86400 ))
    if [ "$code" != 0 ]; then fail "마지막 교체 실패(종료코드 $code, $at)"
    elif [ "$age" -gt "$STALE_DAYS" ]; then fail "마지막 교체 성공이 ${STALE_DAYS}일 넘음($at)"
    else pass "마지막 교체 성공($at, ${age}일 전)"; fi
  fi

  if [ ! -r "$ROTATE_LOG" ]; then warn "교체 로그를 읽을 수 없음: $ROTATE_LOG"
  elif grep '^\[rotate ' "$ROTATE_LOG" | tail -1 | grep -q '\] 완료$'; then pass "교체 로그 마지막 줄 '완료'"
  else fail "교체 로그 마지막 [rotate] 줄이 '완료' 아님 — 로그를 직접 열어 본다"; fi
fi

PS=$($DC ps -a --format '{{.Service}} {{.State}} {{.Health}}' api-spring worker 2>/dev/null)
if [ -z "$PS" ]; then fail "compose ps 결과 없음"
else
  while read -r svc state health; do
    if [ "$state" != running ]; then fail "$svc: $state"
    elif [ -n "$health" ] && [ "$health" != healthy ]; then fail "$svc: $health"
    else pass "$svc: running${health:+ / $health}"; fi
  done <<< "$PS"
fi

echo "FAIL ${fails}건$([ "$warns" -gt 0 ] && echo " · WARN ${warns}건")"
[ "$fails" -eq 0 ] || exit 1
[ "$warns" -eq 0 ] || exit 3
