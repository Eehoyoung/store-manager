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
#   PUBLIC_IP     서버 고정 IP — 기본값 없음. 주면 443 직접 차단 확인 명령을 안내한다
#   SKIP_NET=1    네트워크 점검 생략 / SKIP_GIT=1 git 점검 생략
set -u

REPO_DIR=${REPO_DIR:-$(cd "$(dirname "$0")/.." && pwd)}
ENV_FILE=${ENV_FILE:-/etc/storemanager/env}
EXAMPLE=${EXAMPLE:-$REPO_DIR/deploy/env.example}
CRON_FILE=${CRON_FILE:-/etc/cron.d/storemanager-deploy}
DEPLOY_LOG=${DEPLOY_LOG:-/var/log/storemanager-deploy.log}
SITE_URL=${SITE_URL:-https://review.sodamlabs.kr}
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

  # 3-1) 테스트 매장 절차에 필요한 값 — 값은 보지 않고 비었는지만 본다.
  #  포트원 키가 비면 결제 화면이 닫혀 카드 등록(=체험 시작)을 할 수 없다.
  #  쿠폰이 비면 카드 등록 즉시 33,000원 청구다(체험 없음).
  empty=""
  for k in PORTONE_API_SECRET PORTONE_STORE_ID PORTONE_INICIS_CHANNEL_KEY; do [ -n "$(env_val "$k")" ] || empty="$empty $k"; done
  if [ -z "$empty" ]; then pass "포트원 키 3종 값 있음 — 테스트 채널인지 실채널인지는 포트원 콘솔에서 확인"
  else fail "포트원 키가 비었다:$empty — 카드 등록·체험 시작 불가"; fi
  if [ -n "$(env_val PROMOTION_CODE)" ]; then pass "PROMOTION_CODE 값 있음 — 쿠폰 체험 열림"
  else info "PROMOTION_CODE 가 비었다 — 쿠폰 체험이 열리지 않아 카드 등록 즉시 청구된다"; fi

  # 3-2) api-spring 기동 게이트 — 비면 다음 재빌드 때 api-spring 이 뜨지 않는다(MailProperties).
  #  운영 compose 의 MAIL_REQUIRED 기본값은 true 다. 지금 컨테이너가 옛 빌드라 멀쩡해 보여도
  #  자동 배포가 풀려 api-spring 이 교체되는 순간 API 전체가 내려간다.
  mail_req=$(env_val MAIL_REQUIRED | tr 'A-Z' 'a-z'); [ -n "$mail_req" ] || mail_req=true
  if [ "$mail_req" = "true" ]; then
    empty=""
    for k in MAIL_USERNAME MAIL_PASSWORD; do [ -n "$(env_val "$k")" ] || empty="$empty $k"; done
    if [ -z "$empty" ]; then pass "메일 계정 값 있음 (MAIL_REQUIRED=true)"
    else fail "MAIL_REQUIRED=true 인데 비었다:$empty — api-spring 이 기동하지 않는다. 계정을 넣거나 MAIL_REQUIRED=false"; fi
  else
    info "MAIL_REQUIRED=false — 관리자·본부 OTP 메일이 나가지 않는다"
  fi
  if [ -n "$(env_val APP_ADMIN_EMAILS)" ]; then pass "APP_ADMIN_EMAILS 값 있음"
  else info "APP_ADMIN_EMAILS 가 비었다 — 관리자 로그인 불가(fail-closed)"; fi
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
  # auto-deploy 는 merge 뒤에 빌드한다 — 빌드가 실패해도 HEAD 는 최신이라 아래 HEAD 점검은 PASS 로 나온다.
  last=$(grep -F '[deploy ' "$DEPLOY_LOG" | tail -n1)
  case $last in
    *"빌드 실패"*) fail "마지막 배포가 빌드 실패다 — HEAD 가 최신이어도 운영은 옛 빌드. 원인: dc up -d --build 2>&1 | tail -40" ;;
    *" → "*) info "마지막 배포 뒤 '완료'가 없다 — 빌드 중이거나 멈췄다: ps -ef | grep -E 'auto-deploy|docker (compose|build)'" ;;
  esac
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
  if [ -n "${PUBLIC_IP:-}" ]; then info "443 직접 차단은 서버 밖(PC)에서 확인: curl -k --max-time 5 https://$PUBLIC_IP  → 타임아웃이면 정상"
  else info "SKIP 443 직접 차단 안내 — PUBLIC_IP 미지정(서버 IP 는 저장소에 적지 않는다)"; fi
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
