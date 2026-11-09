#!/usr/bin/env bash
# 배포 후 확인 — 읽기 전용. 재시작·쓰기를 하지 않는다(2026-11-06, 런북 2단계를 한 줄로).
#
#   sudo bash /opt/storemanager/deploy/postdeploy-check.sh <검사한 커밋 sha>
#
# 보는 것: 운영 커밋 = 검사한 커밋 · 최근 배포 로그에 '빌드 실패' 없음 · 컨테이너 전부 running(healthcheck 있으면 healthy)
#          · api-spring 의 /ops-status 마운트 · api-spring 실행 커밋(APP_DEPLOY_SHA) = 검사한 커밋 · 대표 결정 전 위험 플래그가 꺼져 있음.
# ★ 플래그는 값을 출력하지 않는다. 컨테이너 안에서 '꺼져 있는가' 만 종료 코드로 판정한다. env 파일은 읽지 않는다.
# ★ FAIL 이 하나라도 있으면 종료 코드 1. FAIL 없이 WARN 만 있으면 3(실행 커밋이 비어 있음 — 손으로 up 한 경우).
#
# 테스트·다른 경로용으로 덮어쓸 수 있는 값:
#   REPO_DIR    저장소     (기본 이 스크립트의 상위 디렉터리)
#   DEPLOY_LOG  배포 로그  (기본 /var/log/storemanager-deploy.log)
#   DC          compose 명령(기본 docker compose -f $REPO_DIR/deploy/docker-compose.prod.yml --env-file /etc/storemanager/env)
set -u

[ $# -eq 1 ] || { echo "사용법: postdeploy-check.sh <검사한 커밋 sha>" >&2; exit 2; }
WANT=$1
case $WANT in *[!0-9a-f]*|'') echo "sha 는 소문자 16진수" >&2; exit 2 ;; esac
[ ${#WANT} -ge 7 ] || { echo "sha 는 7자 이상" >&2; exit 2; }
REPO_DIR=${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
DEPLOY_LOG=${DEPLOY_LOG:-/var/log/storemanager-deploy.log}
DC=${DC:-"docker compose -f $REPO_DIR/deploy/docker-compose.prod.yml --env-file /etc/storemanager/env"}

# 서비스:플래그. 켜기는 대표 결정이다 — 켰다면 이 목록에서 빼는 것도 같은 결정에 넣는다.
RISK_FLAGS="api-spring:TIERED_PRICING_ENABLED api-spring:BILLING_DUNNING_NOTIFY_ENABLED api-spring:ALIMTALK_ENABLED worker:BACKFILL_ENABLED worker:DATAAPI_WRITE_ENABLED ai-python:GOLDENSET_EVAL_ENABLED"

fails=0; warns=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; fails=$((fails + 1)); }
warn() { echo "WARN $1"; warns=$((warns + 1)); }

HEAD=$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null)
case "$HEAD" in
  "$WANT"*) pass "운영 커밋 = ${WANT}" ;;
  *) fail "운영 커밋 ${HEAD:0:7} ≠ 검사한 커밋 ${WANT:0:7} (자동 배포 아직이면 몇 분 뒤 다시)" ;;
esac

if [ ! -r "$DEPLOY_LOG" ]; then fail "배포 로그 없음: $DEPLOY_LOG"
elif tail -20 "$DEPLOY_LOG" | grep -q '빌드 실패'; then fail "최근 배포 로그에 '빌드 실패'"
else pass "최근 배포 로그에 '빌드 실패' 없음"; fi

PS=$($DC ps -a --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null)
if [ -z "$PS" ]; then fail "compose ps 결과 없음"
else
  while read -r svc state health; do
    if [ "$state" != running ]; then fail "$svc: $state"
    elif [ -n "$health" ] && [ "$health" != healthy ]; then fail "$svc: $health"
    else pass "$svc: running${health:+ / $health}"; fi
  done <<< "$PS"
fi

if $DC exec -T api-spring test -d /ops-status 2>/dev/null; then pass "api-spring /ops-status 마운트"
else fail "api-spring /ops-status 마운트 없음"; fi

# 점검 화면 '배포 반영' 은 이 값으로 판정한다. 값은 출력하지 않는다 — 10=다름 11=검사한 커밋으로 시작 12=비었음.
$DC exec -T api-spring sh -c "case \"\${APP_DEPLOY_SHA:-}\" in '') exit 12 ;; $WANT*) exit 11 ;; *) exit 10 ;; esac" >/dev/null 2>&1
case $? in
  11) pass "api-spring 실행 커밋 = 검사한 커밋" ;;
  12) warn "api-spring 실행 커밋 비어 있음 — 손으로 up 한 경우. 점검 화면 '배포 반영' UNKNOWN(README 9장 표)" ;;
  10) fail "api-spring 실행 커밋 ≠ 검사한 커밋 (컨테이너가 새 빌드로 바뀌지 않음)" ;;
  *) fail "api-spring 실행 커밋 확인 불가(컨테이너 상태 확인)" ;;
esac

for sf in $RISK_FLAGS; do
  svc=${sf%%:*}; flag=${sf#*:}
  # 값은 컨테이너 밖으로 나오지 않는다 — 종료 코드만 본다. 10=꺼짐 아님 11=꺼짐, 그 밖(컨테이너 없음 등)=확인 불가.
  # ★ Spring 은 TRUE·1·on·yes 도 켜짐으로 읽는다. 그래서 비었거나 정확히 false 일 때만 꺼짐으로 친다.
  $DC exec -T "$svc" sh -c "[ \"\${$flag:-false}\" = false ] && exit 11 || exit 10" >/dev/null 2>&1
  case $? in
    10) fail "$svc $flag 꺼짐 아님(대표 결정 전)" ;;
    11) pass "$svc $flag 꺼짐" ;;
    *) fail "$svc $flag 확인 불가(컨테이너 상태 확인)" ;;
  esac
done

echo "FAIL ${fails}건$([ "$warns" -gt 0 ] && echo " · WARN ${warns}건")"
[ "$fails" -eq 0 ] || exit 1
[ "$warns" -eq 0 ] || exit 3
