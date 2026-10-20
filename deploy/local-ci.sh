#!/usr/bin/env bash
# 로컬 CI — .github/workflows/ci.yml 과 같은 단계를 이 PC 에서 돌리고, 전부 통과하면 그 커밋에
# GitHub 커밋 상태 context "local-ci" = success 를 기록한다(D76, 2026-10-21 Actions 한도 초과 우회).
# 서버 auto-deploy.sh 는 deploy/ci_gate.py 로 이 상태를 check-runs 와 함께 인정한다.
#
#   bash deploy/local-ci.sh            # 검사만(상태 기록 없음)
#   bash deploy/local-ci.sh --record   # 검사 + 통과 시 HEAD 커밋에 상태 기록(gh 로그인 필요)
#   bash deploy/local-ci.sh --attest-merge <sha>  # 머지 커밋: 트리가 검사한 커밋과 같을 때만 상태 기록
#
# ★ 기록은 작업 트리가 깨끗하고 HEAD 가 origin 에 푸시된 커밋일 때만 한다 — 검사한 코드 = 상태가 붙는 커밋.
# ★ ci.yml 을 바꾸면 여기도 같이 바꿀 것. 메모리가 적은 PC 라 직렬로 돌리고 끝나면 Gradle 데몬을 내린다.
set -u
cd "$(dirname "$0")/.."
RECORD=0; [ "${1:-}" = "--record" ] && RECORD=1
SHA=$(git rev-parse HEAD)

# 머지 커밋 증명: 검사를 다시 돌리지 않고, HEAD 의 트리가 이미 local-ci=success 인 커밋의 트리와 바이트 동일할 때만 기록.
#   bash deploy/local-ci.sh --attest-merge <검사한 커밋 sha>
if [ "${1:-}" = "--attest-merge" ]; then
  TESTED=${2:?검사한 커밋 sha}
  REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
  [ -z "$(git status --porcelain)" ] || { echo "작업 트리가 깨끗하지 않다"; exit 2; }
  [ "$(git rev-parse "$SHA^{tree}")" = "$(git rev-parse "$TESTED^{tree}")" ]     || { echo "트리가 다르다 — 머지 커밋에서 --record 로 다시 검사할 것"; exit 1; }
  OWNER=${REPO%%/*}
  # 전체 목록(최신순)에서 소유자 계정이 쓴 local-ci 만 본다 - ci_gate.py 와 같은 기준.
  STATE=$(gh api "repos/$REPO/commits/$TESTED/statuses?per_page=100"     -q "[.[] | select(.context==\"local-ci\" and .creator.login==\"$OWNER\")][0].state")
  [ "$STATE" = success ] || { echo "검사한 커밋에 local-ci=success 가 없다($STATE)"; exit 1; }
  gh api "repos/$REPO/statuses/$SHA" -f state=success -f context=local-ci     -f description="local ci: same tree as ${TESTED:0:7}" >/dev/null && echo "커밋 상태 기록: local-ci=success (트리 동일 ${TESTED:0:7})"
  exit $?
fi

if [ "$RECORD" = 1 ]; then
  [ -z "$(git status --porcelain)" ] || { echo "작업 트리가 깨끗하지 않다 — 커밋한 코드만 검사·기록한다"; exit 2; }
  git fetch -q origin
  git branch -r --contains "$SHA" | grep -q . || { echo "HEAD($SHA)가 origin 에 없다 — 먼저 푸시"; exit 2; }
fi

LOG=$(mktemp -d)
FAILED=""
SUMMARY=""
step() { # 이름 명령...
  local name=$1; shift
  printf '== %-10s ' "$name"
  if ( "$@" ) >"$LOG/$name.log" 2>&1; then echo pass; SUMMARY="$SUMMARY $name"; else echo "FAIL (로그 $LOG/$name.log)"; FAILED="$FAILED $name"; fi
}

step spring    bash -c 'cd api-spring && ./gradlew build --no-daemon -q'
(cd api-spring && ./gradlew --stop -q >/dev/null 2>&1)
step ai-python bash -c 'cd ai-python && python -m pytest -q'
step worker    bash -c 'cd worker && python -m pytest -q'
step web       bash -c 'cd web && npm ci --silent && npm run build && npm test'
step extension bash -c 'cd extension && npm ci --silent && npm run typecheck && npm run test && npm run build'
step deploy    bash -c 'python3 deploy/ci_gate.py --self-check && bash deploy/tests/preflight_test.sh'

if [ -n "$FAILED" ]; then
  echo "실패:$FAILED — 상태를 기록하지 않는다"
  exit 1
fi
echo "전부 통과:$SUMMARY ($SHA)"
if [ "$RECORD" = 1 ]; then
  REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
  gh api "repos/$REPO/statuses/$SHA" -f state=success -f context=local-ci \
    -f description="local ci pass:$SUMMARY" >/dev/null && echo "커밋 상태 기록: local-ci=success"
fi
