"""DataAPI 실연동 점검 도구 (T-1 게이트).

수동 실행 전용이다. CI 에서 돌리지 말 것 — 호출당 과금되고 실매장 데이터가 걸린다.

사용법:
    python smoke_dataapi.py calibrate            # 업체 샘플로 암호화 설정 확정
    python smoke_dataapi.py fetch baemin         # 리뷰 조회 1회 (읽기 전용)

★ 이 스크립트는 비밀번호를 인자로 받지 않는다. getpass 로만 입력받아 셸 기록·프로세스 목록에
  남지 않게 한다(절대규칙 5). 출력에도 평문·암호문·토큰을 찍지 않는다.
★ 댓글 등록은 여기에 없다. 되돌릴 수 없는 작업이라 별도 확인 절차를 거친다.
"""
from __future__ import annotations

import getpass
import json
import os
import sys
from datetime import date, timedelta
from pathlib import Path

import dataapi


# Windows 콘솔 기본 코드페이지(cp949)로는 '—'·'★' 가 인코딩되지 않아 스크립트가 죽는다.
# 안내문 하나 때문에 점검이 중단되면 안 되므로 출력 스트림을 UTF-8 로 바꾼다.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def _load_dotenv() -> None:
    """루트 .env 를 읽어 환경변수로 올린다(이미 설정된 값은 덮지 않는다)."""
    env_path = Path(__file__).resolve().parent.parent / ".env"
    if not env_path.exists():
        print(f"[!] {env_path} 가 없습니다. 환경변수로 직접 주입해도 됩니다.")
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    # 모듈 상수는 임포트 시점에 굳으므로 다시 읽어 넣는다.
    dataapi.ENC_SPEC = os.environ.get("DATAAPI_ENC_SPEC", "")
    dataapi.ENC_KEY = os.environ.get("DATAAPI_ENC_KEY", "")
    dataapi.ENC_IV = os.environ.get("DATAAPI_ENC_IV", "")
    dataapi.ENC_OUTPUT = os.environ.get("DATAAPI_ENC_OUTPUT", "base64").lower()
    dataapi.BASE_URL = os.environ.get("DATAAPI_BASE_URL", dataapi.BASE_URL)
    dataapi.TOKEN = os.environ.get("DATAAPI_TOKEN", "")


def _mask(value: str) -> str:
    """설정이 '들어는 갔는지'만 확인시켜 준다. 값 자체는 절대 찍지 않는다."""
    return f"설정됨({len(value)}자)" if value else "비어 있음"


def cmd_calibrate() -> int:
    """업체가 준 PlainData → EncData 샘플로 EncSpec/EncKey/EncIV 해석을 확정한다.

    여기를 통과하지 못한 채 실호출하면 DataAPI 는 로그인 실패(ERR_MLCOM_MSG50059)로만 답한다.
    비밀번호가 틀린 건지 암호화가 틀린 건지 구분할 수 없으므로 반드시 먼저 맞춘다."""
    print(f"  EncSpec : {dataapi.ENC_SPEC or '(비어 있음)'}")
    print(f"  EncKey  : {_mask(dataapi.ENC_KEY)}")
    print(f"  EncIV   : {_mask(dataapi.ENC_IV)}")

    plain = os.environ.get("DATAAPI_ENC_SAMPLE_PLAIN") or input("업체 샘플 PlainData: ").strip()
    expected = os.environ.get("DATAAPI_ENC_SAMPLE_ENC") or input("업체 샘플 EncData  : ").strip()

    result = dataapi.verify_enc_vector(plain, expected)
    if result["ok"]:
        enc = result["matched_output_encoding"]
        print(f"\n[OK] 샘플이 일치합니다. 출력 인코딩 = {enc}")
        if enc != "base64":
            print(f"     .env 에 DATAAPI_ENC_OUTPUT={enc} 를 추가하세요.")
        return 0

    print("\n[실패] 어떤 조합으로도 샘플과 일치하지 않았습니다.")
    print(json.dumps(result["detail"], ensure_ascii=False, indent=2))
    print(
        "\n확인할 것:\n"
        "  - EncKey/EncIV 를 업체가 준 문자열 그대로 넣었는가(앞뒤 공백·줄바꿈 제거)\n"
        "  - EncSpec 이 AES/CBC/PKCS5Padding 형태인가(다른 모드면 알려주세요)\n"
        "  - PlainData 에 눈에 안 보이는 공백이 붙지 않았는가"
    )
    return 1


