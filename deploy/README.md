# 운영 배포

개발용(`docker-compose.yml`)과 운영용(`deploy/docker-compose.prod.yml`)은 다르다.
운영에서 개발용을 쓰면 **DB·Redis 포트가 인터넷에 열린다.** 그 DB 에는 배달앱 자격증명
암호문과 리뷰 원문이 들어 있다.

## 1. 준비

### 1.1 서버
- Docker + Docker Compose v2
- 80/443 인바운드 개방. **그 외 포트는 닫는다** (5432·6379·8080 절대 열지 말 것)
- 디스크: DB + 백업 14일치. 매장 100개 기준 최소 50GB 권장

### 1.2 도메인
`SITE_DOMAIN` 의 A 레코드가 서버 IP 를 가리켜야 한다. Caddy 가 Let's Encrypt 인증서를
자동으로 발급·갱신한다 — DNS 가 안 붙은 상태로 올리면 발급에 실패하고 재시도 한도에 걸린다.

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

필수 항목은 `.env.example` 의 `[필수]` 표시를 따른다. 빠지면 컨테이너가 기동하지 않는다
(fail-closed — 비밀값이 조용히 빈 문자열로 도는 것보다 낫다).

## 2. 배포

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file /etc/storemanager/env up -d --build
docker compose -f deploy/docker-compose.prod.yml --env-file /etc/storemanager/env ps
```

Flyway 가 기동 시 마이그레이션을 적용한다. 실패하면 api-spring 이 뜨지 않는다 —
스키마가 어긋난 채 서비스가 도는 것보다 안전하다.

### 2.1 첫 배포 후 확인

```bash
curl -sI https://$SITE_DOMAIN | head -3               # 200 + HSTS 헤더
curl -s  https://$SITE_DOMAIN/internal/collect-result # 404 여야 한다 (외부 차단 확인)
curl -s  https://$SITE_DOMAIN/actuator/health         # 404 여야 한다
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

## 4. 운영 스위치

| 변수 | 기본 | 의미 |
|------|:----:|------|
| `DATAAPI_WRITE_ENABLED` | `false` | **댓글 등록. 되돌릴 수 없다**(수정 API 스펙 미수령) |
| `DATAAPI_CALL_BUDGET` | `0` | 0=무제한. 테스트 토큰이면 잔여 횟수를 넣는다 |
| `DRAFT_SCHEDULER_ENABLED` | `true` | 끄면 리뷰만 쌓이고 답글이 생성되지 않는다 (LLM 비용) |
| `RETENTION_SCHEDULER_ENABLED` | `true` | **끄면 보유기간을 정해 두고 영구 보관하게 된다** |
| `BILLING_SCHEDULER_ENABLED` | `false` | **올리지 말 것.** Groble 동기화 전이라 잘못 청구된다 |
| `CREDENTIAL_REQUIRE_KMS` | `false` | `true` 로 두면 KMS 어댑터 전까지 기동이 차단된다(T-10) |
| `GOLDENSET_EVAL_ENABLED` | `false` | 1회 약 1,500원. 운영자가 직접 켠다 |

> ★ **셸 환경변수가 `--env-file` 보다 우선한다.** 셸에 같은 이름이 있으면 파일 값이
> 무시된다(실기동에서 겪음). 스위치가 안 먹으면 `docker compose config` 로 확인하라.

## 5. 아직 없는 것 (문서 21 §3)

배포 설정이 생겼다고 출시 준비가 끝난 것은 아니다.

- **수집 자동 스케줄 없음** — 호출 단가 미수령. 지금은 사람이 실행해야 리뷰가 들어온다
- **알림 실발송 없음** — 벤더 미선정. 고위험 리뷰가 떠도 사장님은 모른다
- **로그 수집·알람 없음** — 컨테이너 로그만 남는다. 장애를 자동으로 알 방법이 없다
- **KMS 미전환** — 마스터키가 서버 파일에 있다(T-10)
- **약관·처리방침 변호사 미검토**
