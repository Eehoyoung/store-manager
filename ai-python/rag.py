"""
말투 few-shot 검색 (Sprint 3 (a)).

docs/12_프롬프트_및_평가명세.md §3.2, docs/11_DB스키마_확정본.md §2.6 reply_style_sample.

reply_style_sample 에서 같은 매장의 수동 예시를 우선하고 나머지는 최신순으로 반환한다.
현재 해시 임베딩은 의미 유사도를 보장하지 않으므로 운영 요청 경로에서 사용하지 않는다.

DB 접속 실패는 예외를 던지지 않고 빈 리스트를 반환한다 — few-shot 은 답글 품질 보조
장치일 뿐 필수 의존이 아니므로, DB 가 죽어도 생성 파이프라인은 계속되어야 한다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://storemanager:storemanager@localhost:5433/storemanager"
)

@dataclass
class StyleExample:
    review_text: str
    reply_text: str
    rating: int | None
    fallback: bool  # True 면 임베딩 유사도가 아니라 최신순 fallback 결과


def fetch_examples(store_id: str, review_text: str, k: int = 4) -> list[StyleExample]:
    """해당 매장의 말투 few-shot 예시를 최대 k 건 반환한다. 실패 시 빈 리스트."""
    try:
        import psycopg
    except ImportError:
        return []

    try:
        with psycopg.connect(DATABASE_URL, connect_timeout=3) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT review_text, reply_text, rating FROM reply_style_sample "
                    "WHERE store_id = %s::bigint AND source = 'MANUAL' ORDER BY created_at DESC LIMIT 3",
                    (store_id,),
                )
                manual = [StyleExample(r[0], r[1], r[2], fallback=True) for r in cur.fetchall()]
                remaining = max(0, k - len(manual))
                if remaining == 0:
                    return manual

                cur.execute(
                    """
                    SELECT review_text, reply_text, rating
                      FROM reply_style_sample
                     WHERE store_id = %s::bigint AND source <> 'MANUAL'
                     ORDER BY created_at DESC
                     LIMIT %s
                    """,
                    (store_id, remaining),
                )
                rows = cur.fetchall()
                return manual + [StyleExample(r[0], r[1], r[2], fallback=True) for r in rows]
    except Exception:
        # DB 접속 실패·캐스트 실패 등 — few-shot 은 필수 의존이 아니므로 파이프라인을 막지 않는다
        return []


def demo() -> None:
    # DB 가 없는 환경(CI)에서도 예외 없이 빈 리스트를 반환해야 한다.
    result = fetch_examples(store_id="999999", review_text="맛있어요")
    assert isinstance(result, list)
    print("rag demo OK (DB 미접속/부재 시 빈 리스트 확인)")


if __name__ == "__main__":
    demo()
