"""
DataAPI(데이터허브) 클라이언트 — 리뷰 조회 / 댓글 등록.

★ 절대규칙 2(CLAUDE.md): 성공 판정은 data.RESULT == "SUCCESS" 로만 한다.
  DataAPI 는 실패해도 HTTP 200 / errCode "0000" / 최상위 result "SUCCESS" 를 반환하므로
  최상위 result 로 판정하면 모든 실패가 성공 처리된다 (문서 08 F-1).

★ 절대규칙 5(CLAUDE.md): LOGINPWD 는 평문으로 로깅·저장·전송하지 않는다.
  전송 시에는 업체 규격(EncSpec)으로 암호화한다 — encrypt_password() 참고.
  파라미터는 DATAAPI_ENC_SPEC/_KEY/_IV 환경변수로만 받는다. 소스에 넣지 말 것.

★ CI 에서 이 클라이언트로 실제 네트워크 호출을 하는 테스트는 금지한다 (픽스처 목만 사용).
"""
from __future__ import annotations

import os
import re
import time
from dataclasses import dataclass
from typing import Any, Callable, Literal, TypeVar

import httpx

import budget

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
# 조회 결과 없음 — 실패가 아니다. 로그인은 성공했고 해당 기간에 리뷰가 없다는 뜻이다.
# 실기동에서 확인(2026-08-23, 요기요). ERRMSG="조회된 내역이 없습니다."
# ★ 이걸 실패로 세면 수집 성공률(T-5)이 리뷰 없는 날마다 떨어진다.
ECODE_NO_DATA = "ERR_MLCOM_MSG50079"

PLATFORM_OK_CODE = "0000"  # 최상위 errCode 성공값. 업무 성공을 뜻하지 않는다(절대규칙 2).

# 호출 한도 소진. 테스트 토큰은 이용횟수가 제한돼 있고, 소진되면 전 요청이 이 코드로 막힌다.
# ★ 절대 재시도하지 않는다 — 재시도해도 한 번도 성공하지 않으면서 문제만 키운다.
ECODE_NO_POINT = "2003"
ECODE_AUTH_FAIL = "8003"  # Authorization 토큰 오류
ECODE_DECRYPT_FAIL = "3020"  # 입력 데이터 복호화 실패 = 우리 LOGINPWD 암호화 설정이 틀렸다

# ECODE → (재시도 가능 여부, 후속 조치). 후속 조치는 문서 08 §6.2 그대로:
#   LINK_ERROR       재로그인 필요 — 연동 상태 ERROR, 사장님 알림 (Spring 이 실제 전이를 수행)
#   ALREADY_REPLIED  실패가 아니라 정상 종료 시나리오
#   CONFIG_ERROR     우리 설정 문제 — 사장님이 아니라 운영자가 고쳐야 한다
#   QUOTA_EXHAUSTED  호출 한도 소진 — 운영자 확인 필요, 재시도 금지
#   FAIL             그 외 실패 — 재시도하지 않고 그대로 보고
# 미확인 코드는 이 표에 없으므로 기본 재시도 금지 + FAIL 로 처리한다 (CLAUDE.md).
#
# 출처: docs/전체오류코드.html (최상위 errCode) + 업체 표(스크래핑 모듈 ERR_*).
# ★ ERR_MLCOM_* 전체 목록은 아직 미수령이다(T-4). 아래 2건 외에는 기본 재시도 금지로 남는다.
ECODE_POLICY: dict[str, tuple[bool, str]] = {
    # ── 스크래핑 모듈 (배달앱 로그인·조회) ──
    ECODE_LOGIN_FAIL: (False, "LINK_ERROR"),
    ECODE_DUPLICATE_COMMENT: (False, "ALREADY_REPLIED"),
    ECODE_NO_DATA: (False, "NO_DATA"),
    # ── 플랫폼 (DataAPI 자체) ──
    ECODE_NO_POINT: (False, "QUOTA_EXHAUSTED"),
    ECODE_AUTH_FAIL: (False, "CONFIG_ERROR"),
    ECODE_DECRYPT_FAIL: (False, "CONFIG_ERROR"),
    "2002": (False, "CONFIG_ERROR"),   # 토큰에 서비스 미연동
    "2010": (False, "CONFIG_ERROR"),   # 등록되지 않은 토큰
    "2012": (False, "CONFIG_ERROR"),   # 암복호화 설정 이상
    "6108": (False, "CONFIG_ERROR"),   # 암복호화 설정 필요
    "1000": (False, "FAIL"),           # 요청 검증 실패
    "1001": (False, "FAIL"),           # JSON 파싱 실패
    "9910": (False, "FAIL"),           # 요청 형식 오류
    # 일시 장애 — 백오프 후 재시도
    "2020": (True, "FAIL"),            # QOS 동시접속 초과
    "2021": (True, "FAIL"),            # QOS 동시접속 초과(API)
    "8004": (True, "FAIL"),            # QOS FAIL (일시 부하·네트워크)
    "3030": (True, "FAIL"),            # Worker Error
    "3031": (True, "FAIL"),            # Worker Error - Timeout
    "3070": (True, "FAIL"),            # 응답시간 초과
    "3090": (True, "FAIL"),            # 가용 Worker 없음
}


