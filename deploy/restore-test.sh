#!/bin/sh
# 백업 복구 시험 — 가장 최근 백업을 임시 postgres 컨테이너에 풀어 보고 sanity 검사한다.
#
# ★ 운영 DB·운영 컨테이너·운영 볼륨은 건드리지 않는다. 임시 컨테이너(restore-test-<pid>)는
#   네트워크 없이(--network none), 메모리 상한을 두고 익명 볼륨으로 뜨고, 끝나면 trap 으로 볼륨째 지운다.
# ★ 알림 경로: 서버 호스트에는 메일 클라이언트가 없다. 결과 한 줄을 $BACKUP_DIR/restore-test.log 에 남긴다.
#   실패는 종료코드 1 + 로그의 FAIL 줄. 사람이 보는 알림은 아직 없다.
#
# 덮어쓸 수 있는 값: BACKUP_DIR(기본 deploy/backups) · PG_IMAGE(운영 postgres 와 같은 이미지 — 덤프에 CREATE EXTENSION vector 가 있어 pgvector 가 필요)
set -eu

BACKUP_DIR=${BACKUP_DIR:-$(cd "$(dirname "$0")" && pwd)/backups}
PG_IMAGE=${PG_IMAGE:-pgvector/pgvector:pg16}
LOG="$BACKUP_DIR/restore-test.log"
NAME="restore-test-$$"
FILE=""
RESULT="FAIL 시작 전 중단"

# 어떤 경로로 끝나도 임시 컨테이너를 지우고 결과 한 줄을 남긴다.
cleanup() {
	docker rm -fv "$NAME" >/dev/null 2>&1 || true
	echo "$(date -u +%FT%TZ) $RESULT file=${FILE##*/}" >> "$LOG" 2>/dev/null || true
	echo "[restore-test] $RESULT" >&2
}
trap cleanup EXIT

FILE=$(ls -1t "$BACKUP_DIR"/storemanager-*.sql.gz 2>/dev/null | head -1 || true)
[ -n "$FILE" ] || { RESULT="FAIL 백업 파일 없음"; exit 1; }
# 이틀 넘은 최신 백업이면 백업 자체가 멈춘 것이다.
[ -n "$(find "$FILE" -mtime -2)" ] || { RESULT="FAIL 최신 백업이 2일 넘음"; exit 1; }
gzip -t "$FILE" || { RESULT="FAIL gzip 손상"; exit 1; }

# tmpfs 는 덤프 전체를 RAM 에 풀어 운영 인스턴스를 OOM 으로 몰 수 있다. --rm 은 SIGKILL 로 trap 이 못 돌 때의 보험.
docker run -d --rm --name "$NAME" --network none --memory 512m \
	-e POSTGRES_PASSWORD=restore-test "$PG_IMAGE" >/dev/null

i=0
until docker exec "$NAME" pg_isready -U postgres -q; do
	i=$((i + 1))
	[ "$i" -lt 30 ] || { RESULT="FAIL 임시 postgres 기동 실패"; exit 1; }
	sleep 2
done
# 초기화 중 임시 서버가 내려갔다 다시 뜨는 구간을 넘긴다.
sleep 3

gunzip -c "$FILE" | docker exec -i "$NAME" psql -U postgres -d postgres -q -v ON_ERROR_STOP=1 >/dev/null 2>&1 \
	|| { RESULT="FAIL 복원 중 오류(행 값이 cron 로그에 남지 않게 stderr 는 버림)"; exit 1; }

q() { docker exec "$NAME" psql -U postgres -d postgres -Atc "$1"; }
VER=$(q "select max(version::int) from flyway_schema_history where success and version ~ '^[0-9]+\$'") || VER=""
[ -n "$VER" ] || { RESULT="FAIL flyway_schema_history 없음"; exit 1; }

COUNTS=""
for t in store subscription unified_review; do
	n=$(q "select count(*) from $t") || n=-1
	COUNTS="$COUNTS $t=$n"
	# 테이블이 없으면(-1) 실패. 빈 테이블은 정상일 수 있다(체험 전 subscription 0) — store 만 1건 이상을 요구한다.
	[ "$n" -ge 0 ] && { [ "$t" != store ] || [ "$n" -gt 0 ]; } || { RESULT="FAIL 행 수:$COUNTS flyway=V$VER"; exit 1; }
done

RESULT="OK flyway=V$VER$COUNTS"
