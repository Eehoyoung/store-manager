"""수집 스케줄·재시도 회귀 테스트.

★ 호출 1건이 곧 돈이다(CLAUDE.md 인프라 원가). 스케줄과 재시도 횟수가 조용히 늘면
  청구서에서만 드러난다. 여기서 먼저 걸리게 한다.
"""
import celery_app
import dataapi
import tasks


def test_수집은_하루_3회_10시_16시_20시다():
    """★ 1회 늘리면 3플랫폼 매장당 월 90회(약 21%)가 늘어난다.

    바꾸려면 CLAUDE.md 의 호출 단가 손익표를 함께 고쳐야 한다.
    """
    cron = celery_app.app.conf.beat_schedule["dispatch-polls"]["schedule"]
    assert cron.hour == {10, 16, 20}
    assert cron.minute == {0}


def test_시간대가_서울로_고정되어_있다():
    """★ 기본값 UTC 로 두면 수집이 한국시간 19·01·05시에 돈다 — 전부 빈 조회다."""
    assert celery_app.app.conf.timezone == "Asia/Seoul"
    assert celery_app.app.conf.enable_utc is False


def test_재시도는_2회까지다():
    """★ 호출당 과금이다. 3회면 실패 1건에 3회분을 태운다(2026-08-25 결정)."""
    assert dataapi.MAX_ATTEMPTS == 2

    calls = []

    def always_fails():
        calls.append(1)
        raise dataapi.DataApiError("ERR_TMP", "일시 오류", retryable=True)

    try:
        dataapi.call_with_retry(always_fails, sleep=lambda _: None)
    except dataapi.DataApiError:
        pass
    assert len(calls) == 2, f"{len(calls)}회 호출됐다 — 2회여야 한다"


def test_재시도_불가는_한_번만_호출한다():
    """로그인 실패를 재시도하면 호출료만 쓰고 결과는 같다."""
    calls = []

    def login_failed():
        calls.append(1)
        raise dataapi.DataApiError(dataapi.ECODE_LOGIN_FAIL, "로그인 실패", retryable=False)

    try:
        dataapi.call_with_retry(login_failed, sleep=lambda _: None)
    except dataapi.DataApiError:
        pass
    assert len(calls) == 1


def test_팬아웃은_대상이_없으면_호출을_만들지_않는다():
    assert tasks.dispatch_polls(account_lister=lambda: [])["dispatched"] == 0


def test_대상_조회가_실패하면_호출을_만들지_않는다():
    """★ DB 를 못 읽을 때 '전체 계정' 으로 폴백하면 안 된다 — 해지 매장에 호출이 나간다."""
    def boom():
        raise RuntimeError("DB 접근 실패")

    result = tasks.dispatch_polls(account_lister=boom)
    assert result["status"] == "ERROR"
    assert result["dispatched"] == 0
