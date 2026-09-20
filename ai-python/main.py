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

import os
import secrets
from typing import Literal

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator

import guardrails
import llm
import prompts
import rag
import router

app = FastAPI(title="ai-python")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "")


def require_internal_token(x_internal_token: str | None) -> None:
    if not INTERNAL_TOKEN or x_internal_token is None or not secrets.compare_digest(x_internal_token, INTERNAL_TOKEN):
        raise HTTPException(status_code=401, detail="unauthorized")


# ── 요청 스키마 (docs/13 §11.1) ──────────────────────────────────────────
class ReviewIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    # ★ None = "별점 없음". 0 과 다르다 — 네이버에는 별점이 안 붙는 리뷰가 실제로 있고
    #   (실측 2026-09-20, 10건 중 2건), 0 으로 접으면 모델·라우터가 최저 평점으로 읽어
    #   칭찬 리뷰가 COMPLAINT + T2(sonnet) 로 간다. 실기동에서 11건이 그렇게 됐다.
    rating: int | None = Field(default=None, ge=0, le=5)
    body: str = Field(default="", max_length=10_000)
    menus: list[str] = Field(default_factory=list)
    platform: str


class BannedWordIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    word: str
    category: str
    match_type: str = Field(alias="matchType")


class PersonaIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    tone: str = "FRIENDLY"
    use_emoji: bool = Field(default=True, alias="useEmoji")
    # 0=사용 안 함 · 1=1개 · 2=2~3개(기본) · 3=자유 — DB 기본값도 V34 에서 2 로 올렸다.
    emoji_level: int = Field(default=2, alias="emojiLevel")
    customer_title: str = Field(default="고객님", alias="customerTitle")
    signature: str | None = None
    # 사장님이 직접 입력한 답글 시작 스타일. 있으면 시드 기반 인사말보다 우선한다.
    opening_style: str | None = Field(default=None, alias="openingStyle")
    banned_words: list[str] = Field(default_factory=list, alias="bannedWords")
    global_banned_words: list[BannedWordIn] = Field(default_factory=list, alias="globalBannedWords")
    length_min: int = Field(default=60, alias="lengthMin")
    length_max: int = Field(default=150, alias="lengthMax")
    persona_seed: int | None = Field(default=None, alias="personaSeed")


class OptionsIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    variants: int = Field(default=1, ge=1, le=1)
    instruction: str | None = Field(default=None, max_length=200)
    force_tier: Literal["T0", "T1", "T2", "T3"] | None = Field(default=None, alias="forceTier")


class AnalyzeAndDraftRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    review_id: str = Field(alias="reviewId")
    store_id: str = Field(alias="storeId")
    review: ReviewIn
    persona: PersonaIn
    options: OptionsIn = Field(default_factory=OptionsIn)
    recent_replies: list[str] = Field(default_factory=list, alias="recentReplies")
    # ★ 사장님이 확정 입력한 매장 사실(주차·대기시간·좌석 등). 비어 있는 것이 기본이다.
    #   prompts.store_facts_line 이 "여기 적힌 것만 쓸 수 있다" 를 못박는다 —
    #   그 한 줄이 절대규칙 8의 우회 통로가 되는 것을 막는다.
    store_facts: dict[str, str] = Field(default_factory=dict, alias="storeFacts")

    @field_validator("store_facts")
    @classmethod
    def limit_store_facts(cls, facts: dict[str, str]) -> dict[str, str]:
        """길이·개수 상한. 신뢰 경계를 넘어온 값이라 여기서 자른다."""
        return {k: str(v)[:200] for k, v in list(facts.items())[:8] if str(v).strip()}

    @field_validator("recent_replies")
    @classmethod
    def limit_recent_replies(cls, replies: list[str]) -> list[str]:
        # 최근 게시 이력은 G7 전용이다. 오래된 항목은 버리고 계산량을 제한한다.
        return replies[:20]


