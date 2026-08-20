"""
DataAPI(데이터허브) 클라이언트 — 리뷰 조회 / 댓글 등록.

★ 절대규칙 2(CLAUDE.md): 성공 판정은 data.RESULT == "SUCCESS" 로만 한다.
  DataAPI 는 실패해도 HTTP 200 / errCode "0000" / 최상위 result "SUCCESS" 를 반환하므로
  최상위 result 로 판정하면 모든 실패가 성공 처리된다 (문서 08 F-1).

★ 절대규칙 5(CLAUDE.md): LOGINPWD 는 평문으로 로깅·저장·전송하지 않는다.
  KMS 봉투암호화만 사용하며, 암호화 방식은 업체 회신 대기 중이므로 encrypt_password() 는 TODO.

★ CI 에서 이 클라이언트로 실제 네트워크 호출을 하는 테스트는 금지한다 (픽스처 목만 사용).
"""
from __future__ import annotations

import os
import time
from dataclasses import dataclass
from typing import Any, Callable, Literal, TypeVar

import httpx

Platform = Literal["baemin", "yogiyo", "coupangeats"]

# 개발계/운영계 환경변수로 분리 — 운영계 토큰은 로컬에 두지 않는다.
BASE_URL = os.environ.get("DATAAPI_BASE_URL", "https://datahub-dev.scraping.co.kr")
TOKEN = os.environ.get("DATAAPI_TOKEN", "")

# 개발 중 실수로 실제 리뷰에 답글이 달리지 않도록 쓰기 작업은 기본 비활성.
WRITE_ENABLED_ENV = "DATAAPI_WRITE_ENABLED"

# 답글 하드 제한 (CLAUDE.md #8: 플랫폼 300자보다 여유를 둔 280자)
MAX_COMMENT_LENGTH = 280

# 알려진 ECODE (CLAUDE.md 표 그대로). 전체 목록은 업체 회신 대기 중(문서 09).
ECODE_LOGIN_FAIL = "ERR_MLCOM_MSG50059"  # 로그인 실패 — 재시도 금지, link_status=ERROR
ECODE_DUPLICATE_COMMENT = "ERR_MDCOM_MSG00009"  # 댓글 중복 — 실패가 아님, ALREADY_REPLIED

# ECODE → (재시도 가능 여부, 후속 조치). 후속 조치는 문서 08 §6.2 그대로:
#   LINK_ERROR       재로그인 필요 — 연동 상태 ERROR, 사장님 알림 (Spring 이 실제 전이를 수행)
#   ALREADY_REPLIED  실패가 아니라 정상 종료 시나리오
#   FAIL             그 외 실패 — 재시도하지 않고 그대로 보고
# 미확인 코드는 이 표에 없으므로 기본 재시도 금지 + FAIL 로 처리한다 (CLAUDE.md).
ECODE_POLICY: dict[str, tuple[bool, str]] = {
    ECODE_LOGIN_FAIL: (False, "LINK_ERROR"),
    ECODE_DUPLICATE_COMMENT: (False, "ALREADY_REPLIED"),
}


def _s(v: Any) -> str | None:
    """DataAPI 는 값이 없을 때 JSON null 이 아니라 문자열 "null" 을 보낸다 (문서 08 F-2)."""
    return None if v in (None, "", "null") else str(v)


def _is_retryable(ecode: str | None) -> bool:
    """ECODE_POLICY 에 등록된 코드만 재시도 가능 여부를 명시적으로 판단한다.
    미확인 ECODE 는 기본 재시도 금지로 처리한다 (CLAUDE.md).
    TODO: 전체 ECODE 목록 수령 후 세션만료/캡차·타임아웃/한도초과 케이스를 채운다."""
    if ecode is None:
        return False
    policy = ECODE_POLICY.get(ecode)
    return policy[0] if policy else False


def ecode_action(ecode: str | None) -> str:
    """ECODE 에 대응하는 후속 조치 라벨. 미확인 코드는 'FAIL' 로 처리하고 호출부가 로그를 남긴다."""
    if ecode is None:
        return "FAIL"
    policy = ECODE_POLICY.get(ecode)
    return policy[1] if policy else "FAIL"


class DataApiError(Exception):
    """data.RESULT != "SUCCESS" 일 때 발생 (댓글 중복은 AlreadyRepliedError 로 별도 처리)."""

    def __init__(self, ecode: str | None, errmsg: str | None, retryable: bool):
        self.ecode = ecode
        self.errmsg = errmsg
        self.retryable = retryable
        super().__init__(f"DataApiError(ecode={ecode}, retryable={retryable}): {errmsg}")


class AlreadyRepliedError(DataApiError):
    """ECODE_DUPLICATE_COMMENT — 실패가 아니라 정상 종료 시나리오다 (문서 08 F-8).
    사장님이 이미 앱에서 직접 답글을 달았을 수 있으므로 호출부는 이걸 잡아 ALREADY_REPLIED 로
    마킹하고 정상 종료해야 한다. 절대 재시도하지 않는다."""


