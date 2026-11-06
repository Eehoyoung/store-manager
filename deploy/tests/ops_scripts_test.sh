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
t "rotate: auto-deploy 와 같은 락" grep -q '^exec 9>/run/storemanager-deploy.lock' deploy/rotate-secrets.sh
t "rotate: 락을 trap 보다 먼저" bash -c "awk '/^flock -n 9/{a=NR} /^trap /{b=NR} END{exit !(a && b && a<b)}' deploy/rotate-secrets.sh"
t "rotate: rewrap 전에 새 env 보관" bash -c "awk '/^NEW_COPY=/{a=NR} /rewrap.py/{b=NR} END{exit !(a && b && a<b)}' deploy/rotate-secrets.sh"
t "rotate: awk -v 로 비밀값을 넘기지 않음" bash -c "! grep -vE '^\s*#' deploy/rotate-secrets.sh | grep -q 'awk -v'"
t "restore-test: tmpfs 미사용·메모리 상한" bash -c "! grep -vE '^\s*#' $R | grep -q -- '--tmpfs' && grep -q -- '--memory' $R"
t "backup: pipefail" grep -q '^set -o pipefail' deploy/backup.sh
t "restore-test: 기본 이미지에 pgvector" grep -q "PG_IMAGE:-pgvector/pgvector:pg16" $R
t "backup: 실행 권한 없이도 sh 로 부름" bash -c "! grep -rnE '(^|[^h] )/backup.sh' deploy/docker-compose.prod.yml deploy/rotate-secrets.sh deploy/cron.example | grep -v ':/backup.sh:ro'"
RL=$(grep -E '^# 0 19 1 1,4,7,10 .*rotate-secrets.sh' deploy/cron.example)
t "cron: rotate 줄이 있음" test -n "$RL"
t "cron: rotate 줄의 % 는 모두 \\% 로 이스케이프" bash -c '! printf "%s" "$1" | sed "s/\\\\%//g" | grep -q "%"' _ "$RL"
t "cron: rotate 줄이 종료코드를 rotate.status 에 기록" bash -c 'printf "%s" "$1" | grep -qF "echo \"\$? \$(date -u +\%FT\%TZ)\" > <STATUS_DIR>/rotate.status"' _ "$RL"
t "README: rotate cron 예시도 rotate.status 기록" bash -c "grep -F 'storemanager-rotate.log 2>&1' deploy/README.md | grep -qF 'echo \"\$? \$(date -u +\%FT\%TZ)\" > /opt/storemanager/deploy/ops-status/rotate.status'"
# api-spring 은 env_file 이 없어 compose 에 적힌 키만 받는다 — application.yml 의 env 를 빠뜨리면 서버 env 에 넣어도 안 켜진다.
API_ENV=$(sed -n '/^  api-spring:/,/^  [a-z]/p' deploy/docker-compose.prod.yml | grep -oE '^ +[A-Z][A-Z0-9_]*:' | tr -d ' :' | sort -u)
YML_ENV=$(grep -oE '\$\{[A-Z][A-Z0-9_]*' api-spring/src/main/resources/application.yml | cut -c3- | sort -u)
t "compose: application.yml 의 env 를 api-spring 에 전부 넘김" test -z "$(comm -23 <(echo "$YML_ENV") <(echo "$API_ENV"))"
t "compose: 상태 디렉터리 :ro 마운트와 env" bash -c "grep -q 'ops-status:ro' deploy/docker-compose.prod.yml && grep -q 'READINESS_ROTATE_STATUS_FILE: /ops-status/rotate.status' deploy/docker-compose.prod.yml"
# worker·ai-python 도 env_file 이 없다 — 코드가 읽는 env 는 compose 에 있어야 한다(#61 DATAAPI_ENC_OUTPUT 재발 방지).
# 예외: NEW_KEY_ID(rewrap 은 run -e) · DATAAPI_ENC_SAMPLE_*(smoke 전용) · DATAAPI_BUDGET_KEY(키 이름, 기본값으로 충분) · GOLDENSET_MIN_STORES(eval 전용). 시험 코드(tests/)는 제외.
ENV_EXEMPT='NEW_KEY_ID|DATAAPI_ENC_SAMPLE_ENC|DATAAPI_ENC_SAMPLE_PLAIN|DATAAPI_BUDGET_KEY|GOLDENSET_MIN_STORES'
svc_env() { sed -n "/^  $1:/,/^  [a-z]/p" deploy/docker-compose.prod.yml | grep -oE '^ +[A-Z][A-Z0-9_]*:' | tr -d ' :' | sort -u; }
py_env() { grep -rhoE "(environ\.get\(|getenv\(|environ\[|_ENV *= *)[\"'][A-Z][A-Z0-9_]*" --include=*.py --exclude-dir=tests "$1" | grep -oE '[A-Z][A-Z0-9_]*$' | grep -vxE "$ENV_EXEMPT" | sort -u; }
t "compose: worker 코드가 읽는 env 를 worker 에 전부 넘김" test -z "$(comm -23 <(py_env worker) <(svc_env worker))"
t "compose: ai-python 코드가 읽는 env 를 ai-python 에 전부 넘김" test -z "$(comm -23 <(py_env ai-python) <(svc_env ai-python))"