# ── 응답 스키마 (docs/13 §11.1) ──────────────────────────────────────────
class AnalysisOut(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    category: str
    tone: str = "CALM"
    sentiment: float
    issue_tags: list[str] = Field(default_factory=list, alias="issueTags")
    praised_tags: list[str] = Field(default_factory=list, alias="praisedTags")
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
    # tone 은 전부 CALM 이다 — 스텁은 문장을 읽지 않으므로 감정을 짐작할 근거가 없다.
    # 모르면 낮은 쪽이 안전하다(강도를 과하게 잡으면 가벼운 리뷰에 사죄문이 나간다).
    #
    # ★ 별점이 없으면 스텁은 아무 근거가 없다. 0 으로 접어 COMPLAINT 로 보내면
    #   칭찬 리뷰에 사과문이 붙는다(실기동 2026-09-20에서 11건이 그렇게 됐다).
    #   중립(IMPROVEMENT)으로 둔다 — 스텁은 품질 판정용이 아니라 배관 확인용이다.
    if review.rating is None:
        return prompts.ClassifyOutput(
            category="IMPROVEMENT", tone="CALM", sentiment=0.0,
            issue_tags=[], praised_tags=[], risk_level=0, risk_reasons=[],
        )
    if review.rating >= 4:
        return prompts.ClassifyOutput(
            category="POSITIVE", tone="CALM", sentiment=1.0 if review.rating == 5 else 0.6,
            issue_tags=[], praised_tags=[], risk_level=0, risk_reasons=[],
        )
    if review.rating == 3:
        return prompts.ClassifyOutput(
            category="IMPROVEMENT", tone="CALM", sentiment=0.0,
            issue_tags=[], praised_tags=[], risk_level=0, risk_reasons=[],
        )
    return prompts.ClassifyOutput(
        category="COMPLAINT", tone="CALM", sentiment=-0.6 if review.rating == 2 else -1.0,
        issue_tags=[], praised_tags=[], risk_level=1, risk_reasons=[],
    )


def _classify(provider: llm.LlmProvider, review: ReviewIn) -> tuple[prompts.ClassifyOutput, str, int, int, float, int]:
    """(분류결과, 사용모델, token_in, token_out, cost_krw) 를 반환한다."""
    if not review.body.strip():
        return (prompts.ClassifyOutput(category="NOISE", tone="CALM", sentiment=0.0, issue_tags=[],
                                       praised_tags=[], risk_level=0, risk_reasons=[]),
                "rule-noise", 0, 0, 0.0, 0)

    client = getattr(provider, "client", None)
    if client is None:
        return _stub_classify(review), "stub", 0, 0, 0.0, 0

    sanitized_body, _injection_found, _markers = guardrails.sanitize_review(review.body)
    system, user = prompts.build_classify_messages(sanitized_body, review.rating, review.menus, review.platform)
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
            plain_input = resp.usage.input_tokens
            cache_creation = getattr(resp.usage, "cache_creation_input_tokens", 0) or 0
            cache_read = getattr(resp.usage, "cache_read_input_tokens", 0) or 0
            token_in = plain_input + cache_creation + cache_read
            token_out = resp.usage.output_tokens
            cost = llm.cost_krw(
                router.CLASSIFY_MODEL, plain_input, token_out, cache_creation, cache_read
            )
            return parsed, router.CLASSIFY_MODEL, token_in, token_out, cost, cache_read
        except Exception:
            continue

    # 재시도도 실패 — 안전측 기본값(문서 12 §2 후처리 1)
    return (
        prompts.ClassifyOutput(category="COMPLAINT", sentiment=-0.3, issue_tags=[], risk_level=2, risk_reasons=[]),
        router.CLASSIFY_MODEL, 0, 0, 0.0, 0,
    )


# ── 생성 ────────────────────────────────────────────────────────────────
def _generate_draft(
    provider: llm.LlmProvider, tier: str, category: str, req: AnalyzeAndDraftRequest, variant_idx: int,
    issue_tags: list[str] | None = None, risk_reasons: list[str] | None = None,
    tone: str = "CALM", praised_tags: list[str] | None = None, risk_level: int = 0,
) -> tuple[str | None, str, str, int, int, float, list[str]]:
    """(content, 사용모델, 사용티어, token_in, token_out, cost_krw) 를 반환한다.
    content 가 None 이면 두 티어(원래 티어 + 폴백 1회) 모두 실패한 것이다."""
    persona = req.persona
    tiers_to_try = [tier]
    fb = router.fallback_tier(tier)
    if fb is not None:
        tiers_to_try.append(fb)

    for attempt_tier in tiers_to_try:
        if attempt_tier == "T0" and not prompts.t0_template_allowed(category):
            # 감사 템플릿을 불만 리뷰에 붙이지 않는다. 초안 없이 사람에게 넘긴다.
            continue
        if attempt_tier == "T0":
            seed = (persona.persona_seed or 0) + variant_idx
            # ★ 본문을 함께 넘긴다. 없으면 한 매장의 T0 리뷰가 전부 같은 문장을 받는다
            #   (persona_seed 는 매장당 고정값이다 — 실기동 2026-09-20).
            content = prompts.render_t0_template(
                persona.customer_title, seed, persona.use_emoji, persona.signature,
                req.review.platform, req.review.body
            )
            content = content[: guardrails.MAX_LENGTH]
            return content, "rule-template", "T0", 0, 0, 0.0, list(dict.fromkeys(req.recent_replies))

        # ★ 분류 축을 검색에 넘긴다. 이게 없으면 배달 지연 리뷰에 칭찬 답글 4건이 예시로 붙는다.
        slot = prompts.style_slot_for(category)
        # ★ platform 도 넘긴다. 이게 없으면 네이버 방문 리뷰 답글에 배달에서 긁어온
        #   사장님 답글이 예시로 붙어 "주문 요청사항에 적어주시면…" 같은 배달 표현이 샌다.
        #   MANUAL(사장님이 직접 적은 형식)은 플랫폼 무관이라 양쪽 다 그대로 쓴다.
        examples = rag.fetch_examples(
            req.store_id, req.review.body, k=4, category=category, issue_tags=issue_tags,
            slot_wanted=slot, platform=req.review.platform,
        )
        # ★ 슬롯 유형을 같이 넘긴다. 리뷰가 없는 형식 예시는 유형이 곧 맥락이다 —
        #   없으면 감사 형식이 불만 리뷰의 본보기로 읽힌다.
        pairs = [(e.review_text, e.reply_text, getattr(e, "sample_type", None)) for e in examples]
        # ★ 사장님이 그 슬롯을 비워 뒀으면 우리 기본 형식을 맨 앞에 넣는다.
        #   비워 둔 매장이 예시 없이 생성되면 답글이 업종 표준 톤으로 흘러간다.
        if not any(getattr(e, "sample_type", None) == slot for e in examples):
            pairs.insert(0, ("", prompts.default_style_sample(slot, persona.persona_seed), slot))
        few_shot_text = prompts.format_few_shot(pairs)
        # 게시 이력은 프롬프트가 아니라 가드레일에만 전달한다(입력 토큰 증가 방지).
        recent_replies = list(dict.fromkeys(req.recent_replies + [e.reply_text for e in examples]))
        # ★ issue_tags 를 넘긴다. 이게 없으면 '국물이 샜다' 와 '배달이 늦었다' 가 같은
        #   COMPLAINT 지침 한 줄로 뭉뚱그려진다(사장/소비자 관점 검토에서 공통 지적).
        sanitized_body, _injection_found, _markers = guardrails.sanitize_review(req.review.body)
        safe_review = req.review.model_copy(update={"body": sanitized_body})
        system, user = prompts.build_generate_messages(
            category, safe_review, persona, few_shot_text, issue_tags, req.options.instruction,
            # ★ review_id 는 머리말·맺음말을 리뷰마다 흩는 씨앗이다. 빼면 한 매장의
            #   모든 답글이 같은 인사말로 시작한다(실측 v1.9: 도입부 상위 6문형이 69%).
            risk_reasons, req.review_id, tone, praised_tags, req.review.platform, risk_level,
            req.store_facts,
        )
        model_id = router.TIER_MODELS[attempt_tier]
        try:
            result = provider.complete(system, user, model_id, max_tokens=400)
            max_len = min(persona.length_max or guardrails.MAX_LENGTH, guardrails.MAX_LENGTH)
            content = result.text.strip()[:max_len]
            return content, result.model, attempt_tier, result.token_in, result.token_out, result.cost_krw, recent_replies
        except Exception:
            continue

    return None, "", tier, 0, 0, 0.0, []


def _produce_variant(
    provider: llm.LlmProvider, tier: str, category: str, req: AnalyzeAndDraftRequest, variant_idx: int,
    risk_level: int, issue_tags: list[str] | None = None, risk_reasons: list[str] | None = None,
    tone: str = "CALM", praised_tags: list[str] | None = None,
) -> tuple[DraftOut | None, list[str]]:
    """초안 1건을 생성하고 가드레일을 통과시킨다. (draft, 최종 실패 플래그) 를 반환한다.

    guardrails.check() 의 G8_RISK 는 "이 콘텐츠가 안전하지 않다"가 아니라 "risk_level 정책상
    자동 게시하면 안 된다"는 신호다(guardrails.RISK_BLOCK_THRESHOLD, 문서 12 §4 G8). 콘텐츠
    자체는 사람 검수 화면에 그대로 보여줄 수 있어야 하므로, G8_RISK 는 초안을 폐기하는 사유에서
    제외하고 응답 레벨 blocked 판정에만 반영한다(analyze_and_draft 참고).
    RETRY_FLAGS 만 걸리면(G1_LENGTH_MIN 등) 문서 12 §4 대로 1회 재생성한다.
    """
    last_flags: list[str] = []
    last_retry_draft: DraftOut | None = None
    total_token_in = 0
    total_token_out = 0
    total_cost = 0.0
    for _regen in range(2):  # 최초 생성 1회 + RETRY 시 재생성 1회
        content, gen_model, used_tier, tok_in, tok_out, cost, recent_replies = _generate_draft(
            provider, tier, category, req, variant_idx, issue_tags, risk_reasons, tone, praised_tags,
            risk_level,
        )
        total_token_in += tok_in
        total_token_out += tok_out
        total_cost += cost
        if content is None:
            return None, ["GENERATION_FAILED"]

        banned_rules = [(r.word, r.category, r.match_type) for r in req.persona.global_banned_words]
        banned_rules.extend((word, "STORE", "CONTAINS") for word in req.persona.banned_words)
        flags = guardrails.check(
            content, risk_level, review_body=req.review.body, recent_replies=recent_replies,
            extra_banned_words=banned_rules,
            # ★ 하한은 카테고리별이다. 짧은 호평에 60자를 요구하면 초안이 통째로 사라진다.
            min_length=guardrails.min_length_for(category, tone),
        )
        content_flags = [f for f in flags if f != "G8_RISK"]

        if not content_flags:
            draft = DraftOut(
                content=content, tier=used_tier, model=gen_model, promptVersion=prompts.prompt_version_for(req.review.platform),
                guardrailFlags=[], similarityMax=flags.similarity_max,
                tokenIn=total_token_in, tokenOut=total_token_out, costKrw=round(total_cost, 4),
            )
            return draft, []

        last_flags = content_flags
        if any(f in guardrails.BLOCK_FLAGS for f in content_flags):
            # 진짜 콘텐츠 문제 — 내용 자체가 규칙을 어겼다. 재시도도 보존도 하지 않는다.
            return None, content_flags
        # RETRY 대상만 있으면 한 번 더 시도(루프 계속)하되, 마지막 것을 들고 간다.
        last_retry_draft = DraftOut(
            content=content, tier=used_tier, model=gen_model, promptVersion=prompts.prompt_version_for(req.review.platform),
            guardrailFlags=content_flags, similarityMax=flags.similarity_max,
            tokenIn=total_token_in, tokenOut=total_token_out, costKrw=round(total_cost, 4),
        )

    # ★ RETRY 를 소진했다. 마지막 초안을 버리지 않고 플래그와 함께 넘긴다.
    #   플래그가 남아 있으면 호출부가 blocked=True 로 돌려주므로 자동 게시는 막히고,
    #   검수 화면에는 사장님이 고쳐 쓸 문장이 남는다. 빈손보다 55자짜리가 낫다.
    #   실측(2026-09-19) C-L-012 "두 시간 걸려서 왔는데 다 식었고 일부 금액 환불 요청드려요"
    #   가 2회 모두 60자 미만이라 통째로 사라졌다 — 분류·위험도는 전부 정확했는데도.
    return last_retry_draft, last_flags


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/internal/ai/analyze-and-draft", response_model=AnalyzeAndDraftResponse)
def analyze_and_draft(
    req: AnalyzeAndDraftRequest,
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token", include_in_schema=False),
) -> AnalyzeAndDraftResponse:
    require_internal_token(x_internal_token)
    provider = llm.get_provider()

    classified, classify_model, c_tok_in, c_tok_out, c_cost, _c_cache = _classify(provider, req.review)

    # 문서 12 §1.2: 키워드 룰이 모델보다 우선(하향 금지)
    # ★ platform 을 넘긴다. 방문 리뷰에만 있는 축(매장 위생·대면 충돌)은 배달 룰이
    #   상정한 적이 없다 — 넘기지 않으면 "화장실이 너무 더러웠어요" 가 자동 게시된다.
    risk_level, keyword_reasons = prompts.upgrade_risk_level(
        req.review.body, classified.risk_level, req.review.platform
    )
    risk_reasons = sorted(
        (set(classified.risk_reasons) | set(keyword_reasons)) & set(prompts.RISK_REASON_VALUES)
    )
    # 사전 외 태그 제거(문서 12 §2 후처리 2). 태그 사전은 플랫폼별이다(배달/네이버 방문).
    platform_tags = prompts.issue_tags_for(req.review.platform)
    issue_tags = [t for t in classified.issue_tags if t in platform_tags]
    # ★ 같은 태그가 양쪽에 들어오면 문제 쪽을 남긴다. 답글에서 사과를 빠뜨리는 것보다
    #   칭찬 호응을 빠뜨리는 쪽이 사고가 작다.
    praised_tags = [
        t for t in classified.praised_tags if t in platform_tags and t not in issue_tags
    ]
    # ★ 모델 tone 위에 결정론 룰을 덮어쓴다. 올리기만 한다 — 실측에서 모델이 낸 tone 은
    #   54건 중 50건이 CALM 이었다(2026-09-17). 같은 어휘가 risk 2 의 (가) 조건이기도 해서
    #   _is_risk2 와 한 벌을 쓴다.
    tone = prompts.upgrade_tone(
        req.review.body, classified.tone if classified.tone in prompts.TONE_VALUES else "CALM"
    )

    analysis = AnalysisOut(
        category=classified.category,
        tone=tone,
        sentiment=classified.sentiment,
        issueTags=issue_tags,
        praisedTags=praised_tags,
        riskLevel=risk_level,
        riskReasons=risk_reasons,
        model=classify_model,
        promptVersion=prompts.prompt_version_for(req.review.platform),
    )

    # 문서 12 §3.1: 답글을 만들지 않는 카테고리는 사람에게 넘긴다.
    # ★ 사유를 갈라서 돌려준다(v2.0). 둘 다 생성은 안 하지만 사장님이 할 일이 다르다 —
    #   ABUSIVE 는 읽고 판단해야 하고, OFF_TOPIC(광고·시사)은 읽을 것도 없이 넘기면 된다.
    # ★ 단, ABUSIVE 밑에 실체 있는 위험 사유가 깔려 있으면 초안을 만든다(prompts 참고).
    #   ABUSIVE 오판은 받쳐 주는 것이 하나도 없는 유일한 축이고, 하필 손해가 가장 크다.
    # ★ 방문 경로(네이버)는 카테고리로 초안을 막지 않는다(2026-09-19 운영자 결정).
    #   배달은 가드레일을 통과하면 사람 손을 거치지 않고 게시되므로 "안 만드는 것" 이
    #   곧 방어였다. 네이버는 **구조적으로 전건 사람 승인**이다 — 확장이 본문을 넣기만
    #   하고 게시는 사장님이 직접 누른다(NAVER ABSOLUTE RULES 6·7·8, `!event.isTrusted`
    #   게이트). 서버에는 게시 경로 자체가 없다.
    #   그러니 여기서 초안을 빼는 것은 **아무것도 막지 않고 사장님만 빈손으로 만든다.**
    #   위험은 초안을 없애서가 아니라 riskLevel·riskReasons 로 표시해서 알린다.
    draft_category = classified.category
    if classified.category in prompts.NO_DRAFT_CATEGORIES:
        rescued = (
            "COMPLAINT" if prompts.is_visit_platform(req.review.platform)
            else prompts.abusive_draft_category(risk_reasons)
            if classified.category == "ABUSIVE" and risk_level >= guardrails.RISK_BLOCK_THRESHOLD
            else None
        )
        if rescued is None:
            reason = "ABUSIVE_MANUAL_REVIEW" if classified.category == "ABUSIVE" else "OFF_TOPIC_NO_REPLY"
            return AnalyzeAndDraftResponse(
                analysis=analysis, drafts=[], blocked=True, blockReasons=[reason]
            )
        draft_category = rescued

    tier = router.route(
        req.review.rating, req.review.body, draft_category, risk_level,
        req.options.force_tier, issue_tag_count=len(issue_tags),
    )

    drafts: list[DraftOut] = []
    block_reasons: list[str] = []
    n_variants = max(1, req.options.variants)

    for variant_idx in range(n_variants):
        draft, flags = _produce_variant(
            provider, tier, draft_category, req, variant_idx, risk_level, issue_tags,
            risk_reasons, tone, praised_tags,
        )
        # ★ 초안이 있어도 플래그가 남아 있으면 블록 사유다(RETRY 소진분).
        #   여기서 빠뜨리면 가드레일에 걸린 초안이 blocked=False 로 자동 게시된다.
        for f in flags:
            if f not in block_reasons:
                block_reasons.append(f)
        if draft is None:
            continue
        drafts.append(draft)

    # variants 는 현재 1개로 제한된다. 분류 호출도 같은 요청의 원가이므로 저장되는 초안에 합산한다.
    if drafts:
        drafts[0].token_in += c_tok_in
        drafts[0].token_out += c_tok_out
        drafts[0].cost_krw = round(drafts[0].cost_krw + c_cost, 4)

    if not drafts:
        # 가드레일 전량 차단 또는 생성 전량 실패 — 답글 없이 사람 검수로 넘긴다(절대규칙 1·3·4).
        return AnalyzeAndDraftResponse(analysis=analysis, drafts=[], blocked=True, blockReasons=block_reasons or ["UNKNOWN"])

    # 절대규칙 3 + 문서 12 §4 G8(guardrails.RISK_BLOCK_THRESHOLD): risk_level 이 임계값 이상이면
    # 콘텐츠 자체는 통과했더라도 자동 게시 금지 → 사람 검수 큐로 표시(초안은 유지해 검수 화면에 보여준다).
    if risk_level >= guardrails.RISK_BLOCK_THRESHOLD and "G8_RISK" not in block_reasons:
        block_reasons.append("G8_RISK")
    if block_reasons:
        return AnalyzeAndDraftResponse(analysis=analysis, drafts=drafts, blocked=True, blockReasons=block_reasons)

    return AnalyzeAndDraftResponse(analysis=analysis, drafts=drafts, blocked=False, blockReasons=[])


def demo() -> None:
    from fastapi.testclient import TestClient

    # 인증은 fail-closed 다 — INTERNAL_TOKEN 이 비면 전부 401. 자기점검용 토큰을 여기서 주입한다.
    global INTERNAL_TOKEN
    INTERNAL_TOKEN = INTERNAL_TOKEN or "demo-internal-token"
    hdr = {"X-Internal-Token": INTERNAL_TOKEN}

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
    res = c.post("/internal/ai/analyze-and-draft", json=payload, headers=hdr)
    assert res.status_code == 200
    body = res.json()
    assert body["blocked"] is False
    assert len(body["drafts"]) == 1
    assert len(body["drafts"][0]["content"]) <= 280
    assert body["analysis"]["promptVersion"] == prompts.PROMPT_VERSION

    # 이물질 키워드 → risk_level 3 승격 → blocked=True (초안은 있어도 자동게시 금지)
    payload2 = dict(payload)
    payload2["review"] = {"rating": 1, "body": "국물에서 벌레가 나왔어요", "menus": [], "platform": "BAEMIN"}
    res2 = c.post("/internal/ai/analyze-and-draft", json=payload2, headers=hdr).json()
    assert res2["analysis"]["riskLevel"] == 3
    assert res2["blocked"] is True

    # 욕설/무관 내용은 분류가 잘 안 되는 StubProvider 환경에서는 ABUSIVE 로 안 잡힐 수 있으니
    # 여기서는 빈 본문 NOISE 경로만 추가 확인한다.
    payload3 = dict(payload)
    payload3["review"] = {"rating": 5, "body": "", "menus": [], "platform": "BAEMIN"}
    res3 = c.post("/internal/ai/analyze-and-draft", json=payload3, headers=hdr).json()
    assert res3["analysis"]["category"] == "NOISE"
    assert res3["blocked"] is False

    print("main demo OK (StubProvider 경로)")


if __name__ == "__main__":
    demo()