def cmd_fetch(platform: str, days: int = 2) -> int:
    """리뷰 조회 1회. 읽기 전용이라 실매장에 아무 흔적도 남기지 않는다."""
    if platform not in ("baemin", "yogiyo", "coupangeats"):
        print(f"[실패] 알 수 없는 플랫폼: {platform}")
        return 2
    if not dataapi.TOKEN:
        print("[실패] DATAAPI_TOKEN 이 비어 있습니다.")
        return 2

    print(f"  BaseURL : {dataapi.BASE_URL}")
    print(f"  Token   : {_mask(dataapi.TOKEN)}")
    if "datahub-dev" not in dataapi.BASE_URL:
        print("\n[!] 개발계(datahub-dev)가 아닙니다. 운영계로 호출하려는 게 맞습니까?")
        if input("    계속하려면 'yes' 입력: ").strip() != "yes":
            return 3

    login_id = input(f"{platform} 로그인 ID: ").strip()
    login_pwd = getpass.getpass(f"{platform} 비밀번호(화면에 표시되지 않음): ")

    try:
        encrypted = dataapi.encrypt_password(login_pwd)
    finally:
        del login_pwd  # 평문을 필요 이상으로 메모리에 두지 않는다

    end = date.today()
    start = end - timedelta(days=days)
    creds = dataapi.Credentials(login_id=login_id, login_pwd_encrypted=encrypted)

    print(f"\n조회 중… {start:%Y%m%d} ~ {end:%Y%m%d}")
    try:
        data = dataapi.DataApiClient().fetch_reviews(
            platform, creds, f"{start:%Y%m%d}", f"{end:%Y%m%d}"
        )
    except dataapi.DataApiError as exc:
        print(f"\n[실패] ECODE={exc.ecode} 재시도가능={exc.retryable}")
        print(f"       ERRMSG={exc.errmsg}")
        print(f"       후속조치={dataapi.ecode_action(exc.ecode)}")
        if exc.ecode == dataapi.ECODE_LOGIN_FAIL:
            print("\n로그인 실패입니다. 비밀번호 자체 / 암호화 설정 둘 다 가능성이 있으니")
            print("먼저 `python smoke_dataapi.py calibrate` 로 암호화를 확정하세요.")
        return 1

    reviews = data.get("REVIEWLIST") or []
    print(f"\n[OK] RESULT=SUCCESS · REVIEWLIST {len(reviews)}건")

    # ★ 리뷰 원문·닉네임은 개인정보다. 화면에는 구조만 보여주고 값은 파일로만 남긴다.
    keys = sorted({k for r in reviews if isinstance(r, dict) for k in r})
    print(f"     필드: {', '.join(keys) if keys else '(없음)'}")
    store_ids = {r.get("STOREID") for r in reviews if isinstance(r, dict)}
    print(f"     STOREID 종류: {len(store_ids)}개 (1계정 N매장 확인용)")

    out = Path(__file__).resolve().parent / f"fixture_{platform}_review.json"
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n픽스처 저장: {out}")
    print("★ 이 파일에는 실제 리뷰·닉네임이 들어 있습니다. 커밋 전 반드시 가명처리하세요.")
    return 0


def main() -> int:
    _load_dotenv()
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "calibrate":
        return cmd_calibrate()
    if args[0] == "fetch":
        return cmd_fetch(args[1] if len(args) > 1 else "baemin")
    print(f"알 수 없는 명령: {args[0]}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
