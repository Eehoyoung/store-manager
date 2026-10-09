# 운영 배포

개발용(`docker-compose.yml`)과 운영용(`deploy/docker-compose.prod.yml`)은 다르다.
운영에서 개발용을 쓰면 **DB·Redis 포트가 인터넷에 열린다.** 그 DB 에는 배달앱 자격증명
암호문과 리뷰 원문이 들어 있다.

## 1. 준비

### 1.1 서버
운영 기준은 **AWS Lightsail 8GB / 2vCPU / 160GB (월 $44)** 단일 인스턴스다. 근거는 §5.

- Docker + Docker Compose v2
- 인바운드는 **443(Cloudflare IP 대역만) + 22(운영자 IP만)** 둘뿐이다. 80 도 열지 않는다(§1.2).
  **그 외 포트는 닫는다** (5432·6379·8080 절대 열지 말 것)
- 관리 포트(actuator)는 컨테이너 8081 로 분리돼 **호스트 `127.0.0.1:18080` 에만** 게시된다(운영 콘솔 collector 전용, 2026-09-26).
  방화벽에서 18080 을 열지 말 것. 8080 은 호스트에 게시하지 않는다.
- 디스크: DB + 백업 14일치. 매장 100개 기준 최소 50GB 권장
- **스왑 2GB 를 먼저 만든다.** 이미지 빌드가 서버에서 일어나고(`api-spring/Dockerfile` 의
  `./gradlew bootJar`) Gradle 이 1.5~2GB 를 순간적으로 먹는다. Postgres·JVM·AI 가 이미 도는
  중에 빌드를 걸면 스왑이 없는 4GB 에서는 OOM 으로 **돌고 있던 컨테이너가 먼저 죽는다.**

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

- **정적 IP 를 붙인다**(Lightsail 무료, 붙어 있는 동안). DataAPI 토큰이 IP 화이트리스트에
  묶이면 인스턴스를 재생성할 때마다 업체에 재등록을 요청해야 한다. 정적 IP 를 떼어
  새 인스턴스에 다시 붙이면 IP 가 그대로다 — 복구 시간의 대부분이 이 한 가지에서 나온다.
- **자동 스냅샷을 켠다**(일 1회, 7일 보관). 아래 §6 이 이것에 의존한다.

### 1.2 도메인 — Cloudflare (2026-09-28 확정)

운영 도메인은 `review.sodamlabs.kr`, DNS 는 Cloudflare 다. **A 레코드를 정적 IP 로, 프록시(주황 구름) ON.**

```
사용자 ──TLS──▶ Cloudflare ──TLS(Origin CA)──▶ Caddy:443 ──▶ api-spring / web
```

**① 인증서 — Cloudflare Origin CA.** Let's Encrypt 는 쓰지 않는다(CF 프록시 뒤에서 챌린지가 깨진다).
CF 대시보드 → SSL/TLS → Origin Server → Create Certificate(`review.sodamlabs.kr`, 15년) 로 받아 서버에 둔다.

```bash
sudo install -d -m 700 /etc/storemanager/certs
sudo tee /etc/storemanager/certs/origin.pem >/dev/null   # 인증서 붙여넣기 후 Ctrl-D
sudo tee /etc/storemanager/certs/origin.key >/dev/null   # 개인키 붙여넣기 후 Ctrl-D
sudo chmod 600 /etc/storemanager/certs/origin.key
```

> ★ 개인키는 발급 화면에서 **한 번만** 보인다. 비밀값과 같은 곳에 한 부 더 보관할 것.
> ★ 이 인증서는 브라우저가 신뢰하지 않는다. **프록시를 끄면(회색 구름) 사이트가 깨진다.**

**② Cloudflare 설정**

| 항목 | 값 | 이유 |
|---|---|---|
| SSL/TLS 모드 | **Full (strict)** | Flexible 이면 CF↔서버 구간이 평문이다 |
| Always Use HTTPS | ON | 80 을 열지 않으므로 리다이렉트는 CF 가 한다 |
| HSTS (CF) | OFF | Caddy 가 이미 보낸다. 두 곳에서 관리하지 않는다 |
| Rocket Loader | OFF | SPA 스크립트를 재배치해 깨뜨린다 |
| Bot Fight Mode | OFF | 확장·워커 없는 API 호출에도 챌린지를 걸 수 있다. 필요하면 WAF 규칙으로 좁혀 건다 |
| Cache Rule | `/api/*` → Bypass | 응답에 개인 데이터가 있다 |

