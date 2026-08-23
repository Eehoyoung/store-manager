#!/bin/sh
# PostgreSQL 백업.
#
# ★ 이 DB 에는 배달앱 자격증명 암호문과 리뷰 원문이 들어 있다. 백업본도 같은 등급으로 다뤄야 한다.
# ★ 백업이 '돌고 있다' 와 '복구된다' 는 다르다. 최소 한 번은 실제로 복구해 보고 운영에 넣을 것.
set -eu

BACKUP_DIR=${BACKUP_DIR:-/backup}
KEEP_DAYS=${BACKUP_KEEP_DAYS:-14}
STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$BACKUP_DIR/storemanager-$STAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

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
