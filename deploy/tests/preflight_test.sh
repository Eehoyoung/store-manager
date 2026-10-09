#!/usr/bin/env bash
# deploy/preflight.sh 셸 테스트 — 가짜 env 로 판정만 확인한다. 네트워크·git 은 건너뛴다.
#   bash deploy/tests/preflight_test.sh
set -u
cd "$(dirname "$0")/../.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
SECRET=sekret-value-should-never-print
echo '*/5 * * * * root /opt/storemanager/deploy/auto-deploy.sh' >"$T/cron"

# env.example 의 모든 키를 비밀 값으로 채운 뒤, 케이스별로 DataAPI 두 줄만 바꾼다.
base() { grep -oE '^[A-Z_][A-Z0-9_]*=' deploy/env.example | sed "s/=\$/=$SECRET/" | grep -vE '^DATAAPI_(BASE_URL|WRITE_ENABLED)='; }
run() { ENV_FILE="$T/env" CRON_FILE="$T/cron" DEPLOY_LOG="$T/none" SKIP_NET=1 SKIP_GIT=1 bash deploy/preflight.sh; }

ok=0; bad=0
check() { # 이름 기대종료코드 출력에_있어야_할_문자열
  out=$(run); code=$?
  if [ "$code" = "$2" ] && echo "$out" | grep -qF -- "$3" && ! echo "$out" | grep -q "$SECRET"; then
    echo "ok   $1"; ok=$((ok + 1))
  else
    echo "FAIL $1 (exit=$code)"; echo "$out" | sed 's/^/     /'; bad=$((bad + 1))
  fi
}

{ base; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "개발계 + 등록 false 명시 → 통과" 0 "PASS  DATAAPI_WRITE_ENABLED = false"

{ base; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; } >"$T/env"
check "등록 키 누락 → 실패" 1 "FAIL  DATAAPI_WRITE_ENABLED 가 env 에 없다"

{ base; echo DATAAPI_BASE_URL=https://api.mydatahub.co.kr; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "운영계 → 실패" 1 "개발계가 아니다 (api.mydatahub.co.kr)"

{ base; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; echo DATAAPI_WRITE_ENABLED=true; } >"$T/env"
check "등록 true → 실패" 1 "FAIL  DATAAPI_WRITE_ENABLED 가 false 가 아니다"

{ base | grep -v '^JWT_SECRET='; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "다른 키 누락 → 키 이름만 출력" 1 "서버 env 에 없는 키: JWT_SECRET"

{ base; echo 'DATAAPI_BASE_URL="https://datahub-dev.scraping.co.kr"'; echo DATAAPI_WRITE_ENABLED=true; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "따옴표·중복 키(마지막이 이김) → 통과" 0 "PASS  DATAAPI_BASE_URL = 개발계"

{ base | sed 's/^PORTONE_STORE_ID=.*/PORTONE_STORE_ID=/'; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "포트원 키 빈 값 → 실패(키 이름만)" 1 "포트원 키가 비었다: PORTONE_STORE_ID"

{ base | sed 's/^PROMOTION_CODE=.*/PROMOTION_CODE=/'; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "쿠폰 빈 값 → 안내만(통과)" 0 "INFO  PROMOTION_CODE 가 비었다"

: >"$T/cron"; { base; echo DATAAPI_BASE_URL=https://datahub-dev.scraping.co.kr; echo DATAAPI_WRITE_ENABLED=false; } >"$T/env"
check "cron 없음 → 실패" 1 "FAIL  auto-deploy cron 이 없다"

echo "== ok $ok / fail $bad"
[ "$bad" -eq 0 ]
