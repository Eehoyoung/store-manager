"""
AI Service (FastAPI) — 진입점.

★ 절대규칙 1(CLAUDE.md): 이 서비스는 "답글"만 생성한다. 리뷰 본문을 생성/변형하는
  엔드포인트·함수는 어떤 형태로도 추가하지 않는다.

이 파일이 구현하는 엔드포인트는 CLAUDE.md 가 허용하는 서비스 간 경계 중 하나인
`POST /internal/ai/analyze-and-draft` (Spring → Python) 뿐이다. 요청/응답 스키마는
docs/13_내부API명세.md §11.1 의 JSON 예시와 정확히 일치한다(camelCase alias).

파이프라인(Sprint 3 (a)): 분류(구조화 출력) → risk_level 키워드 룰 승격 → 라우팅(T0~T3)
→ RAG few-shot 조회 → 답글 생성 → guardrails.check() → 통과분만 반환.
"""
from __future__ import annotations

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field

import guardrails
import llm
import prompts
import rag
import router

app = FastAPI(title="ai-python")


# ── 요청 스키마 (docs/13 §11.1) ──────────────────────────────────────────
class ReviewIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    rating: int
    body: str = ""
    menus: list[str] = Field(default_factory=list)
    platform: str


class PersonaIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    tone: str = "FRIENDLY"
    use_emoji: bool = Field(default=True, alias="useEmoji")
    emoji_level: int = Field(default=1, alias="emojiLevel")
    customer_title: str = Field(default="고객님", alias="customerTitle")
    signature: str | None = None
    banned_words: list[str] = Field(default_factory=list, alias="bannedWords")
    length_min: int = Field(default=60, alias="lengthMin")
    length_max: int = Field(default=150, alias="lengthMax")
    persona_seed: int | None = Field(default=None, alias="personaSeed")


class OptionsIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    variants: int = 1
    instruction: str | None = None
    force_tier: str | None = Field(default=None, alias="forceTier")


class AnalyzeAndDraftRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    review_id: str = Field(alias="reviewId")
    store_id: str = Field(alias="storeId")
    review: ReviewIn
    persona: PersonaIn
    options: OptionsIn = Field(default_factory=OptionsIn)


# ── 응답 스키마 (docs/13 §11.1) ──────────────────────────────────────────
class AnalysisOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    category: str
    sentiment: float
    issue_tags: list[str] = Field(default_factory=list, alias="issueTags")
    risk_level: int = Field(alias="riskLevel")
    risk_reasons: list[str] = Field(default_factory=list, alias="riskReasons")
    model: str
    prompt_version: str = Field(alias="promptVersion")


class DraftOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    content: str
    tier: str
    model: str
    prompt_version: str = Field(alias="promptVersion")
    guardrail_flags: list[str] = Field(default_factory=list, alias="guardrailFlags")
    similarity_max: float | None = Field(default=None, alias="similarityMax")
    token_in: int = Field(alias="tokenIn")
    token_out: int = Field(alias="tokenOut")
    cost_krw: float = Field(alias="costKrw")


class AnalyzeAndDraftResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    analysis: AnalysisOut
    drafts: list[DraftOut]
    blocked: bool
    block_reasons: list[str] = Field(default_factory=list, alias="blockReasons")


# ── 분류 ────────────────────────────────────────────────────────────────
def _stub_classify(review: ReviewIn) -> prompts.ClassifyOutput:
    """ANTHROPIC_API_KEY 가 없는 환경(CI 포함)용 rating 기반 결정적 분류.
    기존 스텁과 동일한 계약을 유지해 회귀를 막는다."""
    if review.rating >= 4:
        return prompts.ClassifyOutput(
            category="POSITIVE", sentiment=1.0 if review.rating == 5 else 0.6,
            issue_tags=[], risk_level=0, risk_reasons=[],
        )
    if review.rating == 3:
        return prompts.ClassifyOutput(category="IMPROVEMENT", sentiment=0.0, issue_tags=[], risk_level=0, risk_reasons=[])
    return prompts.ClassifyOutput(
        category="COMPLAINT", sentiment=-0.6 if review.rating == 2 else -1.0,
        issue_tags=[], risk_level=1, risk_reasons=[],
    )


