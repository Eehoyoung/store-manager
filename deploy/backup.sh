#!/bin/sh
# PostgreSQL 백업.
#
# ★ 이 DB 에는 배달앱 자격증명 암호문과 리뷰 원문이 들어 있다. 백업본도 같은 등급으로 다뤄야 한다.
# ★ 백업이 '돌고 있다' 와 '복구된다' 는 다르다. 최소 한 번은 실제로 복구해 보고 운영에 넣을 것.
set -eu
# pg_dump 가 실패해도 gzip 헤더 때문에 파일이 비지 않는다. 파이프 실패를 잡는다(busybox ash 지원).
set -o pipefail

BACKUP_DIR=${BACKUP_DIR:-/backup}
# 점검 화면용 — 시각과 OK/FAIL 만 쓴다(종료코드는 덤프 폴더 쪽 backup.status 에만). 폴더가 없으면 건너뜀.
OPS_STATUS=${OPS_STATUS:-/ops-status}  # compose 가 호스트 ops-status/backup 하위 폴더만 여기에 붙인다
KEEP_DAYS=${BACKUP_KEEP_DAYS:-14}
STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$BACKUP_DIR/storemanager-$STAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

# 결과 한 줄을 backup.status 에 남긴다. 실패면 FAIL. 같은 결과를 ops-status 에도 덮어써 점검 화면 '백업' 이 읽는다.
trap 'c=$?; [ "$c" -eq 0 ] && r=OK || r=FAIL; t=$(date -u +%FT%TZ); echo "$t $r exit=$c" >> "$BACKUP_DIR/backup.status" 2>/dev/null || true; { [ -d "$OPS_STATUS" ] && echo "$t $r" > "$OPS_STATUS/backup.status"; } 2>/dev/null || true' EXIT

# --clean --if-exists: 복구 시 기존 객체를 지우고 새로 만든다. 부분 복구로 인한
# 스키마 불일치가 가장 흔한 복구 실패 원인이다.
PGPASSWORD="$POSTGRES_PASSWORD" pg_dump \
	-h "${POSTGRES_HOST:-postgres}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
	--clean --if-exists --no-owner \
	| gzip > "$FILE"

# 크기가 0 이면 실패다. 조용히 빈 파일을 쌓아 두면 '백업이 있다' 고 착각하게 된다.
if [ ! -s "$FILE" ]; then
	echo "[backup] 실패: 결과 파일이 비어 있음 $FILE" >&2
	rm -f "$FILE"
	exit 1
fi

echo "[backup] $FILE ($(du -h "$FILE" | cut -f1))"

# 보관기간 경과분 삭제. 여기서만 지운다 — 원격 사본은 별도 정책을 따른다.
find "$BACKUP_DIR" -name 'storemanager-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
