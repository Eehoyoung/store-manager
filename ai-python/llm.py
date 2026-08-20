"""
LLM 호출 추상화 (Sprint 3 (a)).

CLAUDE.md LLM 정책의 T1~T3 티어가 실제 모델을 호출할 때 이 모듈을 거친다
(T0 는 룰 템플릿이라 LLM 호출이 없다 — prompts.render_t0_template 참고).

★ ANTHROPIC_API_KEY 가 없으면 자동으로 StubProvider 로 폴백한다. 키가 없는 환경
  (CI 포함)에서도 전체 파이프라인이 돌아가야 하기 때문이다. 폴백 여부는
  LlmResult.model == "stub" 로 드러난다.

공식 SDK(anthropic)만 사용한다. OpenAI 호환 shim 은 쓰지 않는다.
"""
from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from typing import Protocol

try:
    import anthropic
except ImportError:  # requirements 미설치 환경 대비 — StubProvider 로만 동작
    anthropic = None  # type: ignore[assignment]

# 환율 고정값(원/USD). ponytail: 정산 배치가 붙기 전까지의 근사치이며, 실제 정산
# 시점의 환율로 갱신해야 한다. 실시간 환율 API 연동은 지금 필요하지 않다(YAGNI).
USD_KRW = 1400

# 모델별 (입력 $/1M토큰, 출력 $/1M토큰). Sprint 3 지시로 확정된 단가.
# ★ 모델 ID 에 날짜 접미사를 붙이지 않는다.
PRICING_USD_PER_MTOK: dict[str, tuple[float, float]] = {
    "claude-haiku-4-5": (1.0, 5.0),
    "claude-sonnet-5": (3.0, 15.0),
    "claude-opus-5": (5.0, 25.0),
}


def cost_krw(model: str, token_in: int, token_out: int) -> float:
    """모델·토큰 사용량으로 원화 원가를 계산한다. 단가표에 없는 모델(rule-template, stub 등)은 0원."""
    price = PRICING_USD_PER_MTOK.get(model)
    if price is None:
        return 0.0
    in_usd_per_mtok, out_usd_per_mtok = price
    usd = (token_in / 1_000_000) * in_usd_per_mtok + (token_out / 1_000_000) * out_usd_per_mtok
    return round(usd * USD_KRW, 4)


@dataclass
class LlmResult:
    text: str
    model: str
    token_in: int
    token_out: int
    cost_krw: float


class LlmProvider(Protocol):
    """생성(complete) 전용 인터페이스. 분류는 구조화 출력(client.messages.parse)이 필요해
    main.py 에서 AnthropicProvider.client 를 직접 사용한다(StubProvider.client 는 None)."""

    client: object | None  # anthropic.Anthropic 인스턴스 또는 None(스텁)

    def complete(self, system: str, user: str, model: str, max_tokens: int) -> LlmResult: ...


class StubProvider:
    """네트워크 호출 없는 결정적 스텁. ANTHROPIC_API_KEY 가 없는 환경(CI 포함)의 기본값."""

    client = None

    def complete(self, system: str, user: str, model: str, max_tokens: int) -> LlmResult:
        text = _deterministic_text(system, user)
        return LlmResult(
            text=text,
            model="stub",
            token_in=len(system) + len(user),
            token_out=len(text),
            cost_krw=0.0,
        )


# guardrails.MIN_LENGTH(60자) 이상을 항상 만족하도록 여유 있게 맞춘 결정적 문구 5종
# (persona_seed 없이도 system+user 프롬프트 내용에 따라 자연스럽게 갈리도록 해시로 선택한다).
_STUB_TEMPLATES = [
    "고객님, 소중한 후기 남겨주셔서 진심으로 감사드립니다. 앞으로도 한결같은 맛과 정성으로 정성껏 보답하도록 늘 노력하겠습니다.",
    "안녕하세요 고객님, 리뷰 남겨주셔서 감사합니다. 다음 방문에도 만족하실 수 있도록 재료 하나하나 정성껏 준비하겠습니다.",
    "찾아주시고 후기까지 남겨주셔서 진심으로 감사드립니다. 앞으로도 최선을 다해 더 좋은 맛과 서비스로 보답해 드리겠습니다.",
    "소중한 의견 감사드립니다. 말씀 주신 부분을 꼼꼼히 살펴보고 다음번에는 더 나은 모습으로 꼭 보답하도록 하겠습니다.",
    "귀한 시간 내어 리뷰 남겨주셔서 정말 감사합니다. 늘 한결같은 정성을 다하는 매장이 되도록 계속 노력하겠습니다.",
]


def _deterministic_text(system: str, user: str) -> str:
    """system+user 해시로 5종 문구 중 하나를 결정적으로 고른다."""
    digest = hashlib.sha256((system + user).encode("utf-8")).hexdigest()
    idx = int(digest, 16) % len(_STUB_TEMPLATES)
    return _STUB_TEMPLATES[idx]


class AnthropicProvider:
    """공식 anthropic SDK 사용. ANTHROPIC_API_KEY 는 SDK 가 환경변수에서 자동 해석한다
    (하드코딩 금지)."""

    def __init__(self) -> None:
        assert anthropic is not None, "anthropic SDK 가 설치되어 있지 않다"
        self.client = anthropic.Anthropic()

    def complete(self, system: str, user: str, model: str, max_tokens: int) -> LlmResult:
        # 캐싱: 시스템 프롬프트 접두부에 ephemeral 캐시를 건다. 최소 캐시 길이(~1024토큰)
        # 미만이면 캐시가 걸리지 않는다 — 짧은 시스템 프롬프트에서는 무해하게 무시될 뿐이다.
        resp = self.client.messages.create(
            model=model,
            max_tokens=max_tokens,
            system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": user}],
        )
        text = next((b.text for b in resp.content if b.type == "text"), "")
        token_in = resp.usage.input_tokens
        token_out = resp.usage.output_tokens
        return LlmResult(
            text=text,
            model=model,
            token_in=token_in,
            token_out=token_out,
            cost_krw=cost_krw(model, token_in, token_out),
        )


def get_provider() -> LlmProvider:
    """ANTHROPIC_API_KEY 가 있고 SDK 가 설치되어 있으면 AnthropicProvider, 아니면 StubProvider."""
    if anthropic is not None and os.environ.get("ANTHROPIC_API_KEY"):
        return AnthropicProvider()
    return StubProvider()


def demo() -> None:
    stub = StubProvider()
    r = stub.complete("sys", "user", "claude-haiku-4-5", 100)
    assert r.model == "stub" and r.cost_krw == 0.0 and r.text
    assert len(r.text) >= 60  # guardrails.MIN_LENGTH 를 항상 만족해야 한다
    assert stub.client is None

    # 동일 입력 → 동일 출력(결정적)
    r2 = stub.complete("sys", "user", "claude-haiku-4-5", 100)
    assert r.text == r2.text

    assert cost_krw("claude-haiku-4-5", 1_000_000, 1_000_000) == round((1.0 + 5.0) * USD_KRW, 4)
    assert cost_krw("claude-sonnet-5", 1_000_000, 1_000_000) == round((3.0 + 15.0) * USD_KRW, 4)
    assert cost_krw("claude-opus-5", 1_000_000, 1_000_000) == round((5.0 + 25.0) * USD_KRW, 4)
    assert cost_krw("stub", 100, 100) == 0.0
    assert cost_krw("rule-template", 100, 100) == 0.0

    provider = get_provider()
    assert isinstance(provider, (AnthropicProvider, StubProvider))
    print("llm demo OK")


if __name__ == "__main__":
    demo()