# 구독 서비스 가능 조건은 SQL 3곳에 손으로 복제돼 있다 — 하나만 바뀌면 미납 매장에 LLM·DataAPI 비용이 새거나 결제 매장이 멈춘다.
# 기준은 Subscription.isServiceableAt(status 가 SUSPENDED·CANCELED 아님, service_until > now). 바꿀 때는 4곳을 함께 고칠 것.
svc_sql() { tr -s ' \n\r\t' ' ' < "$1" | grep -oE "subscription sub WHERE sub\.store_id = [a-z]+\.(store_id|id) AND sub\.status NOT IN \('SUSPENDED', 'CANCELED'\) AND sub\.service_until > (now\(\)|:now)\)" | wc -l; }
for f in api-spring/src/main/java/com/storemanager/api/review/UnifiedReviewRepository.java api-spring/src/main/java/com/storemanager/api/notify/DailyBriefingService.java worker/credentials.py; do
  t "구독 조건 동기화: $(basename "$f") 에 같은 조건 1벌" test "$(svc_sql "$f")" -eq 1
done
t "구독 조건 동기화: isServiceableAt 이 같은 조건" bash -c "grep -A6 'boolean isServiceableAt' api-spring/src/main/java/com/storemanager/api/billing/Subscription.java | tr -d ' \n\r' | grep -qF '!\"SUSPENDED\".equals(this.status)&&!\"CANCELED\".equals(this.status)&&this.serviceUntil!=null&&now.isBefore(this.serviceUntil)'"

# 게시 방어선 3중(절대규칙 3)의 위험 차단선은 세 곳에 따로 적혀 있다 — 한 곳만 올라가면 그 방어선이 risk 3 을 통과시킨다.
t "방어선 동기화: DraftService 차단선 3" grep -qE 'static final (short|int) RISK_AUTO_BLOCK_LEVEL = 3;' api-spring/src/main/java/com/storemanager/api/draft/DraftService.java
t "방어선 동기화: DraftService 가 차단선 미만만 자동 승인" grep -qF 'analysis.riskLevel() < RISK_AUTO_BLOCK_LEVEL' api-spring/src/main/java/com/storemanager/api/draft/DraftService.java
t "방어선 동기화: PublishScheduler 차단선 3" grep -qE 'static final short RISK_BLOCK_LEVEL = 3;' api-spring/src/main/java/com/storemanager/api/draft/PublishScheduler.java
t "방어선 동기화: PublishScheduler 가 차단선 이상·미승인을 막음" grep -qF 'riskLevel >= RISK_BLOCK_LEVEL && !humanApproved' api-spring/src/main/java/com/storemanager/api/draft/PublishScheduler.java
t "방어선 동기화: publish.is_risk_blocked 차단선 3" grep -qxE 'RISK_BLOCK_THRESHOLD = 3' worker/publish.py
t "방어선 동기화: is_risk_blocked 가 차단선 미만만 통과" grep -qF 'if risk < RISK_BLOCK_THRESHOLD:' worker/publish.py

# 위험 사유 정본(ai-python prompts.RISK_REASON_VALUES)에 라벨이 없으면 사장님 화면에 THREAT 같은 코드가 그대로 뜬다 — 웹·확장 둘 다 전부 있어야 한다.
RISK_REASONS=$(sed -n '/^RISK_REASON_VALUES = (/,/^)/p' ai-python/prompts.py | grep -v '^ *#' | grep -oE '"[A-Z_]+"' | tr -d '"' | sort -u)
t "위험 사유 정본 11종 읽힘" test "$(echo "$RISK_REASONS" | wc -l)" -eq 11
for f in web/src/lib/labels.ts extension/src/sidepanel/riskBanner.ts; do
  t "위험 사유 라벨: $(basename "$f") 에 정본 전부" test -z "$(comm -23 <(echo "$RISK_REASONS") <(grep -oE '^  [A-Z_]+:' "$f" | tr -d ' :' | sort -u))"
done

# 가드레일 플래그(G코드) 정본은 ai-python 이 내는 문자열이다 — 라벨 키가 다르면 사장님 화면에 G6_REVIEW_QUOTE 같은 코드가 그대로 뜬다.
G_FLAGS=$(grep -rhoE '"G[0-9]+_[A-Z_]+"' --include=*.py --exclude-dir=tests ai-python | tr -d '"' | sort -u)
t "가드레일 플래그 정본 읽힘(G1~G9)" test "$(echo "$G_FLAGS" | grep -oE '^G[0-9]+' | sort -u | wc -l)" -eq 9
t "가드레일 플래그 라벨: labels.ts 에 정본 전부" test -z "$(comm -23 <(echo "$G_FLAGS") <(grep -oE '^  G[0-9]+_[A-Z_]+:' web/src/lib/labels.ts | tr -d ' :' | sort -u))"
# Spring 이 guardrailFlags 에 직접 넣는 차단 사유도 같은 라벨 맵을 쓴다.
SPRING_FLAGS=$(grep -rhoE '(blockForScheduling\(|appendFlag\([^,]+, |new String\[\] \{)"[A-Z][A-Z0-9_]+"' api-spring/src/main/java/com/storemanager/api/draft | grep -oE '"[A-Z][A-Z0-9_]+"' | tr -d '"' | sort -u)
t "차단 사유 정본 읽힘(Spring draft)" test "$(echo "$SPRING_FLAGS" | wc -l)" -ge 10
t "차단 사유 라벨: labels.ts 에 Spring 차단 사유 전부" test -z "$(comm -23 <(echo "$SPRING_FLAGS") <(grep -oE '^  [A-Z][A-Z0-9_]+:' web/src/lib/labels.ts | tr -d ' :' | sort -u))"

echo "통과 $ok / 실패 $bad"
[ "$bad" -eq 0 ]