**③ 오리진 잠금 — Lightsail 방화벽.** 443 의 소스를 Cloudflare 대역(https://www.cloudflare.com/ips/)으로만
제한한다. `deploy/Caddyfile` 의 `trusted_proxies` 와 **같은 목록**이어야 한다 — CF 가 대역을 바꾸면 둘 다 고친다.

> ★ 잠그지 않으면 서버 IP 로 직접 들어와 CF 를 우회하고, 실제 IP 판정도 흔들린다.
> ★ 실제 접속자 IP 는 `CF-Connecting-IP` 로만 판정한다(Caddyfile). 동의 증적(`AuthController.clientIp`)이 이 값을 쓴다.

도메인·웹 CORS·알림톡 링크·공개 사업자 기본정보는 운영 Compose에 고정한다.
개발 `.env`가 운영 배포 명령에 섞여도 localhost로 되돌아가지 않게 하기 위해서다.

**DataAPI 는 반대로 안전한 쪽이 기본값이다(2026-10-09).** 키가 없으면 개발계·등록 꺼짐으로
떨어지고, 운영계와 등록은 서버 env 에 명시해야만 켜진다. 운영 전환 때 아래 두 줄을 넣는다.

```
DATAAPI_BASE_URL=https://api.mydatahub.co.kr
DATAAPI_WRITE_ENABLED=true
```

> ★ 키를 빠뜨려도 실매장에 답글이 달리지 않는다. 대신 운영계 토큰으로 개발계를 부르게 돼
> 인증 실패로 수집이 멈춘다 — 눈에 보이는 실패다. 전환 직후 반드시 확인한다:
> `dc exec worker printenv DATAAPI_BASE_URL DATAAPI_WRITE_ENABLED`

### 1.3 비밀값

**`.env` 를 저장소나 `deploy/` 안에 두지 말 것.** 서버의 `/etc/storemanager/env` 처럼
저장소 밖에 두고 권한을 `600` 으로 한다.

```bash
sudo install -d -m 700 /etc/storemanager
sudo touch /etc/storemanager/env && sudo chmod 600 /etc/storemanager/env
```

생성이 필요한 값:

```bash
openssl rand -base64 64   # JWT_SECRET
openssl rand -base64 32   # CREDENTIAL_MASTER_KEY
openssl rand -base64 32   # AUTHOR_HASH_SALT   ★ 바꾸면 기존 author_hash 와 연결이 끊긴다
openssl rand -base64 32   # INTERNAL_TOKEN
```

**운영 env 의 키 목록은 `deploy/env.example` 이다** — 복사해서 빈 값만 채운다(명령은 파일 머리말).
필수 항목은 거기 `[필수]` 표시를 따른다. 빠지면 컨테이너가 기동하지 않는다
(fail-closed — 비밀값이 조용히 빈 문자열로 도는 것보다 낫다).

네이버 확장은 **배달 먼저 출시(2026-09-28)** 에 따라 비워 둔다. 비어 있으면 확장 오리진을 하나도
허용하지 않는다(fail-closed). 웹스토어 등록 후 고정 ID 를 넣고 api-spring 만 재기동한다.
개발 기본값인 `chrome-extension://*`는 운영에서 허용하지 않는다.

```bash
PRODUCTION_EXTENSION_ORIGIN=chrome-extension://<Chrome-Web-Store-고정-ID>
```

## 2. 배포

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file /etc/storemanager/env up -d --build
docker compose -f deploy/docker-compose.prod.yml --env-file /etc/storemanager/env ps
```

Flyway 가 기동 시 마이그레이션을 적용한다. 실패하면 api-spring 이 뜨지 않는다 —
스키마가 어긋난 채 서비스가 도는 것보다 안전하다.

### 2.0 자동 배포 (2026-09-29)

`deploy/auto-deploy.sh` 가 5분마다 master 를 확인해 **CI 가 전부 초록인** 새 커밋만 끌어와
`up -d --build` 한다. master 에 병합하면 CI 시간(약 5~10분) + 최대 5분 + 빌드 시간 뒤 반영된다.

```bash
sudo chmod 700 /opt/storemanager/deploy/auto-deploy.sh
echo '*/5 * * * * root /opt/storemanager/deploy/auto-deploy.sh >> /var/log/storemanager-deploy.log 2>&1' \
  | sudo tee /etc/cron.d/storemanager-deploy
tail -f /var/log/storemanager-deploy.log     # 배포 기록
```

- 끄기: `sudo rm /etc/cron.d/storemanager-deploy`. 되돌리기는 `git revert` 후 master 에 병합 — 그것도 자동으로 나간다.
- **env 변경은 자동으로 반영되지 않는다.** env 는 저장소 밖이다. 바꾸면 여전히 `dc up -d <서비스>` 를 친다.
- 서버에서 저장소를 직접 고치면(`git status` 가 더러우면) fast-forward 가 실패해 멈춘다. 서버에서 코드를 고치지 말 것.
- ★ 22 포트를 GitHub Actions 에 열어 SSH 로 밀어 넣는 방식으로 바꾸지 말 것. 공개 저장소에 서버 키를 두게 된다.

### 2.1 첫 배포 후 확인

```bash
curl -sI https://review.sodamlabs.kr | head -3               # 200 + HSTS 헤더
curl -sk --max-time 5 https://<정적IP> -H "Host: review.sodamlabs.kr"  # (서버 밖에서) 타임아웃이어야 한다 — 오리진 잠금 확인
curl -s  https://review.sodamlabs.kr/internal/collect-result # 404 여야 한다 (외부 차단 확인)
curl -s  https://review.sodamlabs.kr/actuator/health         # 404 여야 한다
curl -s  http://127.0.0.1:18080/actuator/health              # (서버 안에서) UP — 관리 포트
curl -so /dev/null -w '%{http_code}
' http://127.0.0.1:18080/actuator/metrics  # (서버 안에서) 200 — collector 경로
```

`/internal/*` 가 404 가 아니면 **즉시 중단하라.** 그 경로의 `X-Internal-Token` 하나로
가맹본부 계정이 생성된다.

## 3. 백업

`backup` 컨테이너가 매일 KST 03:00 에 `pg_dump` 를 남긴다. 기본 14일 보관.

```bash
ls -lh ${BACKUP_DIR:-deploy/backups}/
```

### 3.1 반드시 할 것 — 복구 시연

**"백업이 돌고 있다" 와 "복구된다" 는 다르다.** 운영 투입 전에 최소 한 번은 실제로
복구해 보라. 안 해 보면 사고 당일에 처음 해 보게 된다.

```bash
gunzip -c backups/storemanager-YYYYmmdd-HHMMSS.sql.gz \
  | docker compose -f deploy/docker-compose.prod.yml exec -T postgres \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

### 3.2 백업본도 같은 등급이다
자격증명 암호문이 들어 있다. 원격 사본을 둘 때 접근권한을 DB 와 같게 관리한다.

> ★ **`pg_dump` 는 같은 인스턴스 디스크에 쌓인다 — 인스턴스를 잃으면 백업도 함께 잃는다.**
> off-box 사본은 Lightsail 자동 스냅샷이 담당한다(디스크 전체를 뜨므로 이 덤프들도 포함된다).
> 둘의 역할이 다르다: 덤프는 **논리 되돌리기**(배치가 데이터를 망쳤을 때), 스냅샷은
> **인스턴스 전손 복구**다. 하나로 다른 하나를 대신할 수 없다.

## 4. 운영 스위치

| 변수 | 기본 | 의미 |
|------|:----:|------|
| `DATAAPI_BASE_URL` | 개발계 | 운영계(`https://api.mydatahub.co.kr`)는 env 에 명시해야 켜진다 |
| `DATAAPI_WRITE_ENABLED` | `false` | **댓글 등록. 되돌릴 수 없다**(수정 API 스펙 미수령). `true` 를 명시해야 켜진다 |
| `DATAAPI_CALL_BUDGET` | `0` | 0=무제한. 테스트 토큰이면 잔여 횟수를 넣는다 |
| `DRAFT_SCHEDULER_ENABLED` | `true` | 끄면 리뷰만 쌓이고 답글이 생성되지 않는다 (LLM 비용) |
| `RETENTION_SCHEDULER_ENABLED` | `true` | **끄면 보유기간을 정해 두고 영구 보관하게 된다** |
| `BILLING_SCHEDULER_ENABLED` | `false` | **올리지 말 것.** Groble 동기화 전이라 잘못 청구된다 |
| `CREDENTIAL_REQUIRE_KMS` | `false` | `true` 로 두면 KMS 어댑터 전까지 기동이 차단된다(T-10) |
| `GOLDENSET_EVAL_ENABLED` | `false` | 1회 약 1,500원. 운영자가 직접 켠다 |

> ★ **셸 환경변수가 `--env-file` 보다 우선한다.** 셸에 같은 이름이 있으면 파일 값이
> 무시된다(실기동에서 겪음). 스위치가 안 먹으면 `docker compose config` 로 확인하라.

## 5. 호스트 구성 — 왜 1대인가

같은 돈으로 두 가지 모양이 나온다. **Lightsail 4GB $22 × 2 = 8GB $44 로 정확히 같다.**

| | 8GB 1대 (채택) | 4GB 2대 |
|---|---|---|
| 가용성 | 그 1대가 죽으면 정지 | **두 대 중 하나만 죽어도 정지** |
| 코드 변경 | 없음 | Redis 인증·사설망 DB·compose 분리 |
| 빌드 여유 | Gradle 빌드가 서비스를 안 죽인다 | 앱 호스트가 아슬아슬하다 |
| 복구 | 스냅샷 → 새 인스턴스 → 정적 IP 재부착 | 같음. **대상이 둘이라 두 번** |

**2대는 이 아키텍처에서 이중화가 아니다.** Postgres·Redis 가 한쪽에만 있으므로 역할을
쪼개면 *두 호스트가 모두 살아 있어야* 서비스가 돈다 — 가용성이 `P(A) × P(B)` 로 **내려간다.**
진짜 이중화는 로드밸런서($18/월) + 관리형 DB + Redis 복제가 붙어야 성립하고, 그때는
Spring 스케줄러 중복(아래)까지 손봐야 한다. 매장 100~200개 규모에서 그건 순증이다.

> ★ **이 제품은 실시간 서비스가 아니다 — 그래서 HA 가 필요 없다.** 수집은 하루 1회 10시이고
> 조회 윈도우가 2일이라 **한 번 통째로 빠져도 다음 날 같은 리뷰를 다시 가져온다.** 게시는
> 사장님 지연시간(기본 2시간) 뒤에 나가므로 **서버가 1시간 죽어도 답글은 늦지 않는다.**
> 다운타임에 실제로 사라지는 것은 대시보드 접속뿐이다. RTO 20분이면 충분하고, 그 20분을
> 위해 이중화를 사는 것은 이 규모에서 과잉이다.
>
> ★ **우리 가용성의 상한은 우리 서버가 아니다.** DataAPI 업체·Anthropic·배달 3사 중 어느
> 하나가 멈추면 서버를 몇 대로 늘려도 리뷰가 들어오지 않는다. 돈을 더 쓸 자리가 있다면
> 서버 대수보다 **`/admin/failures` 를 보는 습관과 로그 알람**이 먼저다(§7).
>
> ★ **장애 대응의 답은 두 번째 서버가 아니라 스냅샷이다.** Lightsail 은 **정지한 인스턴스도
> 과금**하므로 대기 서버를 꺼 둬도 돈이 나간다. 같은 $22 를 스냅샷(월 약 $1.5)에 쓰면
> 복구 지점이 7개 생긴다. §6 이 절차다.

### 5.1 2대로 갈라야 할 때가 오면 — 먼저 할 일

지금 코드는 **api-spring·worker 가 각각 1개일 때만** 안전하다. 늘리기 전에 이것부터 고쳐라.

| 늘리면 깨지는 것 | 왜 |
|---|---|
| `worker` (`--beat` 내장) | beat 가 2개면 `dispatch_polls` 가 10시에 두 번 뜬다. `lock:collect:*` 는 TTL 600초라 첫 수집이 끝난 뒤 두 번째가 락을 잡는다 → **DataAPI 조회 이중 과금**. beat 는 반드시 1개 |
| `DraftScheduler` | `findNeedingDraft` 를 두 인스턴스가 같이 집는다. 존재 검사(`DraftService:134`)가 **LLM 호출 전**이라 둘 다 통과 → **유료 호출 이중 과금** |
| `AlimtalkDispatchTransactions.claimNext` | SELECT 후 UPDATE 인데 행 락이 없다 → **같은 알림톡이 두 번** 간다. `@Lock(PESSIMISTIC_WRITE)` 한 줄이면 막힌다 |
| Redis | 지금 `requirepass` 가 없다. 사설망에 노출하려면 비밀번호부터 |

> `PublishScheduler`(초안별 `SETNX`)·게시 큐(Lua)·청구(`idempotency_key`)·일일 브리핑
> (`uq_daily_briefing_once`)은 이미 중복 안전하다. 위 4개만 다르다.

## 6. 복구 런북 (인스턴스 전손)

**목표 RTO 20분 / RPO 24시간.** 아래를 운영 투입 전에 한 번 그대로 해 본다 — §3.1 의
논리 복구 시연과 별개다.

1. Lightsail 콘솔 → 스냅샷 → **8GB 플랜으로 새 인스턴스 생성**
2. 죽은 인스턴스에서 **정적 IP 를 떼어 새 인스턴스에 붙인다** (DNS 는 건드리지 않는다)
3. 방화벽 443(CF 대역)·22(운영자 IP) 확인. **그 외는 닫혀 있어야 한다.** `/etc/storemanager/certs` 도 스냅샷에 있는지 확인
4. `/etc/storemanager/env` 가 스냅샷에 들어 있는지 확인 (없으면 다시 만든다 — `600`)
5. `docker compose -f deploy/docker-compose.prod.yml --env-file /etc/storemanager/env up -d`
6. §2.1 확인 3줄을 그대로 실행. **`/internal/*` 가 404 가 아니면 즉시 중단**
7. 마지막 스냅샷 이후의 리뷰는 다음 10시 폴링이 다시 가져온다(조회 윈도우 2일)

> ★ **4번을 건너뛰면 `AUTHOR_HASH_SALT` 가 바뀔 수 있고, 그러면 기존 `author_hash` 와
> 연결이 끊긴다**(같은 작성자를 다른 사람으로 본다). 비밀값은 스냅샷과 별도로 한 부 더 보관한다.
> ★ **DataAPI 토큰이 IP 화이트리스트라면 2번이 전부다.** 정적 IP 를 안 쓰고 있었다면
> 업체 재등록을 기다리는 동안 수집·게시가 멈춘다.

## 7. 아직 없는 것 (문서 21 §3)

배포 설정이 생겼다고 출시 준비가 끝난 것은 아니다.

- **알림 실발송 꺼짐** — 솔라피 경로는 있으나 휴대폰 인증 전에는 켜지 않는다(CLAUDE.md). 고위험 리뷰가 떠도 사장님은 모른다
- **로그 수집·알람 없음** — 컨테이너 로그만 남는다. 장애를 자동으로 알 방법이 없다
- **KMS 미전환(보류, 2026-09-28)** — 마스터키가 서버 파일에 있다(T-10). 대신 90일 자동 교체(§8)
- **약관·처리방침 변호사 미검토**

## 8. 비밀값 교체·보관 (2026-09-28)

### 8.1 자동 교체 — 90일

`deploy/rotate-secrets.sh` 가 `JWT_SECRET`·`INTERNAL_TOKEN`·`CREDENTIAL_MASTER_KEY` 를 새로 만든다.
마스터키는 `worker/rewrap.py` 가 전 행의 DEK 를 한 트랜잭션으로 다시 감싼 뒤에야 적용된다.

```bash
sudo chmod 700 /opt/storemanager/deploy/rotate-secrets.sh
echo '0 19 1 1,4,7,10 * root /opt/storemanager/deploy/rotate-secrets.sh >> /var/log/storemanager-rotate.log 2>&1' \
  | sudo tee /etc/cron.d/storemanager-rotate
```

- 1·4·7·10월 2일 **04:00 KST**. 03:00 백업 뒤, 10:00 수집 전이다. api-spring·worker 가 1~2분 멈춘다.
- **첫 배포 직후 한 번 손으로 돌려 본다.** 복구 시연(§3.1)과 같은 이유다 — 처음 도는 날이 사고 날이면 안 된다.
- 옛 env 는 `/etc/storemanager/key-archive/env.<UTC시각>` 에 4개(약 1년) 남는다.

> ★ **교체 전 백업은 옛 마스터키로만 풀린다.** 그 덤프로 복구했다면 `key-archive` 에서 그 시점 env 의
> `CREDENTIAL_MASTER_KEY`·`CREDENTIAL_KEY_ID` 로 기동하거나, 아래 8.3 으로 현재 키로 감싸 올린다.
> ★ 사고(유출 의심)면 주기를 기다리지 말고 즉시 손으로 돌린다.

### 8.2 교체하지 않는 것 — 보관 정책

| 값 | 교체 | 잃으면 |
|---|---|---|
| **`AUTHOR_HASH_SALT`** | **영구 고정. 절대 바꾸지 않는다** | 기존 리뷰 작성자와 신규 작성자의 연결이 끊긴다. 되돌릴 방법이 없다 |
| `DATAAPI_ENC_SPEC`·`_KEY`·`_IV` | 업체가 바꿀 때만 | 업체에 재발급 요청 |
| `DATAAPI_TOKEN`·`ANTHROPIC_API_KEY` | 유출 의심 시 콘솔에서 재발급 | 재발급 |
| `POSTGRES_PASSWORD` | 유출 의심 시 `ALTER USER` 후 env 변경 | DB 컨테이너에서 재설정 |
| Cloudflare Origin CA 키 | 15년 만료 전 / 유출 시 CF 에서 폐기·재발급 | 재발급 |

**`AUTHOR_HASH_SALT` 보관 규칙**

1. 서버 `/etc/storemanager/env`(600) 외에 **서버 밖 두 곳**에 둔다
   — ① 비밀번호 관리자(대표 계정, 2단계 인증) ② 오프라인 사본(인쇄 또는 암호화 USB, 사무실 잠금 보관).
   Lightsail 스냅샷은 사본으로 치지 않는다 — 같은 AWS 계정을 잃으면 함께 잃는다.
2. **저장소·채팅·메일·이슈에 절대 붙이지 않는다.** 이 값은 가명처리의 열쇠다.
3. 접근자는 대표 1인. 사람이 늘면 이 줄을 먼저 고친다.
4. 두 사본에 **지문**을 함께 적어 둔다. 복구 뒤 서버 값과 대조하면 값을 드러내지 않고 같은지 안다.
   ```bash
   sudo sh -c '. /etc/storemanager/env; printf %s "$AUTHOR_HASH_SALT" | sha256sum | cut -c1-16'
   ```
5. 연 1회(1월 교체와 같은 날) 두 사본의 지문이 서버와 같은지 확인한다.

> ★ `rotate-secrets.sh` 가 salt 를 건드리지 않는 것은 의도다. "4종 전부 교체" 로 고치지 말 것.

### 8.3 교체 되돌리기 (헬스체크 실패 시)

DB 는 이미 새 키로 감싸져 있으므로 **env 만 되돌리면 자격증명을 못 읽는다.** 거꾸로 다시 감싼다.

```bash
cd /opt/storemanager/deploy
DC="docker compose -f docker-compose.prod.yml --env-file /etc/storemanager/env"
OLD=/etc/storemanager/key-archive/env.<UTC시각>
export OLD_MASTER_KEY=$(sed -n 's/^CREDENTIAL_MASTER_KEY=//p' /etc/storemanager/env)
export NEW_MASTER_KEY=$(sed -n 's/^CREDENTIAL_MASTER_KEY=//p' "$OLD")
export NEW_KEY_ID=$(sed -n 's/^CREDENTIAL_KEY_ID=//p' "$OLD"); : "${NEW_KEY_ID:=prod}"
$DC stop api-spring worker
$DC run --rm --no-deps -e OLD_MASTER_KEY -e NEW_MASTER_KEY -e NEW_KEY_ID worker python rewrap.py
sudo cp -p "$OLD" /etc/storemanager/env && $DC up -d
```
