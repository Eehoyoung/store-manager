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

- Docker Desktop

호스트에서 모듈을 직접 실행하는 경우에만 JDK 17, Node 22, Python 3.12+가 필요합니다.

## 로컬 포트

호스트 5432/6379 는 다른 프로젝트(stockmate-ai)가 이미 점유 중이라, Postgres/Redis 는
아래처럼 다른 호스트 포트로 매핑한다. 컨테이너 내부 포트는 표준값 그대로다.

| 서비스 | 호스트 포트 |
|--------|------------|
| Postgres | 5433 |
| Redis | 6380 |
| Spring | 18080 |
| AI Service (FastAPI) | 18001 |
| Web (Vite preview) | 15173 |
| Worker | 외부 포트 없음 |

## 로컬 실행 순서

```bash
# 전체 스택 빌드·기동 (Postgres/Redis/Spring/AI/Worker/Web)
docker compose up -d --build

# 2. 환경변수
cp .env.example .env
# .env 를 열어 값 채우기 (JWT_SECRET, CREDENTIAL_MASTER_KEY 등)

# 브라우저: http://localhost:15173
# API 상태: http://localhost:18080/actuator/health
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
