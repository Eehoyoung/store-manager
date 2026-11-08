#!/usr/bin/env bash
# restore-test.sh · rotate-secrets.sh · backup.sh 정적 검사 — 실행하지 않고 문장만 본다.
#   bash deploy/tests/ops_scripts_test.sh
# ★ git grep 을 쓰는 검사는 추적 파일만 본다 — 새 파일은 git add 뒤에 검사된다.
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

# postdeploy-check.sh — 읽기만 한다. 가짜 compose 로 실제 판정을 돌려 본다(값이 출력되지 않는지까지).
P=deploy/postdeploy-check.sh
t "bash -n $P" bash -n $P
t "postdeploy: 재시작·쓰기·env 출력 명령 없음" bash -c "! grep -vE '^\s*#' $P | grep -qE '(compose|DC)[^|]* (up|restart|down|stop|kill|rm|pull|build)\b|printenv|docker inspect|/etc/storemanager/env[^\"]*\" *\$|cat [^|]*env|>> *\"?/'"
for sf in $(grep -oE '^RISK_FLAGS="[^"]+"' $P | cut -d'"' -f2); do
  t "postdeploy: ${sf#*:} 는 compose ${sf%%:*} 에 기본 false" bash -c "sed -n '/^  ${sf%%:*}:/,/^  [a-z]/p' deploy/docker-compose.prod.yml | grep -qF '${sf#*:}: \${${sf#*:}:-false}'"
done
PD_TMP=$(mktemp -d)
git init -q "$PD_TMP/repo" && git -C "$PD_TMP/repo" -c user.email=t@t -c user.name=t commit -q --allow-empty -m x
PD_SHA=$(git -C "$PD_TMP/repo" rev-parse HEAD)
echo "배포 완료" > "$PD_TMP/log"
cat > "$PD_TMP/dc" <<'DC'
#!/usr/bin/env bash
case $1 in
  ps) printf '%s\n' "${FAKE_PS:-api-spring running healthy
worker running }" ;;
  exec) shift 2; svc=$1; shift
        if [ "$1" = test ]; then exit 0; fi
        shift 2; env APP_DEPLOY_SHA=$FAKE_DEPLOY_SHA ${FAKE_ENV:-} sh -c "$1" ;;
esac
DC
chmod +x "$PD_TMP/dc"
printf '%s
' '#!/usr/bin/env bash' "FAKE_DEPLOY_SHA=$PD_SHA REPO_DIR=$PD_TMP/repo DEPLOY_LOG=$PD_TMP/log DC=$PD_TMP/dc exec bash $P \"\$@\" > $PD_TMP/out 2>&1" > "$PD_TMP/pd"
PD="bash $PD_TMP/pd"
t "postdeploy: 정상이면 통과" $PD $PD_SHA
t "postdeploy: 정상이면 실행 커밋 PASS" grep -q '^PASS api-spring 실행 커밋 = 검사한 커밋$' $PD_TMP/out
t "postdeploy: 실행 커밋이 다르면 FAIL(종료 1)·값은 출력 안 함" bash -c "FAKE_ENV=APP_DEPLOY_SHA=abcdef1234 $PD $PD_SHA; [ \$? = 1 ] && grep -q '실행 커밋 ≠' $PD_TMP/out && ! grep -q abcdef $PD_TMP/out"
t "postdeploy: 실행 커밋이 비면 WARN(종료 3)·FAIL 0건" bash -c "FAKE_ENV=APP_DEPLOY_SHA= $PD $PD_SHA; [ \$? = 3 ] && grep -q '^WARN api-spring 실행 커밋 비어 있음' $PD_TMP/out && grep -q '^FAIL 0건 · WARN 1건$' $PD_TMP/out && ! grep -q '^FAIL api' $PD_TMP/out"
t "postdeploy: 16진수가 아닌 sha 는 종료 2" bash -c "$PD 'abc;id'; [ \$? = 2 ]"
t "postdeploy: 커밋이 다르면 FAIL" bash -c "! $PD 0000000"
t "postdeploy: unhealthy 면 FAIL" bash -c "! FAKE_PS='worker running unhealthy' $PD $PD_SHA"
t "postdeploy: 플래그 켜지면 FAIL·값은 출력 안 함" bash -c "! FAKE_ENV=BACKFILL_ENABLED=true $PD $PD_SHA && grep -q 'BACKFILL_ENABLED 꺼짐 아님' $PD_TMP/out && ! grep -q 'true' $PD_TMP/out"
t "postdeploy: Spring 이 켜짐으로 읽는 'TRUE'·'1' 도 FAIL" bash -c "! FAKE_ENV=ALIMTALK_ENABLED=TRUE $PD $PD_SHA && ! FAKE_ENV=TIERED_PRICING_ENABLED=1 $PD $PD_SHA"
t "postdeploy: 명시적 false 는 통과" env FAKE_ENV='BACKFILL_ENABLED=false ALIMTALK_ENABLED=false' $PD $PD_SHA
t "postdeploy: 배포 로그에 빌드 실패면 FAIL" bash -c "echo '빌드 실패' >> $PD_TMP/log; ! $PD $PD_SHA"
rm -rf "$PD_TMP"