def _s(v: Any) -> str | None:
    """DataAPI 는 값이 없을 때 JSON null 이 아니라 문자열 "null" 을 보낸다 (문서 08 F-2)."""
    return None if v in (None, "", "null") else str(v)


def _is_retryable(ecode: str | None) -> bool:
    """ECODE_POLICY 에 등록된 코드만 재시도 가능 여부를 명시적으로 판단한다.
    미확인 ECODE 는 기본 재시도 금지로 처리한다 (CLAUDE.md).
    TODO(T-4): 스크래핑 모듈 ERR_* 전체 목록 수령 후 세션만료·캡차 케이스를 채운다."""
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
    data.RESULT 로만 성공을 판정한다.

    ★ 오류 코드는 두 층에서 온다.
      - data.ECODE   스크래핑 모듈(배달앱 로그인·조회) 오류. 예: ERR_MLCOM_MSG50059
      - errCode      플랫폼 오류. 예: 2003 이용횟수 소진, 8003 토큰 오류, 3020 복호화 실패
    플랫폼 레벨에서 거절되면 data 가 통째로 비어 ECODE 가 없다. 그때 최상위 errCode 로
    떨어지지 않으면 '원인 불명 실패' 로만 남는다 — 호출 한도 소진을 로그인 실패로 오인하게 된다.
    """
    data = resp.get("data") or {}
    if data.get("RESULT") != "SUCCESS":
        ecode = _s(data.get("ECODE"))
        # EMSG 는 스펙상 "데이터부 상세 오류 메시지" 다. ERRMSG 가 비어 있을 때 원인이 여기 있다.
        errmsg = _s(data.get("ERRMSG")) or _s(data.get("EMSG"))
        if ecode is None:
            # 표기가 문서마다 다르다(errCode/ERRCODE, errMsg/ERRMSG). 있는 것을 집는다.
            ecode = _s(resp.get("errCode")) or _s(resp.get("ERRCODE"))
            errmsg = errmsg or _s(resp.get("errMsg")) or _s(resp.get("ERRMSG"))
            # errCode "0000" 은 플랫폼 성공이라는 뜻일 뿐 업무 성공이 아니다(절대규칙 2).
            # 그 값을 오류 코드로 보고하면 '성공 코드로 실패' 라는 모순된 로그가 남는다.
            if ecode == PLATFORM_OK_CODE:
                ecode = None
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


# ── LOGINPWD 암호화 ────────────────────────────────────────────────────────
# 업체(기웅정보통신)가 발급한 EncSpec/EncKey/EncIV 로 비밀번호를 감싸 전송한다.
# 값은 전부 환경변수로만 받는다 — 소스에 하드코딩하면 그 순간 저장소가 자격증명 보관소가 된다.
ENC_SPEC = os.environ.get("DATAAPI_ENC_SPEC", "")
ENC_KEY = os.environ.get("DATAAPI_ENC_KEY", "")
ENC_IV = os.environ.get("DATAAPI_ENC_IV", "")
# 암호문 인코딩. 업체 샘플(PlainData→EncData)로 확정한다. verify_enc_vector() 참고.
ENC_OUTPUT = os.environ.get("DATAAPI_ENC_OUTPUT", "base64").lower()

_AES_KEY_SIZES = (16, 24, 32)


def _decode_material(value: str, sizes: tuple[int, ...]) -> bytes:
    """EncKey/EncIV 는 업체마다 원문·Base64·Hex 중 하나로 준다. 어느 쪽인지 명시가 없으므로
    'AES 가 받아들이는 길이가 되는 해석' 하나만 채택한다. 둘 이상 성립하면 모호하므로
    추측하지 않고 예외를 낸다 — 잘못 고르면 로그인 실패 ECODE 로만 보여 원인 추적이 어렵다."""
    import base64
    import binascii

    candidates: list[tuple[str, bytes]] = [("raw", value.encode("utf-8"))]
    try:
        candidates.append(("base64", base64.b64decode(value, validate=True)))
    except (binascii.Error, ValueError):
        pass
    try:
        candidates.append(("hex", bytes.fromhex(value)))
    except ValueError:
        pass

    matches = {name: b for name, b in candidates if len(b) in sizes}
    if not matches:
        raise ValueError(
            f"EncKey/EncIV 길이가 AES 규격({sizes})에 맞지 않습니다. "
            f"업체가 준 값의 인코딩을 확인하세요 (값 자체는 로그에 남기지 않습니다)."
        )
    if len(matches) > 1 and len({bytes(b) for b in matches.values()}) > 1:
        raise ValueError(
            f"EncKey/EncIV 해석이 모호합니다({', '.join(matches)}). "
            f"DATAAPI_ENC_* 를 한 가지 인코딩으로 통일해 주세요."
        )
    return next(iter(matches.values()))


def encrypt_password(
    raw_password: str,
    spec: str | None = None,
    key: str | None = None,
    iv: str | None = None,
    output: str | None = None,
) -> str:
    """LOGINPWD 를 업체 규격(EncSpec)으로 암호화해 전송용 문자열로 반환한다.

    ★ 절대규칙 5: 평문 비밀번호는 이 함수 밖으로 나가지 않는다. 예외 메시지에도 넣지 않는다.
    파라미터가 하나라도 비면 즉시 막는다 — 미설정 상태로 호출되면 평문이 그대로 나갈 위험이 있다.
    """
    from cryptography.hazmat.primitives import padding as sym_padding
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    spec = (spec if spec is not None else ENC_SPEC).strip()
    key = key if key is not None else ENC_KEY
    iv = iv if iv is not None else ENC_IV
    output = (output if output is not None else ENC_OUTPUT).lower()

    if not (spec and key):
        raise RuntimeError(
            "LOGINPWD 암호화 파라미터가 없습니다. DATAAPI_ENC_SPEC/DATAAPI_ENC_KEY"
            "(모드에 따라 DATAAPI_ENC_IV)를 .env 에 설정하세요 (절대규칙 5)."
        )

    # 구분자는 업체마다 다르다. 실수령 예: "AES_CBC_PKCS5PADDING/256" (언더스코어 + 키비트 접미사),
    # 자바 표기 예: "AES/CBC/PKCS5Padding". 둘 다 같은 토큰 목록으로 정규화한다.
    tokens = [t for t in re.split(r"[/_\-\s]+", spec.upper()) if t]
    # 끝에 붙는 키 길이(128/192/256)는 키 자체에서 이미 결정되므로 검증에만 쓴다.
    key_bits = next((int(t) for t in tokens if t.isdigit()), None)
    parts = [t for t in tokens if not t.isdigit()]

    algo = parts[0] if parts else ""
    mode_name = parts[1] if len(parts) > 1 else "CBC"
    padding_name = parts[2] if len(parts) > 2 else "PKCS5PADDING"

    if algo != "AES":
        raise NotImplementedError(f"미지원 알고리즘: {algo} (현재 AES 만 구현)")
    if padding_name not in ("PKCS5PADDING", "PKCS7PADDING", "PKCS5", "PKCS7"):
        raise NotImplementedError(f"미지원 패딩: {padding_name}")

    key_bytes = _decode_material(key, _AES_KEY_SIZES)
    if key_bits is not None and len(key_bytes) * 8 != key_bits:
        # 스펙이 말하는 키 길이와 실제 키가 다르면 조용히 넘어가지 않는다 — 여기서 어긋나면
        # 로그인 실패로만 보여서 원인을 찾을 수 없다.
        raise ValueError(
            f"EncSpec 은 {key_bits}비트 키를 요구하는데 EncKey 는 {len(key_bytes) * 8}비트로 해석됩니다."
        )
    if mode_name == "ECB":
        cipher_mode = modes.ECB()  # noqa: S305 — 업체 규격이 ECB 인 경우에만 사용
    elif mode_name == "CBC":
        if not iv:
            raise RuntimeError("CBC 모드에는 DATAAPI_ENC_IV 가 필요합니다.")
        cipher_mode = modes.CBC(_decode_material(iv, (16,)))
    else:
        raise NotImplementedError(f"미지원 모드: {mode_name}")

    # PKCS5 는 8바이트 블록 기준 명칭이지만 AES(16바이트 블록)에서는 PKCS7 과 동일하다.
    padder = sym_padding.PKCS7(algorithms.AES.block_size).padder()
    padded = padder.update(raw_password.encode("utf-8")) + padder.finalize()
    encryptor = Cipher(algorithms.AES(key_bytes), cipher_mode).encryptor()
    ciphertext = encryptor.update(padded) + encryptor.finalize()

    if output == "base64":
        import base64

        return base64.b64encode(ciphertext).decode("ascii")
    if output == "hex":
        return ciphertext.hex()
    raise NotImplementedError(f"미지원 출력 인코딩: {output} (base64|hex)")


def verify_enc_vector(plain: str, expected: str) -> dict:
    """업체가 준 샘플(PlainData → EncData)로 암호화 설정이 맞는지 검증한다.

    실호출 전에 반드시 통과시킨다. 여기서 틀리면 DataAPI 는 '로그인 실패'(ERR_MLCOM_MSG50059)
    로만 답하기 때문에, 비밀번호가 틀린 건지 암호화가 틀린 건지 구분할 수 없다.
    반환값에는 평문·키를 담지 않는다."""
    results = {}
    for out in ("base64", "hex"):
        try:
            results[out] = encrypt_password(plain, output=out) == expected
        except Exception as exc:  # 설정 오류를 그대로 보여준다(값은 포함되지 않음)
            results[out] = f"ERROR: {exc}"
    matched = [k for k, v in results.items() if v is True]
    return {
        "spec": ENC_SPEC,
        "matched_output_encoding": matched[0] if matched else None,
        "ok": bool(matched),
        "detail": results,
    }


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
        return self._post("reviewManagement", url, payload)

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
        return self._post("CreateComment", url, payload)

    def _post(self, endpoint: str, url: str, payload: dict) -> dict:
        """모든 DataAPI 호출이 지나는 단 하나의 지점.

        ★ 여기 말고 다른 곳에서 httpx 로 DataAPI 를 부르지 말 것. 우회로가 하나라도 생기면
          예산 게이트가 무의미해지고, 어디서 호출이 새는지 추적할 수 없게 된다.
        ★ 예산 예약을 호출 '앞'에 둔다. 뒤에 두면 이미 과금된 호출을 세지 못한다.
        ★ payload 에는 LOGINPWD 가 들어 있다 — 절대 로깅하지 않는다(절대규칙 5).
        """
        budget.reserve(endpoint)
        resp = httpx.post(url, json=payload, headers=self._headers, timeout=self._timeout)
        resp.raise_for_status()
        return parse_envelope(resp.json())
