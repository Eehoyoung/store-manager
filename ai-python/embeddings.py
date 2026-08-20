"""
임베딩 추상화 (Sprint 3 (a)).

docs/11_DB스키마_확정본.md §2.6 reply_style_sample.embedding 은 vector(1024) 이므로
항상 1024차원을 반환한다.

기본 구현은 표준 라이브러리(hashlib)만 쓰는 결정적 해시 임베딩이다 — 네트워크 호출이
없어 테스트·오프라인 환경에서 항상 동작하지만, 의미 유사도를 보장하지 않는다
(실 서비스 few-shot 품질에는 부적합).

TODO: Voyage AI voyage-3(1024차원) 연동, API 키 발급 후.
  Anthropic 은 임베딩 API 를 제공하지 않으므로 별도 provider 가 필요하다.
"""
from __future__ import annotations

import hashlib
import math
from typing import Protocol

DIM = 1024


class EmbeddingProvider(Protocol):
    def embed(self, text: str) -> list[float]: ...


class HashEmbeddingProvider:
    """SHA-256 반복 확장으로 1024차원을 채우는 결정적 해시 임베딩. 의미 유사도 없음
    — 테스트/오프라인 전용. rag.py 의 기본 provider."""

    def embed(self, text: str) -> list[float]:
        data = (text or "").encode("utf-8")
        vec = [0.0] * DIM
        block = hashlib.sha256(data).digest()
        i = 0
        while i < DIM:
            for byte in block:
                if i >= DIM:
                    break
                vec[i] = (byte / 255.0) * 2 - 1  # [-1, 1] 범위로 정규화
                i += 1
            block = hashlib.sha256(block).digest()
        norm = math.sqrt(sum(x * x for x in vec)) or 1.0
        return [x / norm for x in vec]


# TODO: Voyage AI voyage-3(1024차원) 연동, API 키 발급 후.
# class VoyageEmbeddingProvider:
#     def __init__(self) -> None:
#         import voyageai
#         self.client = voyageai.Client()  # VOYAGE_API_KEY 환경변수
#
#     def embed(self, text: str) -> list[float]:
#         return self.client.embed([text], model="voyage-3", output_dimension=1024).embeddings[0]


def get_embedding_provider() -> EmbeddingProvider:
    return HashEmbeddingProvider()


def demo() -> None:
    p = HashEmbeddingProvider()
    v1 = p.embed("맛있어요")
    v2 = p.embed("맛있어요")
    v3 = p.embed("완전 별로였어요")
    v4 = p.embed("")
    assert len(v1) == DIM
    assert len(v4) == DIM
    assert v1 == v2  # 결정적
    assert v1 != v3
    print("embeddings demo OK")


if __name__ == "__main__":
    demo()