# local-ci.sh — 여유 메모리가 기준 미만이면 빌드를 시작하지 않는다(가짜 meminfo 로 확인).
# ★ 빌드가 절대 돌지 않게, 첫 빌드 단계 앞(LOG=)에서 자른 사본을 돌린다. 끝까지 오면 REACHED 를 찍는다.
LC_TMP=$(mktemp -d); mkdir -p "$LC_TMP/deploy"
sed '/^LOG=\$(mktemp -d)/,$d' deploy/local-ci.sh > "$LC_TMP/deploy/local-ci.sh"; echo 'echo REACHED' >> "$LC_TMP/deploy/local-ci.sh"
LC="bash $LC_TMP/deploy/local-ci.sh"
printf 'MemTotal: 8000000 kB
MemFree: 1200000 kB
' > "$LC_TMP/low"
printf 'MemTotal: 8000000 kB
MemFree: 4000000 kB
MemAvailable: 1000000 kB
' > "$LC_TMP/avail_low"
printf 'MemTotal: 8000000 kB
MemAvailable: 3000000 kB
' > "$LC_TMP/ok"
t "local-ci: 사본이 빌드 단계 앞에서 잘림" bash -c "! grep -q '^step ' $LC_TMP/deploy/local-ci.sh"
t "local-ci: 여유 1.2GB 면 exit 3·진행 안 함" bash -c "MEMINFO=$LC_TMP/low $LC > $LC_TMP/out 2>&1; [ \$? -eq 3 ] && grep -q '시작하지 않는다' $LC_TMP/out && ! grep -q REACHED $LC_TMP/out"
t "local-ci: MemAvailable 이 있으면 그것으로 판정" bash -c "MEMINFO=$LC_TMP/avail_low $LC >/dev/null 2>&1; [ \$? -eq 3 ]"
t "local-ci: 여유 2.9GB 면 진행" bash -c "MEMINFO=$LC_TMP/ok $LC 2>&1 | grep -q REACHED"
t "local-ci: 기준은 LOCAL_CI_MIN_FREE_MB 로 조정" bash -c "LOCAL_CI_MIN_FREE_MB=5000 MEMINFO=$LC_TMP/ok $LC 2>&1 | grep -q '< 5000MB'"
rm -rf "$LC_TMP"