def parse_envelope(resp: dict) -> dict:
    """★ 필수 구현 패턴(CLAUDE.md) 그대로. 최상위 result 가 아니라 반드시
    data.RESULT 로만 성공을 판정한다."""
    data = resp.get("data") or {}
    if data.get("RESULT") != "SUCCESS":
        ecode = _s(data.get("ECODE"))
        errmsg = _s(data.get("ERRMSG"))
        if ecode == ECODE_DUPLICATE_COMMENT:
            raise AlreadyRepliedError(ecode, errmsg, retryable=False)
        raise DataApiError(ecode, errmsg, retryable=_is_retryable(ecode))
    return data


def is_retryable_exception(exc: BaseException) -> bool:
    """재시도 헬퍼(call_with_retry)가 재시도 여부를 판단하는 데 쓰는 분류기.
    DataApiError 는 ECODE_POLICY 를 그대로 따르고, HTTP 레벨 예외(타임아웃·5xx)는
    일시 장애로 보아 재시도 가능으로 분류한다 (문서 08 §6.2 '타임아웃·일시 장애' 행)."""
    if isinstance(exc, DataApiError):
        return exc.retryable
    if isinstance(exc, httpx.TimeoutException):
        return True
    if isinstance(exc, httpx.HTTPStatusError):
        return 500 <= exc.response.status_code < 600
    return False


_T = TypeVar("_T")


def call_with_retry(
    fn: Callable[[], _T],
    max_attempts: int = 3,
    base_delay: float = 1.0,
    sleep: Callable[[float], None] = time.sleep,
) -> _T:
    """지수 백오프 재시도(최대 max_attempts 회). AlreadyRepliedError 는 정상 종료
    시나리오이므로 즉시 그대로 전파하고, 재시도 불가로 분류된 예외도 즉시 전파한다.
    sleep 을 주입 가능하게 해 테스트에서 대기 없이 즉시 실행되도록 한다."""
    attempt = 0
    while True:
        attempt += 1
        try:
            return fn()
        except AlreadyRepliedError:
            raise
        except Exception as exc:
            if attempt >= max_attempts or not is_retryable_exception(exc):
                raise
            sleep(base_delay * (2 ** (attempt - 1)))


def encrypt_password(raw_password: str) -> str:
    """TODO: LOGINPWD 암호화 방식 미확정(Base64 추정, 문서 09 회신 대기).
    확정 전까지는 호출을 막아 평문이 실수로 전송되는 것을 방지한다."""
    raise NotImplementedError("LOGINPWD 암호화 방식 미확정 — 문서 09 회신 후 구현 (CLAUDE.md 절대규칙 5)")


@dataclass
class Credentials:
    """LOGINPWD 를 평문으로 로그·예외 메시지에 남기지 않기 위한 래퍼 (절대규칙 5)."""

    login_id: str
    login_pwd_encrypted: str  # KMS 봉투암호화된 값만 담는다. 평문 저장 금지.

    def __repr__(self) -> str:
        return f"Credentials(login_id={self.login_id!r}, login_pwd_encrypted='***')"


class DataApiClient:
    """DataAPI(데이터허브) REST 클라이언트. 조회(reviewManagement)/등록(CreateComment)만 다룬다."""

    def __init__(self, base_url: str = BASE_URL, token: str = TOKEN, timeout: float = 30.0):
        self._base_url = base_url.rstrip("/")
        self._headers = {"Content-Type": "application/json", "Authorization": f"Token {token}"}
        self._timeout = timeout

    def fetch_reviews(
        self, platform: Platform, credentials: Credentials, start_date: str, end_date: str
    ) -> dict:
        """리뷰 조회. start_date/end_date 는 yyyyMMdd. 반환값은 parse_envelope 통과 후의 data
        (REVIEWLIST[] 포함 — 1계정 N매장, 문서 08 F-7)."""
        url = f"{self._base_url}/scrap/deliveryapp/{platform}/reviewManagement"
        payload = {
            "LOGINID": credentials.login_id,
            "LOGINPWD": credentials.login_pwd_encrypted,
            "STARTDATE": start_date,
            "ENDDATE": end_date,
        }
        resp = httpx.post(url, json=payload, headers=self._headers, timeout=self._timeout)
        resp.raise_for_status()
        return parse_envelope(resp.json())

    def create_comment(
        self, platform: Platform, credentials: Credentials, contents: str, store_id: str, review_id: str
    ) -> dict:
        """댓글 등록. **되돌릴 수 없다** — 수정 API 스펙 미수령(문서 08 F-11).
        DATAAPI_WRITE_ENABLED=true 가 아니면 개발 중 실수 방지를 위해 즉시 중단한다."""
        if os.environ.get(WRITE_ENABLED_ENV, "false").lower() != "true":
            raise RuntimeError(
                f"DataAPI 쓰기 작업이 비활성화되어 있습니다 ({WRITE_ENABLED_ENV}=true 로 명시적으로 켤 것)"
            )
        if len(contents) > MAX_COMMENT_LENGTH:
            raise ValueError(f"답글은 {MAX_COMMENT_LENGTH}자를 초과할 수 없습니다")

        url = f"{self._base_url}/scrap/deliveryapp/{platform}/CreateComment"
        payload = {
            "LOGINID": credentials.login_id,
            "LOGINPWD": credentials.login_pwd_encrypted,
            "CONTENTS": contents,
            "STOREID": store_id,
            "REVIEWID": review_id,
        }
        resp = httpx.post(url, json=payload, headers=self._headers, timeout=self._timeout)
        resp.raise_for_status()
        return parse_envelope(resp.json())