def _classify(provider: llm.LlmProvider, review: ReviewIn) -> tuple[prompts.ClassifyOutput, str, int, int, float]:
    """(분류결과, 사용모델, token_in, token_out, cost_krw) 를 반환한다."""
    if not review.body.strip():
        return prompts.ClassifyOutput(category="NOISE", sentiment=0.0, issue_tags=[], risk_level=0, risk_reasons=[]), "rule-noise", 0, 0, 0.0

    client = getattr(provider, "client", None)
    if client is None:
        return _stub_classify(review), "stub", 0, 0, 0.0

    system, user = prompts.build_classify_messages(review.body, review.rating, review.menus)
    for _attempt in range(2):  # 문서 12 §2 후처리 1: JSON 파싱 실패 시 1회 재시도
        try:
            resp = client.messages.parse(
                model=router.CLASSIFY_MODEL,
                max_tokens=512,
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": user}],
                output_format=prompts.ClassifyOutput,
            )
            parsed = resp.parsed_output
            token_in = resp.usage.input_tokens
            token_out = resp.usage.output_tokens
            cost = llm.cost_krw(router.CLASSIFY_MODEL, token_in, token_out)
            return parsed, router.CLASSIFY_MODEL, token_in, token_out, cost
        except Exception:
            continue

    # 재시도도 실패 — 안전측 기본값(문서 12 §2 후처리 1)
    return (
        prompts.ClassifyOutput(category="COMPLAINT", sentiment=-0.3, issue_tags=[], risk_level=2, risk_reasons=[]),
        router.CLASSIFY_MODEL, 0, 0, 0.0,
    )


# ── 생성 ────────────────────────────────────────────────────────────────
def _generate_draft(
    provider: llm.LlmProvider, tier: str, category: str, req: AnalyzeAndDraftRequest, variant_idx: int
) -> tuple[str | None, str, str, int, int, float]:
    """(content, 사용모델, 사용티어, token_in, token_out, cost_krw) 를 반환한다.
    content 가 None 이면 두 티어(원래 티어 + 폴백 1회) 모두 실패한 것이다."""
    persona = req.persona
    tiers_to_try = [tier]
    fb = router.fallback_tier(tier)
    if fb is not None:
        tiers_to_try.append(fb)

    for attempt_tier in tiers_to_try:
        if attempt_tier == "T0":
            seed = (persona.persona_seed or 0) + variant_idx
            content = prompts.render_t0_template(persona.customer_title, seed, persona.use_emoji, persona.signature)
            content = content[: guardrails.MAX_LENGTH]
            return content, "rule-template", "T0", 0, 0, 0.0

        examples = rag.fetch_examples(req.store_id, req.review.body, k=4)
        few_shot_text = prompts.format_few_shot([(e.review_text, e.reply_text) for e in examples])
        system, user = prompts.build_generate_messages(category, req.review, persona, few_shot_text)
        model_id = router.TIER_MODELS[attempt_tier]
        try:
            result = provider.complete(system, user, model_id, max_tokens=400)
            max_len = min(persona.length_max or guardrails.MAX_LENGTH, guardrails.MAX_LENGTH)
            content = result.text.strip()[:max_len]
            return content, result.model, attempt_tier, result.token_in, result.token_out, result.cost_krw
        except Exception:
            continue

    return None, "", tier, 0, 0, 0.0


