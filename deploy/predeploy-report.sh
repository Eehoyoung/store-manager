#!/usr/bin/env bash
# 배포 전 차이 보고 — git 만 읽는다. env 파일·컨테이너·DB 를 보지 않는다(2026-11-06).
#
#   bash deploy/predeploy-report.sh <운영 커밋 sha> [배포할 커밋, 기본 origin/master]
#
# 보는 것: 커밋 수 · Flyway 마이그레이션(새 파일 / ★기존 파일 수정·삭제) · compose 에 새로 생긴 env 키와 기본값
#          · 바뀐 서비스 · 대표 결정 전 위험 플래그의 compose 기본값.
# ★ 주의가 하나라도 있으면 종료 코드 1 — 기존 마이그레이션 수정·삭제, 기본값 없는 새 필수 env(:?), 위험 플래그 기본값이 false 아님.
# REPO_DIR 로 저장소를 바꿀 수 있다(시험용).
set -u

[ $# -ge 1 ] && [ $# -le 2 ] || { echo "사용법: predeploy-report.sh <운영 sha> [배포할 커밋]" >&2; exit 2; }
REPO_DIR=${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
g() { git -C "$REPO_DIR" "$@"; }
FROM=$(g rev-parse --verify -q "$1^{commit}") || { echo "운영 커밋을 찾지 못함: $1 (git fetch 했는지)" >&2; exit 2; }
TO=$(g rev-parse --verify -q "${2:-origin/master}^{commit}") || { echo "배포할 커밋을 찾지 못함: ${2:-origin/master}" >&2; exit 2; }
MIG=api-spring/src/main/resources/db/migration
COMPOSE=deploy/docker-compose.prod.yml
# 위험 플래그 목록은 postdeploy-check.sh 가 정본이다 — 배포할 커밋의 것을 읽는다.
RISK_FLAGS=$(g show "$TO:deploy/postdeploy-check.sh" 2>/dev/null | sed -n 's/^RISK_FLAGS="\(.*\)"$/\1/p')

warns=0
warn() { echo "  ★ $1"; warns=$((warns + 1)); }

echo "== ${FROM:0:7} → ${TO:0:7}: 커밋 $(g rev-list --count "$FROM..$TO")개"
g merge-base --is-ancestor "$FROM" "$TO" || warn "운영 커밋이 배포할 커밋의 조상이 아님(되돌림·다른 갈래)"

echo "== Flyway 마이그레이션"
MIGS=$(g diff --name-status "$FROM" "$TO" -- "$MIG")
[ -z "$MIGS" ] && echo "  없음"
while read -r st path; do
  [ -z "$st" ] && continue
  case $st in
    A) echo "  새 파일 ${path##*/}" ;;
    *) warn "기존 마이그레이션 $st ${path##*/} — 운영 Flyway 체크섬 검증 실패로 기동이 멈춘다" ;;
  esac
done <<< "$MIGS"

echo "== compose 새 env 키"
NEW=$(g diff -U0 "$FROM" "$TO" -- "$COMPOSE" | grep -E '^\+ +[A-Z][A-Z0-9_]*: ' | sed 's/^+ *//')
[ -z "$NEW" ] && echo "  없음"
while IFS= read -r line; do
  [ -z "$line" ] && continue
  key=${line%%:*}
  case $line in
    *'${'*':?'*) warn "$key — 기본값 없는 필수값(:?), 서버 env 에 없으면 기동 실패" ;;
    *'${'*':-'*'}'*) def=${line#*:-}; echo "  $key (기본 '${def%%\}*}')" ;;
    *) echo "  $key (고정값)" ;;
  esac
done <<< "$NEW"

echo "== 바뀐 서비스"
CHANGED=$(g diff --name-only "$FROM" "$TO" | cut -d/ -f1 | sort -u)
for s in api-spring ai-python worker web deploy extension; do
  echo "$CHANGED" | grep -qx "$s" && echo "  $s$([ $s = extension ] && echo ' (확장 — 서버 배포와 무관, 스토어 제출 따로)')"
done
[ -z "$CHANGED" ] && echo "  없음"

echo "== 위험 플래그 compose 기본값(배포할 커밋)"
[ -z "$RISK_FLAGS" ] && warn "postdeploy-check.sh 의 RISK_FLAGS 를 읽지 못함"
for sf in $RISK_FLAGS; do
  flag=${sf#*:}
  def=$(g show "$TO:$COMPOSE" | grep -oE "\\\$\{$flag:-[^}]*\}" | head -1 | sed 's/.*:-//; s/}$//')
  if [ "$def" = false ]; then echo "  $flag 기본 false"
  else warn "$flag 기본 '${def:-없음}' — false 가 아님"; fi
done

echo "주의 ${warns}건"
[ "$warns" -eq 0 ]
