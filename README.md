# 매장 매니저

배달앱(배민·요기요·쿠팡이츠) 리뷰 답글 자동화 SaaS.

## 디렉토리 구조

```
/api-spring     Spring Boot — 도메인·API·결제·워크플로
/ai-python      FastAPI — LLM 라우팅·RAG·가드레일
/worker         Celery — DataAPI 수집/게시 워커
/web            React 18 + TS + Vite
/docs           설계 문서
```

## 사전 요구사항

- JDK 17
- Node 22
- Python 3.12+
- Docker Desktop

## 로컬 포트

호스트 5432/6379 는 다른 프로젝트(stockmate-ai)가 이미 점유 중이라, Postgres/Redis 는
아래처럼 다른 호스트 포트로 매핑한다. 컨테이너 내부 포트는 표준값 그대로다.

| 서비스 | 호스트 포트 |
|--------|------------|
| Postgres | 5433 |
| Redis | 6380 |
| Spring | 8080 |
| AI Service (FastAPI) | 8001 |
| Web (Vite) | 5173 |

## 로컬 실행 순서

```bash
# 1. 인프라 (Postgres+pgvector, Redis)
docker compose up -d

# 2. 환경변수
cp .env.example .env
# .env 를 열어 값 채우기 (JWT_SECRET, CREDENTIAL_MASTER_KEY 등)

# 3. Spring
cd api-spring && ./gradlew bootRun

# 4. AI Service
cd ai-python && uvicorn main:app --reload --port 8001

# 5. Collector Worker
cd worker && celery -A tasks worker -l info

# 6. Web
cd web && npm install && npm run dev
```

## 테스트

```bash
cd api-spring && ./gradlew test
cd ai-python && pytest
cd worker && pytest
```

## 현재 상태

- Sprint 0 (DataAPI 토큰 발급) — 보류, 회신 대기 중
- Sprint 1 (기반: 스키마·인증·KMS 유틸·CI) — 진행 중

문서 인덱스는 `CLAUDE.md` 를 참조.