def _produce_variant(
    provider: llm.LlmProvider, tier: str, category: str, req: AnalyzeAndDraftRequest, variant_idx: int, risk_level: int
) -> tuple[DraftOut | None, list[str]]:
    """초안 1건을 생성하고 가드레일을 통과시킨다. (draft, 최종 실패 플래그) 를 반환한다.

    guardrails.check() 의 G8_RISK 는 "이 콘텐츠가 안전하지 않다"가 아니라 "risk_level 정책상
    자동 게시하면 안 된다"는 신호다(guardrails.RISK_BLOCK_THRESHOLD, 문서 12 §4 G8). 콘텐츠
    자체는 사람 검수 화면에 그대로 보여줄 수 있어야 하므로, G8_RISK 는 초안을 폐기하는 사유에서
    제외하고 응답 레벨 blocked 판정에만 반영한다(analyze_and_draft 참고).
    RETRY_FLAGS 만 걸리면(G1_LENGTH_MIN 등) 문서 12 §4 대로 1회 재생성한다.
    """
    last_flags: list[str] = []
    for _regen in range(2):  # 최초 생성 1회 + RETRY 시 재생성 1회
        content, gen_model, used_tier, tok_in, tok_out, cost = _generate_draft(provider, tier, category, req, variant_idx)
        if content is None:
            return None, ["GENERATION_FAILED"]

        flags = guardrails.check(content, risk_level, review_body=req.review.body)
        content_flags = [f for f in flags if f != "G8_RISK"]

        if not content_flags:
            draft = DraftOut(
                content=content, tier=used_tier, model=gen_model, promptVersion=prompts.PROMPT_VERSION,
                guardrailFlags=[], similarityMax=flags.similarity_max,
                tokenIn=tok_in, tokenOut=tok_out, costKrw=cost,
            )
            return draft, []

        last_flags = content_flags
        if any(f in guardrails.BLOCK_FLAGS for f in content_flags):
            break  # 진짜 콘텐츠 문제(BLOCK) — 재시도해도 소용없다
        # RETRY 대상만 있으면 한 번 더 시도(루프 계속)

    return None, last_flags


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/internal/ai/analyze-and-draft", response_model=AnalyzeAndDraftResponse)
def analyze_and_draft(req: AnalyzeAndDraftRequest) -> AnalyzeAndDraftResponse:
    provider = llm.get_provider()

    classified, classify_model, c_tok_in, c_tok_out, c_cost = _classify(provider, req.review)

    # 문서 12 §1.2: 키워드 룰이 모델보다 우선(하향 금지)
    risk_level, keyword_reasons = prompts.upgrade_risk_level(req.review.body, classified.risk_level)
    risk_reasons = sorted(set(classified.risk_reasons) | set(keyword_reasons))
    # 사전 외 태그 제거(문서 12 §2 후처리 2)
    issue_tags = [t for t in classified.issue_tags if t in prompts.ISSUE_TAG_DICT]

    analysis = AnalysisOut(
        category=classified.category,
        sentiment=classified.sentiment,
        issueTags=issue_tags,
        riskLevel=risk_level,
        riskReasons=risk_reasons,
        model=classify_model,
        promptVersion=prompts.PROMPT_VERSION,
    )

    # 문서 12 §3.1: ABUSIVE 는 자동 생성 대상이 아니다 — 사람 검수로 직행한다.
    if classified.category == "ABUSIVE":
        return AnalyzeAndDraftResponse(
            analysis=analysis, drafts=[], blocked=True, blockReasons=["ABUSIVE_MANUAL_REVIEW"]
        )

    tier = router.route(req.review.rating, req.review.body, classified.category, risk_level, req.options.force_tier)

    drafts: list[DraftOut] = []
    block_reasons: list[str] = []
    n_variants = max(1, req.options.variants)

    for variant_idx in range(n_variants):
        draft, flags = _produce_variant(provider, tier, classified.category, req, variant_idx, risk_level)
        if draft is None:
            for f in flags:
                if f not in block_reasons:
                    block_reasons.append(f)
            continue
        drafts.append(draft)

    if not drafts:
        # 가드레일 전량 차단 또는 생성 전량 실패 — 답글 없이 사람 검수로 넘긴다(절대규칙 1·3·4).
        return AnalyzeAndDraftResponse(analysis=analysis, drafts=[], blocked=True, blockReasons=block_reasons or ["UNKNOWN"])

    # 절대규칙 3 + 문서 12 §4 G8(guardrails.RISK_BLOCK_THRESHOLD): risk_level 이 임계값 이상이면
    # 콘텐츠 자체는 통과했더라도 자동 게시 금지 → 사람 검수 큐로 표시(초안은 유지해 검수 화면에 보여준다).
    if risk_level >= guardrails.RISK_BLOCK_THRESHOLD:
        return AnalyzeAndDraftResponse(analysis=analysis, drafts=drafts, blocked=True, blockReasons=["G8_RISK"])

    return AnalyzeAndDraftResponse(analysis=analysis, drafts=drafts, blocked=False, blockReasons=[])


def demo() -> None:
    from fastapi.testclient import TestClient

    c = TestClient(app)
    assert c.get("/health").json() == {"status": "ok"}

    payload = {
        "reviewId": "r1", "storeId": "1",
        "review": {"rating": 5, "body": "돌솥알밥 맛있어요", "menus": ["돌솥알밥"], "platform": "BAEMIN"},
        "persona": {
            "tone": "FRIENDLY", "useEmoji": True, "emojiLevel": 1, "customerTitle": "고객님",
            "signature": None, "bannedWords": [], "lengthMin": 60, "lengthMax": 150, "personaSeed": 1,
        },
        "options": {"variants": 1, "instruction": None, "forceTier": None},
    }
    res = c.post("/internal/ai/analyze-and-draft", json=payload)
    assert res.status_code == 200
    body = res.json()
    assert body["blocked"] is False
    assert len(body["drafts"]) == 1
    assert len(body["drafts"][0]["content"]) <= 280
    assert body["analysis"]["promptVersion"] == prompts.PROMPT_VERSION

    # 이물질 키워드 → risk_level 3 승격 → blocked=True (초안은 있어도 자동게시 금지)
    payload2 = dict(payload)
    payload2["review"] = {"rating": 1, "body": "국물에서 벌레가 나왔어요", "menus": [], "platform": "BAEMIN"}
    res2 = c.post("/internal/ai/analyze-and-draft", json=payload2).json()
    assert res2["analysis"]["riskLevel"] == 3
    assert res2["blocked"] is True

    # 욕설/무관 내용은 분류가 잘 안 되는 StubProvider 환경에서는 ABUSIVE 로 안 잡힐 수 있으니
    # 여기서는 빈 본문 NOISE 경로만 추가 확인한다.
    payload3 = dict(payload)
    payload3["review"] = {"rating": 5, "body": "", "menus": [], "platform": "BAEMIN"}
    res3 = c.post("/internal/ai/analyze-and-draft", json=payload3).json()
    assert res3["analysis"]["category"] == "NOISE"
    assert res3["blocked"] is False

    print("main demo OK (StubProvider 경로)")


if __name__ == "__main__":
    demo()
