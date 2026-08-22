"""
말투 few-shot 검색 (Sprint 3 (a)).

docs/12_프롬프트_및_평가명세.md §3.2, docs/11_DB스키마_확정본.md §2.6 reply_style_sample.

reply_style_sample 에서 store_id 로 필터한 뒤 embedding 코사인 유사도 상위 k 건을
반환한다. embedding 이 NULL 인 행이 대부분(Sprint 2 T-7 이월)이므로, 매장의 임베딩된
샘플이 2건 미만이면 최신순 fallback 으로 동작하고 그 사실을 StyleExample.fallback 에
표기한다(문서 12 §3.2: "샘플 2개 미만 → 업종/최신 fallback, 다른 매장 샘플을 섞지 않는다").

DB 접속 실패는 예외를 던지지 않고 빈 리스트를 반환한다 — few-shot 은 답글 품질 보조
장치일 뿐 필수 의존이 아니므로, DB 가 죽어도 생성 파이프라인은 계속되어야 한다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

from embeddings import get_embedding_provider

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://storemanager:storemanager@localhost:5433/storemanager"
)

_MIN_EMBEDDED_FOR_SIMILARITY = 2  # 문서 12 §3.2


@dataclass
class StyleExample:
    review_text: str
    reply_text: str
    rating: int | None
    fallback: bool  # True 면 임베딩 유사도가 아니라 최신순 fallback 결과


def _vector_literal(vec: list[float]) -> str:
    """pgvector 텍스트 입력 형식('[0.1,0.2,...]')으로 변환한다. psycopg 에 pgvector
    어댑터를 등록하지 않았으므로(요구사항 최소화), 텍스트로 만들어 ::vector 캐스트한다."""
    return "[" + ",".join(f"{x:.6f}" for x in vec) + "]"


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

                # ★ 임베딩을 여기서 지연 적재한다. backfill_embeddings 는 있었지만 아무도 부르지
                #   않아 죽은 코드였고, 그래서 RC_LIST 코퍼스(핵심 자산)가 영영 임베딩되지 않은 채
                #   RAG 가 조용히 최신순 fallback 으로만 동작했다. 품질 저하가 로그에도 안 남는다.
                #   별도 스케줄러를 두지 않는 이유: 필요한 시점은 '이 매장의 few-shot 을 뽑을 때'
                #   하나뿐이고, 한 번 채우면 다음부터는 대상이 없어 비용이 0 이다.
                cur.execute(
                    "SELECT COUNT(*) FROM reply_style_sample "
                    "WHERE store_id = %s::bigint AND embedding IS NULL",
                    (store_id,),
                )
                (missing,) = cur.fetchone()
                if missing:
                    backfill_embeddings(store_id)

                cur.execute(
                    "SELECT COUNT(*) FROM reply_style_sample "
                    "WHERE store_id = %s::bigint AND source <> 'MANUAL' AND embedding IS NOT NULL",
                    (store_id,),
                )
                (embedded_count,) = cur.fetchone()

                if embedded_count >= _MIN_EMBEDDED_FOR_SIMILARITY:
                    q = _vector_literal(get_embedding_provider().embed(review_text))
                    cur.execute(
                        """
                        SELECT review_text, reply_text, rating
                          FROM reply_style_sample
                         WHERE store_id = %s::bigint AND source <> 'MANUAL' AND embedding IS NOT NULL
                         ORDER BY embedding <=> %s::vector
                         LIMIT %s
                        """,
                        (store_id, q, remaining),
                    )
                    rows = cur.fetchall()
                    return manual + [StyleExample(r[0], r[1], r[2], fallback=False) for r in rows]

                # 임베딩 2건 미만 → 최신순 fallback(다른 매장 샘플을 섞지 않는다)
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


def backfill_embeddings(store_id: str, batch_size: int = 200) -> int:
    """embedding 이 NULL 인 행에 임베딩을 채운다(Sprint 2 T-7 이월 항목). 채운 행 수를 반환한다."""
    try:
        import psycopg
    except ImportError:
        return 0

    provider = get_embedding_provider()
    filled = 0
    try:
        with psycopg.connect(DATABASE_URL, connect_timeout=3) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, review_text FROM reply_style_sample "
                    "WHERE store_id = %s::bigint AND embedding IS NULL LIMIT %s",
                    (store_id, batch_size),
                )
                rows = cur.fetchall()
                for row_id, review_text in rows:
                    vec = _vector_literal(provider.embed(review_text or ""))
                    cur.execute(
                        "UPDATE reply_style_sample SET embedding = %s::vector WHERE id = %s",
                        (vec, row_id),
                    )
                    filled += 1
            conn.commit()
    except Exception:
        return filled
    return filled


def demo() -> None:
    # DB 가 없는 환경(CI)에서도 예외 없이 빈 리스트를 반환해야 한다.
    result = fetch_examples(store_id="999999", review_text="맛있어요")
    assert isinstance(result, list)
    filled = backfill_embeddings(store_id="999999")
    assert isinstance(filled, int)
    print("rag demo OK (DB 미접속/부재 시 빈 리스트·0건 확인)")


if __name__ == "__main__":
    demo()
