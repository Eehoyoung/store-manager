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
    sample_type: str | None = None  # MANUAL 슬롯 유형 THANKS|APOLOGY|GENERAL


def fetch_examples(
    store_id: str, review_text: str, k: int = 4,
    category: str | None = None, issue_tags: list[str] | None = None,
    slot_wanted: str | None = None,
) -> list[StyleExample]:
    """해당 매장의 말투 few-shot 예시를 최대 k 건 반환한다. 실패 시 빈 리스트.

    ★ 고르는 순서 (2026-09-17 학습 루프)
      1. MANUAL   — 사장님이 설정 화면에 직접 적은 형식. 최대 3건, 무조건 우선.
      2. EDITED   — 사장님이 AI 초안을 **고쳐서** 올린 답글. 이 시스템에서 가장 강한 신호다.
      3. 겹침 순  — 같은 이슈 태그가 많이 겹치는 것, 그다음 같은 카테고리.
      4. 최신순   — 위 단서가 전부 없을 때의 폴백(RC_LIST 는 태그가 없다).

    ★ 왜 pgvector 를 안 쓰나: embedding 이 아직 결정론적 해시라 의미 유사도가 없다(T-15).
      그걸로 유사도 검색을 하면 무작위와 다를 바 없다. 의미 임베딩으로 바꾸기 전까지는
      **이미 계산해 둔 분류 축**(category·issue_tags)이 더 나은 신호다 — 추가 비용도 0이다.
    """
    try:
        import psycopg
    except ImportError:
        return []

    try:
        with psycopg.connect(DATABASE_URL, connect_timeout=3) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT review_text, reply_text, rating, sample_type FROM reply_style_sample "
                    "WHERE store_id = %s::bigint AND source = 'MANUAL' LIMIT 3",
                    (store_id,),
                )
                rows = cur.fetchall()
                # ★ 이 리뷰에 맞는 슬롯을 앞에 둔다. 예전에는 최신순이라, 감사 형식만 적어 둔
                #   매장에서는 불만 리뷰에도 감사 문체가 첫 예시로 붙었다.
                want = slot_wanted or "GENERAL"
                order = {want: 0, "GENERAL": 1}
                rows.sort(key=lambda r: order.get(r[3], 2))
                manual = [StyleExample(r[0], r[1], r[2], fallback=True, sample_type=r[3]) for r in rows]
                remaining = max(0, k - len(manual))
                if remaining == 0:
                    return manual

                tags = [t for t in (issue_tags or []) if t]
                cur.execute(
                    """
                    SELECT review_text, reply_text, rating
                      FROM reply_style_sample
                     WHERE store_id = %s::bigint AND source <> 'MANUAL'
                     ORDER BY (source = 'EDITED') DESC,
                              cardinality(ARRAY(SELECT unnest(issue_tags)
                                                INTERSECT SELECT unnest(%s::text[]))) DESC,
                              (category IS NOT DISTINCT FROM %s) DESC,
                              created_at DESC
                     LIMIT %s
                    """,
                    (store_id, tags, category, remaining),
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