# predeploy-report.sh — git 만 읽는다. 가짜 저장소에서 주의 판정을 돌려 본다.
PR=deploy/predeploy-report.sh
t "bash -n $PR" bash -n $PR
t "predeploy: env 파일·컨테이너·printenv 를 보지 않음" bash -c "! grep -vE '^\s*#' $PR | grep -qE '/etc/storemanager|\.env|docker (compose|exec|ps|run)|printenv|psql'"
PR_TMP=$(mktemp -d); PRG="git -C $PR_TMP -c core.autocrlf=false -c user.name=t -c user.email=t@t"
mkdir -p "$PR_TMP/api-spring/src/main/resources/db/migration" "$PR_TMP/deploy"
git init -q "$PR_TMP"
echo 'RISK_FLAGS="api-spring:FOO_ENABLED"' > "$PR_TMP/deploy/postdeploy-check.sh"
printf '      FOO_ENABLED: ${FOO_ENABLED:-false}\n' > "$PR_TMP/deploy/docker-compose.prod.yml"
echo 'create table a(x int);' > "$PR_TMP/api-spring/src/main/resources/db/migration/V1__a.sql"
$PRG add -A && $PRG commit -qm base && $PRG tag base
echo 'create table b(x int);' > "$PR_TMP/api-spring/src/main/resources/db/migration/V2__b.sql"
printf '      NEW_K: ${NEW_K:-v}\n' >> "$PR_TMP/deploy/docker-compose.prod.yml"
$PRG add -A && $PRG commit -qm ok && $PRG tag ok
pr_on() { $PRG checkout -q ok && $PRG checkout -q -b "$1" && eval "$2" && $PRG commit -qam "$1" && $PRG tag "$1"; }
pr_on edit "echo '-- x' >> $PR_TMP/api-spring/src/main/resources/db/migration/V1__a.sql"
pr_on req "printf '      REQ: \${REQ:?}\n' >> $PR_TMP/deploy/docker-compose.prod.yml"
pr_on flag "sed -i 's/FOO_ENABLED:-false/FOO_ENABLED:-true/' $PR_TMP/deploy/docker-compose.prod.yml"
pr() { REPO_DIR=$PR_TMP bash $PR "$@" > "$PR_TMP/out" 2>&1; }
t "predeploy: 새 마이그레이션·새 키만이면 주의 0" bash -c "$(declare -f pr); PR=$PR PR_TMP=$PR_TMP; pr base ok && grep -q '새 파일 V2__b.sql' $PR_TMP/out && grep -q \"NEW_K (기본 'v')\" $PR_TMP/out && grep -q 'FOO_ENABLED 기본 false' $PR_TMP/out"
t "predeploy: 기존 마이그레이션 수정이면 주의" bash -c "$(declare -f pr); PR=$PR PR_TMP=$PR_TMP; ! pr base edit && grep -q '★ 기존 마이그레이션 M V1__a.sql' $PR_TMP/out"
t "predeploy: 기본값 없는 새 필수 env 면 주의" bash -c "$(declare -f pr); PR=$PR PR_TMP=$PR_TMP; ! pr base req && grep -q '★ REQ' $PR_TMP/out"
t "predeploy: 위험 플래그 기본값이 false 아니면 주의" bash -c "$(declare -f pr); PR=$PR PR_TMP=$PR_TMP; ! pr base flag && grep -q \"★ FOO_ENABLED 기본 'true'\" $PR_TMP/out"
t "predeploy: 운영 커밋이 조상 아니면 주의" bash -c "$(declare -f pr); PR=$PR PR_TMP=$PR_TMP; ! pr edit req && grep -q '조상이 아님' $PR_TMP/out"
t "predeploy: 없는 커밋이면 exit 2" bash -c "$(declare -f pr); PR=$PR PR_TMP=$PR_TMP; pr deadbeef ok; [ \$? -eq 2 ]"
rm -rf "$PR_TMP"

