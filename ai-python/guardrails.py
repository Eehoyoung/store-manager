"""
답글 가드레일 최소 구현.

★ 절대규칙 3(CLAUDE.md): risk_level >= 3 이면 무조건 사람 검수 큐로 보낸다 (자동 게시 금지).
★ 절대규칙 4(CLAUDE.md): 답글에 금전적 보상 약속을 생성하지 않는다.

생성된 답글은 반드시 check() 를 통과해야 저장된다 (문서 12 §4). 여기서는 문서 12 의
가드레일 9종 중 Sprint 1 스켈레톤에 필요한 부분집합만 구현한다:
  G1 길이, G3 보상 약속, G4 개인정보(전화번호), G5 경쟁 플랫폼, G8 위험도.
G2(금칙어 테이블)·G6(리뷰 인용)·G7(중복 유사도)·G9(인젝션 사후검증)는
DB/임베딩/LLM 결과가 필요하므로 Sprint 3 로 남긴다.
"""
import re

MAX_LENGTH = 280  # CLAUDE.md #8: 플랫폼 300자 제한보다 여유를 둔 하드 제한
HIGH_RISK_THRESHOLD = 3  # CLAUDE.md 절대규칙 3

# 문서 12 §4 G3 정규식 그대로
_COMPENSATION_PATTERNS = [
    re.compile(r"(환불|보상|배상|변상)(해|드리|하겠|처리)"),
    re.compile(r"(무료|서비스|덤|추가)(로\s*)?(드리|제공|보내|넣)"),
    re.compile(r"(할인|쿠폰|적립|포인트)(해\s*)?(드리|제공|발행)"),
    re.compile(r"(다시|재)\s*(보내|배송|조리)(해\s*)?드리"),
]

_COMPETITOR_PATTERN = re.compile(r"배달의\s*민족|배민|요기요|쿠팡\s*이츠|쿠팡이츠|땡겨요")

# 010-1234-5678 / 01012345678 / 02-123-4567 등 국내 전화번호 형태
_PHONE_PATTERN = re.compile(r"(01[0-9]|02|0[3-9][0-9])[-\s.]?\d{3,4}[-\s.]?\d{4}")


def check(text: str, risk_level: int) -> list[str]:
    """답글 텍스트와 위험도를 검사해 위반한 가드레일 플래그 목록을 반환한다.
    빈 리스트면 통과. 하나라도 있으면 BLOCKED (문서 12 §4)."""
    flags: list[str] = []

    if not text or len(text) > MAX_LENGTH:
        flags.append("G1_LENGTH")

    if any(p.search(text) for p in _COMPENSATION_PATTERNS):
        flags.append("G3_COMPENSATION")

    if _PHONE_PATTERN.search(text):
        flags.append("G4_PHONE")

    if _COMPETITOR_PATTERN.search(text):
        flags.append("G5_COMPETITOR")

    if risk_level >= HIGH_RISK_THRESHOLD:
        flags.append("G8_RISK")

    return flags


def demo() -> None:
    """수동 실행용 자가 점검 (pytest 대신 python guardrails.py 로도 확인 가능)."""
    assert check("고객님 감사합니다 :)", risk_level=0) == []
    assert "G1_LENGTH" in check("a" * 281, risk_level=0)
    assert "G3_COMPENSATION" in check("불편을 드려 환불해 드리겠습니다", risk_level=0)
    assert "G5_COMPETITOR" in check("요기요에서도 자주 찾아주세요", risk_level=0)
    assert "G4_PHONE" in check("010-1234-5678 로 연락주세요", risk_level=0)
    assert "G8_RISK" in check("죄송합니다", risk_level=3)
    assert "G8_RISK" not in check("죄송합니다", risk_level=2)
    print("guardrails demo OK")


if __name__ == "__main__":
    demo()
