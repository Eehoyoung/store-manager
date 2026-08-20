# TODO(Sprint 3): LLM 라우팅(T0~T3)·RAG(pgvector few-shot)로 교체.
# 지금은 결정적 스텁이다 — 실제 분류·생성 모델을 호출하지 않는다.
"""
AI Service (FastAPI) — 진입점.

★ 절대규칙 1(CLAUDE.md): 이 서비스는 "답글"만 생성한다. 리뷰 본문을 생성/변형하는
  엔드포인트·함수는 어떤 형태로도 추가하지 않는다.

이 파일이 구현하는 엔드포인트는 CLAUDE.md 가 허용하는 서비스 간 경계 중 하나인
`POST /internal/ai/analyze-and-draft` (Spring → Python) 뿐이다. 요청/응답 스키마는
docs/13_내부API명세.md §11.1 의 JSON 예시와 정확히 일치한다(camelCase alias).
"""
from __future__ import annotations

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field

import guardrails
from prompts import PROMPT_VERSION

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


# ── 결정적 스텁 로직 ──────────────────────────────────────────────────────
def _classify(review: ReviewIn) -> tuple[str, float, int]:
    """rating 기반 임시 분류. (category, sentiment, risk_level) 반환.
    TODO(Sprint 3): 실제 카테고리(PRAISE/ABUSIVE 등)·이슈태그·위험도 분류 모델로 교체."""
    if not review.body.strip():
        return "NOISE", 0.0, 0
    if review.rating >= 4:
        return "POSITIVE", 1.0 if review.rating == 5 else 0.6, 0
    if review.rating == 3:
        return "IMPROVEMENT", 0.0, 0
    return "COMPLAINT", -0.6 if review.rating == 2 else -1.0, 1


_TEMPLATES = {
    "POSITIVE": "{title}, 좋은 후기 남겨주셔서 감사합니다. 앞으로도 맛있는 음식으로 보답하겠습니다.",
    "IMPROVEMENT": "{title}, 소중한 의견 감사합니다. 말씀해 주신 부분은 개선하도록 하겠습니다.",
    "COMPLAINT": "{title}, 불편을 드려 죄송합니다. 확인 후 개선하겠습니다.",
    "NOISE": "{title}, 방문해 주셔서 감사합니다.",
}


def _render_draft(category: str, persona: PersonaIn) -> str:
    """T0 룰 기반 템플릿 1건 생성. TODO(Sprint 3): 로컬/클라우드 LLM 생성으로 교체."""
    text = _TEMPLATES[category].format(title=persona.customer_title)
    if persona.signature:
        text = f"{text} {persona.signature}"
    return text[: persona.length_max or guardrails.MAX_LENGTH][: guardrails.MAX_LENGTH]


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/internal/ai/analyze-and-draft", response_model=AnalyzeAndDraftResponse)
def analyze_and_draft(req: AnalyzeAndDraftRequest) -> AnalyzeAndDraftResponse:
    category, sentiment, risk_level = _classify(req.review)

    analysis = AnalysisOut(
        category=category,
        sentiment=sentiment,
        issueTags=[],
        riskLevel=risk_level,
        riskReasons=[],
        model="stub-t0-rule-based",
        promptVersion=PROMPT_VERSION,
    )

    content = _render_draft(category, req.persona)
    flags = guardrails.check(content, risk_level)

    if flags:
        # 절대규칙 3·4 — 가드레일에 걸리면 어떤 초안도 반환하지 않고 사람 검수로 넘긴다.
        return AnalyzeAndDraftResponse(
            analysis=analysis, drafts=[], blocked=True, blockReasons=flags
        )

    draft = DraftOut(
        content=content,
        tier="T0",
        model="stub-t0-rule-based",
        promptVersion=PROMPT_VERSION,
        guardrailFlags=[],
        similarityMax=None,
        tokenIn=len(req.review.body),
        tokenOut=len(content),
        costKrw=0.0,
    )
    return AnalyzeAndDraftResponse(analysis=analysis, drafts=[draft], blocked=False, blockReasons=[])
