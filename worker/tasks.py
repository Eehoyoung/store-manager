# TODO(Sprint 2): 수집 워커 본체 구현. 여기서는 시그니처와 흐름만 정의한다.
"""Celery 태스크 — DataAPI 수집(poll/backfill).

★ 이 워커가 Spring 과 통신하는 유일한 경로는 POST /internal/collect-result 뿐이다
  (CLAUDE.md 서비스 간 경계). 다른 엔드포인트를 추가하지 않는다.
★ authorRaw(닉네임)는 가공 없이 그대로 전달한다 — 가명처리는 Spring 이 담당한다
  (docs/13 §11.2). 요청 본문은 로깅하지 않는다.
"""
from celery_app import app


@app.task(name="tasks.poll_reviews")
def poll_reviews(account_id: str) -> None:
    """정기 수집(1일 N회): 최근 2일 재조회 → 정규화 → /internal/collect-result 전달.

    흐름(TODO, Sprint 2):
      1. account_id 로 platform_account 조회 (LOGINID, KMS 복호화된 LOGINPWD, platform, 매장 매핑)
      2. dataapi.DataApiClient.fetch_reviews(
             platform, credentials,
             start_date=오늘-2일(yyyyMMdd), end_date=오늘(yyyyMMdd))
         → 증분 조회가 없으므로(문서 08 F-4) 매번 최근 2일을 통째로 재조회한다.
      3. dataapi.parse_envelope 결과를 받아 REVIEWLIST[] 순회
         (1계정 N매장 구조, 문서 08 F-7 — 절대 REVIEWLIST 를 단일 매장으로 가정하지 않는다)
      4. 리뷰 필드를 docs/13 §11.2 스키마로 정규화한다.
         - authorRaw 는 원문(예: 배민 미마스킹 닉네임) 그대로 전달 — Worker 는 가공하지 않는다.
         - RC_LIST 의 기존 답글은 existingReply 로 함께 전달 (RAG 코퍼스 적재의 원천 데이터)
      5. POST /internal/collect-result 로 전달. (platform, REVIEWID) 기준 dedupe 는
         Spring 쪽 UPSERT 로 처리되므로 Worker 는 중복 여부를 신경 쓰지 않는다.
         이 요청의 본문은 절대 로깅하지 않는다.
      6. DataApiError(로그인 실패 등) 발생 시 재시도하지 않고 link_status=ERROR 로 보고한다.
    """
    raise NotImplementedError("Sprint 2 에서 구현 예정")


@app.task(name="tasks.backfill")
def backfill(account_id: str) -> None:
    """최초 연동 백필: 90일을 7일 단위로 분할 호출(문서 08 F-4 — 타임아웃·응답크기 대비).

    흐름(TODO, Sprint 2):
      1. 오늘부터 과거 90일을 7일 단위 창(약 13개)으로 분할
      2. 각 창마다 poll_reviews 와 동일한 조회 → 정규화 → /internal/collect-result 전달 흐름 수행
      3. 창 간에는 DataAPI 호출 속도 제한을 두고 순차 처리 (레이트리밋)
      4. RC_LIST(기존 답글)가 이 시점에 대량으로 수집되며, 이는 말투 학습 RAG 코퍼스의
         핵심 소스가 된다(문서 08 §5) — 별도 온보딩 설문 불필요.
    """
    raise NotImplementedError("Sprint 2 에서 구현 예정")
