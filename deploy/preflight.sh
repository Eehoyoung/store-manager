#!/usr/bin/env bash
# 운영 서버 사전점검 — 읽기 전용. 무엇도 바꾸지 않는다(2026-10-09).
#
#   sudo bash /opt/storemanager/deploy/preflight.sh
#
# ★ 비밀값의 **값**은 출력하지 않는다. 키 이름과 판정만 낸다. DATAAPI_BASE_URL 만 예외로
#   개발계/운영계 판정을 위해 호스트 이름을 보여 준다(비밀값이 아니다).
# ★ FAIL 이 하나라도 있으면 종료 코드 1.
#
# 테스트·다른 경로용으로 덮어쓸 수 있는 값:
#   ENV_FILE      서버 env           (기본 /etc/storemanager/env)
#   REPO_DIR      저장소             (기본 이 스크립트의 상위 디렉터리)
#   CRON_FILE     auto-deploy cron   (기본 /etc/cron.d/storemanager-deploy)
#   DEPLOY_LOG    배포 로그          (기본 /var/log/storemanager-deploy.log)
#   SITE_URL      공개 주소          (기본 https://review.sodamlabs.kr)
#   PUBLIC_IP     서버 고정 IP       (기본 15.165.196.205)
#   SKIP_NET=1    네트워크 점검 생략 / SKIP_GIT=1 git 점검 생략
set -u

REPO_DIR=${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
ENV_FILE=${ENV_FILE:-/etc/storemanager/env}
EXAMPLE=${EXAMPLE:-$REPO_DIR/deploy/env.example}
CRON_FILE=${CRON_FILE:-/etc/cron.d/storemanager-deploy}
DEPLOY_LOG=${DEPLOY_LOG:-/var/log/storemanager-deploy.log}
SITE_URL=${SITE_URL:-https://review.sodamlabs.kr}
PUBLIC_IP=${PUBLIC_IP:-15.165.196.205}
DEV_HOST=datahub-dev.scraping.co.kr

FAILS=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; FAILS=$((FAILS + 1)); }
info() { echo "INFO  $1"; }

# env 값 읽기 — 마지막 줄이 이긴다(compose 와 같은 규칙). 앞뒤 따옴표·CR 제거.
env_val() {
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '\r' | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}
has_key() { grep -qE "^$1=" "$ENV_FILE" 2>/dev/null; }

echo "== 소담리뷰 운영 사전점검 $(date -u +%FT%TZ)"

# 1) env 파일과 키
if [ ! -r "$ENV_FILE" ]; then
  fail "env 파일을 읽을 수 없다: $ENV_FILE (sudo 로 실행했는지 확인)"
else
  missing=$(grep -oE '^[A-Z_][A-Z0-9_]*=' "$EXAMPLE" | tr -d = | while read -r k; do has_key "$k" || echo "$k"; done | tr '\n' ' ')
  if [ -z "$missing" ]; then pass "env.example 의 키가 서버 env 에 모두 있다"
  else fail "서버 env 에 없는 키: $missing"; fi

  # 2) DataAPI 개발계
  if ! has_key DATAAPI_BASE_URL; then
    fail "DATAAPI_BASE_URL 이 env 에 없다 — compose 기본값에 맡기지 말고 명시할 것"
  else
    host=$(env_val DATAAPI_BASE_URL | sed -E 's#^[a-z]+://##; s#/.*$##')
    if [ "$host" = "$DEV_HOST" ]; then pass "DATAAPI_BASE_URL = 개발계 ($host)"
    else fail "DATAAPI_BASE_URL 이 개발계가 아니다 ($host) — 테스트 중에는 개발계여야 한다"; fi
  fi

  # 3) 답글 등록 명시적 false
  if ! has_key DATAAPI_WRITE_ENABLED; then
    fail "DATAAPI_WRITE_ENABLED 가 env 에 없다 — 옛 compose 는 이때 등록을 켠다"
  elif [ "$(env_val DATAAPI_WRITE_ENABLED | tr 'A-Z' 'a-z')" = "false" ]; then
    pass "DATAAPI_WRITE_ENABLED = false (명시)"
  else
    fail "DATAAPI_WRITE_ENABLED 가 false 가 아니다 — 실매장 리뷰에 답글이 달릴 수 있다"
  fi
fi

# 4) auto-deploy cron
if [ -f "$CRON_FILE" ] && grep -q 'auto-deploy.sh' "$CRON_FILE"; then
  pass "auto-deploy cron 등록됨 ($CRON_FILE)"
else
  fail "auto-deploy cron 이 없다 ($CRON_FILE) — deploy/README.md §2.0"
fi
if [ -x "$REPO_DIR/deploy/auto-deploy.sh" ]; then pass "auto-deploy.sh 실행 권한 있음"
else fail "auto-deploy.sh 에 실행 권한이 없다 — sudo chmod 700 $REPO_DIR/deploy/auto-deploy.sh"; fi
[ -f /run/storemanager-deploy.failed ] && info "CI 실패로 멈춘 커밋 표시가 있다: $(cut -c1-7 /run/storemanager-deploy.failed)"
if [ -r "$DEPLOY_LOG" ]; then
  info "배포 로그 마지막 3줄:"; tail -n3 "$DEPLOY_LOG" | sed 's/^/        /'
else
  info "배포 로그가 없다 ($DEPLOY_LOG) — cron 이 한 번도 돌지 않았을 수 있다"
fi

# 5) git
if [ "${SKIP_GIT:-0}" != 1 ]; then
  owner=$(stat -c %U "$REPO_DIR" 2>/dev/null || echo root)
  G="git -C $REPO_DIR"; [ "$(id -un)" != "$owner" ] && G="sudo -u $owner git -C $REPO_DIR"
  head=$($G rev-parse HEAD 2>/dev/null)
  remote=$($G ls-remote origin refs/heads/master 2>/dev/null | cut -f1)
  if [ -z "$remote" ]; then fail "origin/master 를 조회하지 못했다(네트워크·권한)"
  elif [ "$head" = "$remote" ]; then pass "서버 HEAD = origin/master (${head:0:7})"
  else fail "서버 HEAD ${head:0:7} ≠ origin/master ${remote:0:7} — 배포가 밀려 있다"; fi
  dirty=$($G status --porcelain 2>/dev/null)
  if [ -z "$dirty" ]; then pass "git status 깨끗함"
  else fail "서버 저장소가 더럽다 — fast-forward 가 실패한다: $(echo "$dirty" | head -n5 | tr '\n' ' ')"; fi
fi

# 6) 네트워크
if [ "${SKIP_NET:-0}" != 1 ]; then
  # 서버 안에서 자기 공인 IP 로 붙는 것은 Lightsail 방화벽을 거치지 않을 수 있어 판정하지 않는다.
  info "443 직접 차단은 서버 밖(PC)에서 확인: curl -k --max-time 5 https://$PUBLIC_IP  → 타임아웃이면 정상"
  bi=$(curl -fsS --max-time 10 "$SITE_URL/api/v1/legal/business-info" 2>/dev/null)
  if [ -z "$bi" ]; then fail "사업자 정보 API 응답 없음 ($SITE_URL)"
  else
    pending=$(echo "$bi" | grep -oE '"pending":\[[^]]*\]' | sed 's/"pending"://')
    if [ "$pending" = "[]" ]; then pass "사업자 정보 pending 없음 — BUSINESS_INFO_REQUIRED=true 전환 가능"
    else info "사업자 정보 pending: $pending (통신판매업 신고 전이면 정상)"; fi
  fi
fi

echo "== FAIL $FAILS 건"
[ "$FAILS" -eq 0 ]
