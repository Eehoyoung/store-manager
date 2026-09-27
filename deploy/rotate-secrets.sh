#!/bin/sh
# 비밀값 교체 — JWT_SECRET · INTERNAL_TOKEN · CREDENTIAL_MASTER_KEY (2026-09-28, 90일 주기).
#
# ★ AUTHOR_HASH_SALT 는 교체하지 않는다. 바꾸면 기존 리뷰 작성자와 연결이 끊긴다(README §8).
#
#   JWT_SECRET            30분짜리 액세스 토큰만 무효가 된다. refresh 는 Redis 값이라 웹이 조용히 재발급한다.
#   INTERNAL_TOKEN        api-spring·ai-python·worker 를 한꺼번에 재기동하므로 어긋나는 구간이 없다.
#   CREDENTIAL_MASTER_KEY api-spring·worker 를 멈추고 전 행의 DEK 를 새 키로 다시 감싼 뒤(worker/rewrap.py)
#                         새 env 로 올린다. 1~2분 멈춘다.
#
# ★ 옛 env 는 key-archive 에 남긴다. 교체 전에 뜬 백업(14일)·스냅샷(7일)은 옛 마스터키로만 풀린다.
#   KEEP=4 × 90일 = 약 1년치. 이 보관을 줄이지 말 것.
#
# cron (/etc/cron.d/storemanager-rotate) — 1·4·7·10월 2일 04:00 KST. 03:00 백업 뒤, 10:00 수집 전이다.
#   0 19 1 1,4,7,10 * root /opt/storemanager/deploy/rotate-secrets.sh >> /var/log/storemanager-rotate.log 2>&1
set -eu
cd "$(dirname "$0")"

ENV_FILE=${ENV_FILE:-/etc/storemanager/env}
ARCHIVE=${ARCHIVE:-/etc/storemanager/key-archive}
KEEP=4
DC="docker compose -f docker-compose.prod.yml --env-file $ENV_FILE"
umask 077

STAMP=$(date -u +%Y%m%d%H%M)
NEXT="$ENV_FILE.next"
log() { echo "[rotate $(date -u +%FT%TZ)] $*"; }

# KEY=VALUE 를 바꾸고, 없으면 끝에 붙인다. 값은 base64 라 awk -v 이스케이프에 걸리지 않는다.
set_kv() {
	tmp=$(mktemp "$NEXT.XXXX")
	awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; d = 1; next } { print } END { if (!d) print k "=" v }' \
		"$NEXT" > "$tmp"
	mv "$tmp" "$NEXT"
}

mkdir -p "$ARCHIVE"
cp -p "$ENV_FILE" "$ARCHIVE/env.$STAMP"
ls -1t "$ARCHIVE"/env.* | tail -n +$((KEEP + 1)) | xargs -r rm -f
log "옛 env 보관: $ARCHIVE/env.$STAMP"

OLD_MASTER_KEY=$(sed -n 's/^CREDENTIAL_MASTER_KEY=//p' "$ENV_FILE" | tail -1)
NEW_MASTER_KEY=$(openssl rand -base64 32)
NEW_KEY_ID="prod-$STAMP"
export OLD_MASTER_KEY NEW_MASTER_KEY NEW_KEY_ID   # docker compose run -e 로 넘긴다 — 명령줄(ps)에 남지 않는다

cp -p "$ENV_FILE" "$NEXT"
set_kv JWT_SECRET "$(openssl rand -base64 64 | tr -d '\n')"
set_kv INTERNAL_TOKEN "$(openssl rand -base64 32)"
set_kv CREDENTIAL_MASTER_KEY "$NEW_MASTER_KEY"
set_kv CREDENTIAL_KEY_ID "$NEW_KEY_ID"

# 교체 직전 상태를 덤프로 남긴다(옛 키로 풀리는 마지막 백업).
$DC exec -T backup /backup.sh

log "api-spring·worker 정지 → 재암호화"
$DC stop api-spring worker
if ! $DC run --rm --no-deps -e OLD_MASTER_KEY -e NEW_MASTER_KEY -e NEW_KEY_ID worker python rewrap.py; then
	# rewrap 은 한 트랜잭션이라 DB 는 그대로다. 옛 env 로 되살린다.
	rm -f "$NEXT"
	$DC start api-spring worker
	log "실패: 재암호화 롤백됨. 옛 비밀값으로 재기동했다" >&2
	exit 1
fi

mv "$NEXT" "$ENV_FILE"
$DC up -d
log "새 env 적용(key_id=$NEW_KEY_ID). api-spring 헬스 대기"

i=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$($DC ps -q api-spring)")" = healthy ]; do
	i=$((i + 1))
	if [ "$i" -ge 30 ]; then
		log "경고: api-spring 이 5분 안에 healthy 가 되지 않았다. 옛 env: $ARCHIVE/env.$STAMP" >&2
		log "되돌리려면 README §8.3 — 이미 DB 는 새 키로 감싸져 있으니 env 만 되돌리면 안 된다" >&2
		exit 1
	fi
	sleep 10
done
log "완료"
