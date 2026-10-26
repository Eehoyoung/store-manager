#!/usr/bin/env bash
# restore-test.sh · rotate-secrets.sh · backup.sh 정적 검사 — 실행하지 않고 문장만 본다.
#   bash deploy/tests/ops_scripts_test.sh
set -u
cd "$(dirname "$0")/../.."
ok=0; bad=0
t() { # 이름 명령...
  n=$1; shift
  if "$@"; then echo "ok   $n"; ok=$((ok + 1)); else echo "FAIL $n"; bad=$((bad + 1)); fi
}
R=deploy/restore-test.sh
code() { grep -vE '^\s*#' "$1"; }

for f in deploy/restore-test.sh deploy/rotate-secrets.sh deploy/backup.sh; do
  t "bash -n $f" bash -n "$f"
done
t "restore-test: trap EXIT 정리 존재" grep -qE "^trap cleanup EXIT" $R
t "restore-test: 임시 컨테이너 이름이 restore-test" grep -q 'NAME="restore-test-' $R
t "restore-test: 네트워크 미연결" grep -q -- '--network none' $R
t "restore-test: 운영 이름·볼륨·compose 미사용" bash -c "! grep -vE '^\s*#' $R | grep -qE 'compose|pgdata|storemanager-postgres| -v [^ ]+:|--volumes-from|docker (stop|kill)|drop (database|table)'"
t "restore-test: docker rm 은 \$NAME 만" bash -c "grep -vE '^\s*#' $R | grep 'docker rm' | grep -qv '\$NAME' && exit 1 || exit 0"
t "rotate: 비밀값 echo·log 출력 없음" bash -c "! grep -vE '^\s*#' deploy/rotate-secrets.sh | grep -E '(echo|log|printf).*(NEW_MASTER_KEY|OLD_MASTER_KEY|JWT_SECRET|INTERNAL_TOKEN)'"
t "rotate: run 에 -T (tty 불필요)" grep -q 'run -T --rm' deploy/rotate-secrets.sh
t "rotate: .next 정리 trap" grep -q '^trap .*NEXT' deploy/rotate-secrets.sh

echo "통과 $ok / 실패 $bad"
[ "$bad" -eq 0 ]