# 네이버 ↔ 배달 분리(NAVER 규칙 3·9, 경계 1) — naver-separation.md '지키는 테스트' 표의 "naver 패키지 import 스캔".
# 배달 패키지 = collect·draft·platform·review·internal. 공유는 제품 코어(store·user·ai·audit·common 등)로만.
NB=api-spring/src/main/java/com/storemanager/api
NB_DELIVERY="$NB/collect $NB/draft $NB/platform $NB/review $NB/internal"
t "네이버 분리: naver 패키지가 배달 패키지를 참조하지 않음" bash -c "! grep -rqE 'com\.storemanager\.api\.(collect|draft|platform|review|internal)\b' $NB/naver"
t "네이버 분리: 배달 패키지가 naver 패키지를 참조하지 않음" bash -c "! grep -rqE 'com\.storemanager\.api\.naver\b' $NB_DELIVERY"
t "네이버 분리: worker(DataAPI·Celery)에 네이버 없음" bash -c "! grep -rliE 'naver' worker --include=*.py --exclude-dir=tests | grep -q ."
# 규칙 3 — 리뷰 원문 컬럼을 막는다. 새 컬럼은 '리뷰 원문·작성자 정보가 아님'을 확인한 뒤에만 이 목록에 넣는다.
NE_ALLOWED="approved_at blocked category created_at draft_content drafted_at edit_distance edited guardrail_flags id inserted_at model posted_at prompt_version public_id rating review_hash risk_level risk_reasons status store_id updated_at viewed_at"
NE_COLS=$(cat api-spring/src/main/resources/db/migration/*.sql | sed 's/--.*//' | tr '\n' ' ' | tr ';' '\n' | grep -iE 'TABLE( IF NOT EXISTS)? naver_review_event( |\()' |
  sed -E 's/.*CREATE TABLE[^(]*\((.*)\)[^)]*$/\1/I' | grep -oiE '(^|,|ADD COLUMN( IF NOT EXISTS)?) *[a-z_]+ ' | grep -oE '[a-z_]+ $' | tr -d ' ' | grep -vxE 'unique|primary|constraint|check|foreign' | sort -u)
t "네이버 규칙 3: naver_review_event 컬럼이 허용 목록과 같음(본문 컬럼 없음)" test "$(echo $NE_COLS)" = "$NE_ALLOWED"

# compose 가 기본값 없이 받는 키(${K:?}·${K})는 env.example 에 있어야 한다 — preflight.sh 는 서버 env 를 env.example 과만
# 대조하므로, 여기서 빠지면 preflight 는 PASS 인데 배포에서 기동이 실패한다(빈 값으로 뜨거나 :? 로 멈춤).
CE_MISSING=$(grep -oE '\$\{[A-Z][A-Z0-9_]*(:?\?|\})' deploy/docker-compose.prod.yml | sed -E 's/^\$\{//; s/[:?}]+$//' | sort -u |
  comm -23 - <(grep -oE '^[A-Z][A-Z0-9_]*=' deploy/env.example | tr -d = | sort -u))
t "compose 기본값 없는 키가 env.example 에 전부 있음${CE_MISSING:+ — 빠짐: $(echo $CE_MISSING)}" test -z "$CE_MISSING"

# 서비스 간 호출은 2개만(CLAUDE.md 경계): Spring→Python POST /internal/ai/analyze-and-draft · Worker→Spring POST /internal/collect-result.
SJ=api-spring/src/main/java
t "서비스 경계: ai.base-url 은 AiClient 만 씀" test "$(grep -rl 'ai\.base-url' $SJ)" = "$SJ/com/storemanager/api/ai/AiClient.java"
t "서비스 경계: AiClient 가 부르는 경로는 analyze-and-draft 하나" test "$(grep -oE '\.uri\("[^"]*"' $SJ/com/storemanager/api/ai/AiClient.java | sort -u)" = '.uri("/internal/ai/analyze-and-draft"'
t "서비스 경계: worker 의 HTTP 호출은 dataapi.py·tasks.py 각 1곳" test "$(grep -rlE 'httpx\.(post|get|put|patch|delete|request|stream|Client|AsyncClient)\(|import requests|urllib\.request|aiohttp' worker --include=*.py --exclude-dir=tests | sort | tr '\n' ' ')$(grep -rhcE 'httpx\.(post|get|put|patch|delete|request|stream|Client|AsyncClient)\(' worker/dataapi.py worker/tasks.py | tr '\n' ' ')" = "worker/dataapi.py worker/tasks.py 1 1 "
t "서비스 경계: worker → Spring 경로는 collect-result 하나" test "$(grep -rhoE 'SPRING_INTERNAL_URL\}[^"]*' worker --include=*.py --exclude-dir=tests | sort -u)" = 'SPRING_INTERNAL_URL}/internal/collect-result'
t "서비스 경계: ai-python 은 다른 서비스를 부르지 않음" bash -c "! grep -rlE 'import httpx|from httpx|import requests|urllib\.request|aiohttp|SPRING_INTERNAL_URL|api-spring:' ai-python --include=*.py --exclude-dir=tests | grep -q ."

# 배포 후 확인(postdeploy-check.sh)의 RISK_FLAGS 는 compose 에서 기본 false 인 _ENABLED 플래그와 맞아야 한다 —
# 새 대표 결정 플래그가 생겼는데 배포 후 확인에서 빠지거나, 이름·서비스가 틀려 '확인 불가'로 새는 일을 막는다.
# 예외(이유를 옆에 쓴다): READINESS_SUMMARY 는 관리자 주소에만 메일 — 켜도 대표 결정 아님. HQ_REVIEW_ACCESS 는 운영 true, 대표 확인 대기(CEO_TODO 18).
RF_EXEMPT="api-spring:HQ_REVIEW_ACCESS_ENABLED api-spring:READINESS_SUMMARY_ENABLED"
RF_COMPOSE=$(awk '/^  [a-z][a-z0-9-]*:$/{s=$1; sub(/:$/,"",s)} $1 ~ /^[A-Z][A-Z0-9_]*_ENABLED:$/ && $2 ~ /:-false\}$/ {k=$1; sub(/:$/,"",k); print s":"k}' deploy/docker-compose.prod.yml | sort -u)
RF_LISTED=$(echo $(sed -n 's/^RISK_FLAGS="\(.*\)"$/\1/p' deploy/postdeploy-check.sh) $RF_EXEMPT | tr ' ' '\n' | sort -u)
RF_MISSING=$(comm -23 <(echo "$RF_COMPOSE") <(echo "$RF_LISTED"))
RF_EXTRA=$(comm -13 <(echo "$RF_COMPOSE") <(echo "$RF_LISTED"))
t "RISK_FLAGS: compose 기본 false 플래그가 전부 RISK_FLAGS 나 예외에 있음${RF_MISSING:+ — 빠짐: $(echo $RF_MISSING)}" test -n "$RF_COMPOSE" -a -z "$RF_MISSING"
t "RISK_FLAGS: RISK_FLAGS·예외가 전부 compose 의 그 서비스에 기본 false 로 있음${RF_EXTRA:+ — 없음: $(echo $RF_EXTRA)}" test -z "$RF_EXTRA"
# 절대규칙 5·6 — 로그·print 에 자격증명·작성자 정보 값이 들어가지 않는다. 호출을 괄호가 닫힐 때까지 모은 뒤
# 값이 아닌 부분(문자열 상수·f-string 의 글자·주석)을 지우고 민감어가 남으면 실패. 예외는 패턴 하나: mask* 함수로 감싼 값.
# ponytail: 정규식 근사(문자열 안 괄호·로거를 변수에 담아 부르는 경우는 못 봄) — 새는 길이 생기면 그 모양을 여기 더한다.
LG_AWK='!c && /(^|[^A-Za-z_.])(log|logger)\.(trace|debug|info|warn|warning|error|exception|critical)\(|(^|[^A-Za-z_.])print\(|System\.(out|err)\.print/ { c = 1; b = ""; at = FILENAME ":" FNR; d = 0 }
c { b = b " " $0; d += gsub(/\(/, "(") - gsub(/\)/, ")"); if (d <= 0) { print at "\t" b; c = 0 } }'
LG_PL='while (<STDIN>) { my ($at, $s) = split /\t/, $_, 2;
  $s =~ s#\bf(["'"'"'])(.*?)\1#join " ", $2 =~ m/\{([^}]*)\}/g#ge; $s =~ s/(["'"'"'])(?:\.|(?!\1).)*\1/""/g;
  $s =~ s{//.*}{} if $at =~ /\.java:/; $s =~ s/\s#.*// if $at =~ /\.py:/; $s =~ s/\b_?mask\w*\([^)]*\)/MASKED/gi;
  print "$at " if $s =~ /LOGINPWD|passw|pwd|nick_?name|author_?(name|raw)|writer_?name|cookie|secret|token|credential|phone|email/i }'
lg_scan() { { find "$@" -name '*.py' -not -path '*/tests/*'; find "$@" -name '*.java' -not -path '*/test/*'; } | sort | xargs awk "$LG_AWK" | perl -e "$LG_PL"; }
LG_HITS=$(lg_scan worker ai-python api-spring/src/main/java)
t "절대규칙 5·6: 로그·print 에 자격증명·작성자 정보 값 없음${LG_HITS:+ — $LG_HITS}" test -z "$LG_HITS"
LG_FX=$(mktemp -d); printf '%s\n' 'log.info(f"x {req.LOGINPWD}")' 'print(f"t {_mask(TOKEN)}")' 'print("token 비어 있음")' > "$LG_FX/a.py"
printf '%s\n' 'log.warn("nick {}",' '    review.authorNickname());' 'log.info("x {}", storeId); // password' > "$LG_FX/B.java"
t "절대규칙 5·6: 로깅 검사가 f-string·여러 줄 호출을 잡고 상수·주석·mask 는 넘김" test "$(lg_scan "$LG_FX" | sed "s#$LG_FX/##g")" = "B.java:1 a.py:1 "
rm -rf "$LG_FX"

# 절대규칙 6 — 배민 원본 닉네임(authorRaw)은 worker 가 normalize 한 곳에서 담고, Spring 은 Pseudonymizer.mask 로만 꺼낸다.
# 저장되는 작성자 컬럼은 가명처리 결과(author_masked·author_hash)뿐이다.
AR_SJ=api-spring/src/main/java
t "절대규칙 6: Spring 에서 authorRaw 를 꺼내는 곳은 pseudonymizer.mask 하나" test "$(grep -rhE '\.authorRaw\(\)|getAuthorRaw' $AR_SJ | sed 's/^ *//')" = 'Pseudonymizer.Result masked = pseudonymizer.mask(rb.authorRaw());'
t "절대규칙 6: worker 에서 authorRaw 를 담는 곳은 normalize.py 하나" test "$(grep -rlE '["'"'"']authorRaw["'"'"']' worker --include=*.py --exclude-dir=tests)" = worker/normalize.py
t "절대규칙 6: 마이그레이션의 작성자 컬럼은 author_masked·author_hash 뿐" test "$(cat api-spring/src/main/resources/db/migration/*.sql | sed 's/--.*//' | grep -oiE '\b[a-z_]*(author|nick|writer)[a-z_]*\b' | tr A-Z a-z | sort -u | tr '\n' ' ')" = "author_hash author_masked "
# #76 로깅 검사는 로거 이름 log 만 본다 — 다른 이름이 생기면 검사 밖으로 새므로 여기서 막는다.
t "절대규칙 5·6: 로거 변수 이름은 log 하나(Java·Python)" test "$(grep -rhoE '\bLogger +[A-Za-z_]+ *=' $AR_SJ | sort -u)$(grep -rhoE '^[[:space:]]*[A-Za-z_]+ *= *(logging\.getLogger|get_task_logger|structlog\.get_logger)' worker ai-python --include=*.py --exclude-dir=tests | sed 's/[[:space:]]//g' | sort -u)" = "Logger log =log=logging.getLogger"

# 절대규칙 2 — DataAPI 성공 판정은 dataapi.parse_envelope 한 곳에서만, data.RESULT == "SUCCESS" 이면서 ECODE 가 빌 때만.
# 다른 곳에서 RESULT·ECODE 를 직접 읽거나 응답 JSON 을 parse_envelope 없이 쓰면 우회 판정이 생긴다.
SJ_AT=$(find worker -name '*.py' -not -path '*/tests/*' | sort | xargs awk 'FNR==1{fn="<module>"} /^[[:space:]]*def [A-Za-z_]+/{fn=$0; sub(/^[[:space:]]*def /,"",fn); sub(/\(.*/,"",fn)} /(get\(|\[)["'"'"'](RESULT|ECODE)["'"'"']/{print FILENAME ":" fn}' | sort -u | tr '\n' ' ')
t "절대규칙 2: RESULT·ECODE 를 읽는 곳은 dataapi.parse_envelope 뿐${SJ_AT:+ — $SJ_AT}" test "$SJ_AT" = "worker/dataapi.py:parse_envelope "
t "절대규칙 2: worker 의 응답 JSON 은 parse_envelope 를 거쳐서만 씀" test "$(grep -rhE '\.json\(\)' worker --include=*.py --exclude-dir=tests | sed 's/^[[:space:]]*//')" = 'return parse_envelope(resp.json())'
t "절대규칙 2: 판정식이 RESULT 와 ECODE 를 함께 봄" grep -qF 'if data.get("RESULT") != "SUCCESS" or _s(data.get("ECODE")) is not None:' worker/dataapi.py

# 절대규칙 7 — 카드번호·CVC·유효기간은 PG 결제창 밖으로 나오지 않는다. 코드·스키마에 그 필드·입력란 이름이 없어야 한다.
# 약관·안내 문구(한글 "카드번호·CVC")는 대상이 아니다 — 식별자 표기만 본다. SQL 은 -- 주석을 지우고 본다.
CD_RE='\b(cvc|cvv|csc|cardNumber|card_number|cardNo|card_no|cardNum|card_num|CARD_NUMBER|CARD_NO|cardExpiry|card_expiry|expiryMonth|expiryYear|expiry_month|expiry_year|expMonth|expYear|exp_month|exp_year)\b|cc-(number|csc|exp)'
CD_HITS=$( { git grep -nE "$CD_RE" -- 'api-spring/src/main/*.java' 'web/src/*.ts' 'web/src/*.tsx' 'extension/src/*.ts' 'worker/*.py' 'ai-python/*.py' ':!*test*' ':!*Test*';
  for f in api-spring/src/main/resources/db/migration/*.sql; do sed 's/--.*//' "$f" | grep -nE "$CD_RE" | sed "s#^#$f:#"; done; } | cut -d: -f1,2 | tr '\n' ' ')
t "절대규칙 7: 코드·스키마에 카드 원문 필드·입력란 없음${CD_HITS:+ — $CD_HITS}" test -z "$CD_HITS"
# 빌링키는 BillingService 의 verifyBillingKey(소유 고객·허용 채널·ISSUED 확인)를 거친 뒤에만 Subscription 에 들어간다.
CD_BS=api-spring/src/main/java/com/storemanager/api/billing/BillingService.java
CD_SUB=api-spring/src/main/java/com/storemanager/api/billing/Subscription.java
t "절대규칙 7: Subscription 에 빌링키를 담는 메서드는 beginTrial·swapBillingKey·chargeSucceeded 셋" test "$(awk '/^    public .*\(/{m=$0; sub(/^    public [A-Za-z<>]+ /,"",m); sub(/\(.*/,"",m)} /this\.billingKey = [^n]/{print m}' $CD_SUB | tr '\n' ' ')" = "beginTrial swapBillingKey chargeSucceeded "
t "절대규칙 7: 빌링키를 담는 메서드를 부르는 곳은 BillingService 뿐" test "$(git grep -lE '\.(beginTrial|swapBillingKey|chargeSucceeded)\(' -- api-spring/src/main)" = $CD_BS
t "절대규칙 7: 바깥에서 온 빌링키는 verifyBillingKey 로 시작하는 한 메서드 안에서만 씀" test "$(git grep -lE '\breq\.billingKey\(\)' -- api-spring/src/main)$(awk '/req\.billingKey\(\)/ && !s {s=FNR; ok=/String channelKey = verifyBillingKey\(store, req\.billingKey\(\)\);/} s && !e && FNR>s && /^    \}$/ {e=FNR} /req\.billingKey\(\)/ && e {bad=1} END{print (ok && !bad) ? " ok" : " bad"}' $CD_BS)" = "$CD_BS ok"
t "절대규칙 7: verifyBillingKey 가 ISSUED·고객 id·채널을 모두 봄" test "$(grep -cE '"ISSUED"\.equals\(info\.path\("status"\)|customerId\(store\)\.equals\(info\.path\("customer"\)\.path\("id"\)|channelKey\.equals\(portOneClient\.channelKey\(\)\)' $CD_BS)" = 3

# 절대규칙 1 — 리뷰 본문을 만드는 경로는 어떤 형태로도 없다. 답글만 만든다. 허용 목록으로 고정한다:
# AI 서비스 경로는 /health · analyze-and-draft 둘, 프롬프트 빌더는 분류·답글 둘, LLM 호출은 그 둘을 쓰는 main.py 한 곳.
# git pathspec 의 * 는 하위 폴더까지 맞으므로 tests 를 뺀다. 독스트링(""" 줄) 속 호출 표기는 세지 않는다.
RB_PY="ai-python/*.py :!ai-python/tests"
t "절대규칙 1: AI 서비스 HTTP 경로는 /health 와 analyze-and-draft 뿐" test "$(git grep -hoE '@(app|router)\.[a-z_]+\("[^"]*"|add_api_route\(|include_router\(|APIRouter\(|\.mount\(' -- $RB_PY | tr '\n' ' ')" = '@app.get("/health" @app.post("/internal/ai/analyze-and-draft" '
t "절대규칙 1: 프롬프트 함수는 prompt_version_for·build_classify_messages·build_generate_messages 셋" test "$(git grep -hoE '^def [a-z_]*(messages|prompt)[a-z_]*\(' -- $RB_PY | tr '\n' ' ')" = "def prompt_version_for( def build_classify_messages( def build_generate_messages( "
t "절대규칙 1: LLM 호출은 llm.py 의 complete 와 main.py 의 분류·답글 각 1회, SDK import 는 llm.py 뿐" test "$(git grep -nE '\.messages\.(create|parse|stream)\(|\bprovider\.complete\(|\.chat\.completions|\.responses\.create\(' -- $RB_PY | grep -v '"""' | cut -d: -f1 | uniq -c | awk '{printf "%s:%s ", $2, $1}')$(git grep -lE '^\s*(import|from) (anthropic|openai)\b' -- ai-python worker api-spring ':!ai-python/tests' ':!worker/tests')" = "ai-python/llm.py:1 ai-python/main.py:2 ai-python/llm.py"
t "절대규칙 1: 답글 생성 호출에는 build_generate_messages 결과만 들어감" test "$(grep -B12 'provider\.complete(system, user' ai-python/main.py | grep -cE 'system, user = prompts\.build_generate_messages\(')" = 1
RB_HITS=$(git grep -nE '\b(generate|write|create|compose|fabricate|fake|synthesi[sz]e)_?[Rr]eviews?(_?(body|text|content|Body|Text|Content))?\b|\b(generate|write|compose|fabricate|fake|synthesi[sz]e)[A-Za-z]*Review(Body|Text|Content)?\b|\b[Rr]eview_?([Ww]riter|[Gg]enerator|[Ff]abricat)|(리뷰|후기)(를|글을|본문을) ?(대신 )?(작성|써|만들|생성)' -- 'ai-python/*.py' 'api-spring/src/main/java' 'worker/*.py' 'web/src' 'extension/src' ':!*test*' ':!*Test*' | cut -d: -f1,2 | tr '\n' ' ')
t "절대규칙 1: 코드에 리뷰 생성 이름·지시문 없음${RB_HITS:+ — $RB_HITS}" test -z "$RB_HITS"

echo "통과 $ok / 실패 $bad"
[ "$bad" -eq 0 ]
