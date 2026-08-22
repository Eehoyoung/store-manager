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

- Sprint 1~5, 8 완료 — 인증·매장·수집 파이프라인·AI 엔진·게시·프론트엔드·가맹본부
- Sprint 0 (DataAPI 실호출 검증) — 보류, 개발계 토큰 회신 대기
- Sprint 6~7 (결제 상태 동기화·전자계약·출시) — 진행 중

## 설계 문서

**설계·운영 문서는 이 저장소에 포함되지 않습니다.** 요구사항명세·DB 스키마·API 명세·화면명세·
핸드오프·약관 초안과 작업 규약(`CLAUDE.md`)은 별도로 관리하며, 필요하면 저장소 소유자에게 요청하십시오.
