import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { personaApi } from "../api/persona";
import { StoreFactsSection } from "./StoreFactsSection";
import { reviewsApi } from "../api/reviews";
import type { PersonaRequest, PersonaResponse, PreviewResponse, PublishWindow, ReviewSummary, StyleSampleResponse } from "../api/types";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { Button } from "../components/Button";
import { Field } from "../components/Field";
import { Select } from "../components/Select";
import { Badge } from "../components/Badge";
import { Modal } from "../components/Modal";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { Pagination } from "../components/Pagination";
import { useToast } from "../components/Toast";
import { describeGeneratedBy, describeGuardrailFlag, describeRiskReason } from "../lib/labels";
import { useShellStore } from "../layout/AppShell";


// 0~3 숫자만 보여주면 사장님이 무슨 뜻인지 알 수 없다(docs/14 — 40~60대 설계 원칙).
// ★ 기본값은 2 다(V34, 2026-09-17). 라벨 문구는 ai-python 의 _EMOJI_LABELS 와 같은 뜻이어야 한다.
const EMOJI_LEVELS: ReadonlyArray<readonly [number, string]> = [
  [0, "0 — 사용 안 함"],
  [1, "1 — 1개까지만"],
  [2, "2 — 2~3개 (기본)"],
  [3, "3 — 자유롭게"],
];

const TONE_OPTIONS: { value: PersonaRequest["tone"]; label: string }[] = [
  { value: "POLITE", label: "정중한" },
  { value: "FRIENDLY", label: "친근한" },
  { value: "CHEERFUL", label: "활기찬" },
  { value: "CONCISE", label: "간결한" },
];

// ★ 절대규칙 3: 3 이상은 선택지 자체를 만들지 않는다.
function toRequest(p: PersonaResponse): PersonaRequest {
  return {
    tone: p.tone,
    useEmoji: p.useEmoji,
    emojiLevel: p.emojiLevel,
    customerTitle: p.customerTitle,
    signature: p.signature,
    openingStyle: p.openingStyle,
    bannedWords: p.bannedWords,
    lengthMin: p.lengthMin,
    lengthMax: p.lengthMax,
    delayHours: p.delayHours,
    publishWindows: p.publishWindows,
  };
}

function validate(p: PersonaRequest): Record<string, string> {
  const errors: Record<string, string> = {};
  if (p.lengthMax > 280) errors.lengthMax = "280자를 넘을 수 없어요.";
  if (p.lengthMin < 1) errors.lengthMin = "1 이상이어야 해요.";
  if (p.lengthMin > p.lengthMax) errors.lengthMin = "최소 길이는 최대 길이 이하여야 해요.";
  p.publishWindows.forEach((w, i) => {
    if (!w.start || !w.end || !(w.start < w.end)) {
      errors[`publishWindows[${i}]`] = `시간대 ${i + 1}: 시작 시각은 종료 시각보다 이전이어야 해요.`;
    }
  });
  return errors;
}

const KNOWN_FIELD_KEYS = new Set([
  "tone",
  "emojiLevel",
  "customerTitle",
  "signature",
  "openingStyle",
  "lengthMin",
  "lengthMax",
  "delayHours",
]);

export function PersonaPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();
  const toast = useToast();

  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  const [persona, setPersona] = useState<PersonaRequest | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [newBannedWord, setNewBannedWord] = useState("");
  const [tab, setTab] = useState<"voice" | "content" | "publishing" | "examples">("voice");
  // ★ persona 상태(PersonaRequest)와 따로 둔다 — 자동 게시는 저장 버튼이 아니라 즉시 반영이다.
  const [autoPublish, setAutoPublish] = useState(true);

  useEffect(() => {
    if (!storeId) return;
    personaApi
      .get(storeId)
      .then((res) => {
        setPersona(toRequest(res));
        setAutoPublish(res.autoPublish);
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "페르소나 설정을 불러오지 못했어요."));
  }, [storeId]);

  const toggleAutoPublish = async (next: boolean) => {
    try {
      const res = await personaApi.setAutoPublish(storeId, next);
      setAutoPublish(res.autoPublish);
      toast.show(next ? "자동 게시를 다시 켰어요." : "자동 게시를 중지했어요. 답글은 계속 만들어져요.",
        "success");
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "변경에 실패했어요.", "danger");
    }
  };

  const update = (patch: Partial<PersonaRequest>) => {
    setPersona((prev) => (prev ? { ...prev, ...patch } : prev));
  };

  const handleSave = async () => {
    if (!persona) return;
    const errors = validate(persona);
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      toast.show("입력값을 다시 확인해 주세요.", "danger");
      return;
    }
    setFieldErrors({});
    setSaving(true);
    try {
      const res = await personaApi.update(storeId, persona);
      setPersona(toRequest(res));
      toast.show("페르소나 설정을 저장했어요.", "success");
    } catch (e) {
      if (e instanceof ApiError && e.code === "VALIDATION_FAILED") {
        setFieldErrors((e.details?.fields as Record<string, string>) ?? {});
        toast.show(e.message, "danger");
      } else {
        toast.show(e instanceof ApiError ? e.message : "저장에 실패했어요.", "danger");
      }
    } finally {
      setSaving(false);
    }
  };

  const addBannedWord = () => {
    const w = newBannedWord.trim();
    if (!w || !persona || persona.bannedWords.includes(w)) {
      setNewBannedWord("");
      return;
    }
    update({ bannedWords: [...persona.bannedWords, w] });
    setNewBannedWord("");
  };

  const updateWindow = (i: number, patch: Partial<PublishWindow>) => {
    if (!persona) return;
    const windows = persona.publishWindows.map((w, idx) => (idx === i ? { ...w, ...patch } : w));
    update({ publishWindows: windows });
  };

  if (!storeId) {
    return <EmptyState title="매장을 먼저 선택해 주세요" />;
  }

  if (loadError) {
    return <EmptyState title="페르소나 설정을 불러오지 못했어요" description={loadError} />;
  }

  if (!persona) {
    return (
      <div className="persona-page">
        <Skeleton height={300} />
      </div>
    );
  }

  const bannerErrors = Object.entries(fieldErrors).filter(([k]) => !KNOWN_FIELD_KEYS.has(k));

  return (
    <div className="persona-page">
      <h1>페르소나 설정</h1>
      <p className="persona-page__intro">매장 답글의 말투와 운영 방식을 탭별로 설정해요. 바꾼 값은 저장 버튼을 눌러 적용하세요.</p>

      {bannerErrors.length > 0 ? (
        <Card className="persona-page__error-banner" role="alert">
          <ul>
            {bannerErrors.map(([k, v]) => (
              <li key={k}>{v}</li>
            ))}
          </ul>
        </Card>
      ) : null}

      <div className="persona-page__tabs" role="tablist" aria-label="페르소나 설정 항목">
        {([["voice", "말투와 표현"], ["content", "매장 정보와 내용"], ["publishing", "게시 방식"], ["examples", "미리보기와 학습"]] as const).map(([id, label]) => (
          <button key={id} id={`persona-tab-${id}`} type="button" role="tab" aria-selected={tab === id} aria-controls={`persona-panel-${id}`}
            className={`persona-page__tab${tab === id ? " persona-page__tab--active" : ""}`} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      <div className="persona-page__actions">
        <span>말투·내용·시간 변경은 저장이 필요합니다. 자동 게시 설정은 바꾸는 즉시 적용돼요.</span>
        <Button type="button" onClick={handleSave} loading={saving}>변경 사항 저장</Button>
      </div>

      <div id="persona-panel-voice" role="tabpanel" aria-labelledby="persona-tab-voice" hidden={tab !== "voice"}>
      <Card className="persona-page__section">
        <h2>말투</h2>
        <p className="field__hint">리뷰에 답할 때 유지할 기본 분위기예요. 상황과 안전 규칙에 따라 표현은 달라질 수 있어요.</p>
        <fieldset className="persona-page__radio-group">
          <legend className="field__label">말투 선택</legend>
          {TONE_OPTIONS.map((t) => (
            <label key={t.value} className="persona-page__radio">
              <input
                type="radio"
                name="tone"
                value={t.value}
                checked={persona.tone === t.value}
                onChange={() => update({ tone: t.value })}
              />
              {t.label}
            </label>
          ))}
        </fieldset>
        {fieldErrors.tone ? (
          <p className="field__error" role="alert">
            {fieldErrors.tone}
          </p>
        ) : null}

        <label className="persona-page__checkbox">
          <input type="checkbox" checked={persona.useEmoji} onChange={(e) => update({ useEmoji: e.target.checked })} />
          이모지 사용
        </label>
        <p className="field__hint">이 항목을 켜야 답글에 이모지가 사용돼요. 끄면 아래 개수 설정과 관계없이 이모지를 넣지 않아요.</p>
        <Select
          label="이모지 사용 정도"
          value={String(persona.emojiLevel)}
          onChange={(e) => update({ emojiLevel: Number(e.target.value) })}
          disabled={!persona.useEmoji}
          error={fieldErrors.emojiLevel}
        >
          {EMOJI_LEVELS.map(([n, label]) => (
            <option key={n} value={n}>
              {label}
            </option>
          ))}
        </Select>
        <p className="field__hint">이모지 사용을 켠 경우 답글 한 건에 들어갈 양을 정해요. 기본은 2~3개예요.</p>

        <Field
          label="고객 호칭"
          value={persona.customerTitle}
          maxLength={20}
          onChange={(e) => update({ customerTitle: e.target.value })}
          error={fieldErrors.customerTitle}
          hint="답글에서 손님을 부를 때 쓸 표현이에요. 비우면 기본 호칭을 사용해요. 예: 고객님, 손님"
        />
        <Field
          label="서명"
          value={persona.signature}
          maxLength={100}
          onChange={(e) => update({ signature: e.target.value })}
          error={fieldErrors.signature}
          hint="답글 끝에 매장 이름이나 인사말을 덧붙여요. 비우면 서명 없이 마쳐요."
        />
        <Field
          label="답글 시작 스타일"
          value={persona.openingStyle}
          maxLength={100}
          onChange={(e) => update({ openingStyle: e.target.value })}
          error={fieldErrors.openingStyle}
          hint="답글 첫 문장의 시작 방식을 맞춰요. 인사말을 일관되게 하고 싶을 때 설정하고, 비우면 리뷰 내용에 맞춰 자연스럽게 시작해요."
        />
      </Card>
      </div>

      <div id="persona-panel-content" role="tabpanel" aria-labelledby="persona-tab-content" hidden={tab !== "content"}>
      {/* ★ 말투 바로 다음에 둔다. 사장님이 가장 먼저 채우면 효과가 제일 큰 항목이다 —
          여기가 비면 주차·웨이팅 리뷰 답글이 "확인해 보겠습니다" 로만 끝난다. */}
      <StoreFactsSection storeId={storeId} />

      <Card className="persona-page__section">
        <h2>금칙어</h2>
        <p className="field__hint">답글에 절대 포함되면 안 되는 단어를 등록해요.</p>
        <div className="persona-page__tag-input">
          <Field
            label="새 금칙어"
            value={newBannedWord}
            maxLength={50}
            onChange={(e) => setNewBannedWord(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addBannedWord();
              }
            }}
          />
          <Button type="button" variant="secondary" onClick={addBannedWord}>
            추가
          </Button>
        </div>
        {persona.bannedWords.length > 0 ? (
          <ul className="persona-page__tag-list">
            {persona.bannedWords.map((w) => (
              <li key={w}>
                <Badge tone="neutral">{w}</Badge>
                <button
                  type="button"
                  className="persona-page__tag-remove"
                  aria-label={`${w} 삭제`}
                  onClick={() => update({ bannedWords: persona.bannedWords.filter((x) => x !== w) })}
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="field__hint">등록된 금칙어가 없어요.</p>
        )}
      </Card>

      <Card className="persona-page__section">
        <h2>답글 길이</h2>
        <Field
          label="최소 길이"
          type="number"
          min={1}
          value={persona.lengthMin}
          onChange={(e) => update({ lengthMin: Number(e.target.value) })}
          error={fieldErrors.lengthMin}
          hint="답글이 지나치게 짧아지지 않도록 원하는 최소 글자 수를 지정해요."
        />
        <Field
          label="최대 길이"
          type="number"
          min={1}
          max={280}
          value={persona.lengthMax}
          onChange={(e) => update({ lengthMax: Number(e.target.value) })}
          error={fieldErrors.lengthMax}
          hint="답글 최대 글자 수예요. 플랫폼 제한에 여유를 두기 위해 280자를 넘을 수 없어요."
        />
      </Card>
      </div>

      <div id="persona-panel-publishing" role="tabpanel" aria-labelledby="persona-tab-publishing" hidden={tab !== "publishing"}>
      <Card className="persona-page__section">
        <h2>자동 게시</h2>
        {/* ★ 저장 버튼을 거치지 않고 즉시 반영한다 — 개인정보 보호법 제37조의2 거부권 행사이고,
            처리방침 §9.4 가 "접수 즉시 중지한다" 고 약속한 동작이다. */}
        <label className="persona-page__switch">
          <input
            type="checkbox"
            checked={autoPublish}
            onChange={(e) => void toggleAutoPublish(e.target.checked)}
          />
          <span>답글을 자동으로 게시해요</span>
        </label>
        <p className="field__hint">켜면 안전 검사를 통과한 답글을 별도 승인 없이 게시해요. 끄면 답글은 만들어지지만 직접 확인하고 게시해야 해요.</p>
        <p className="persona-page__auto-publish-notice" role="status">
          {autoPublish
            ? "안전 검사를 통과한 답글은 승인 없이 자동 게시돼요. 위험·가드레일 차단 건은 게시하지 않아요."
            : "자동 게시를 중지했어요. 답글은 그대로 만들어 두고, 게시는 사장님이 직접 하시게 돼요."}
        </p>
        <Field
          label="게시 지연 시간(시간)"
          type="number"
          min={0}
          value={persona.delayHours}
          onChange={(e) => update({ delayHours: Number(e.target.value) })}
          error={fieldErrors.delayHours}
          hint="자동 게시를 켠 경우 답글을 만든 뒤 게시 전까지 기다릴 시간이에요. 운영 상황에 맞춰 게시를 늦출 때 사용해요."
        />

        <h2>게시 가능 시간대</h2>
        <p className="field__hint">자동 게시가 가능한 시간을 제한해요. 비워 두면 별도 시간대 제한 없이 예약 규칙에 따라 게시해요.</p>
        {persona.publishWindows.length === 0 ? <p className="field__hint">시간대 제한 없음</p> : null}
        <ul className="persona-page__window-list">
          {persona.publishWindows.map((w, i) => (
            <li key={i} className="persona-page__window-row">
              <label>
                시작
                <input type="time" value={w.start} onChange={(e) => updateWindow(i, { start: e.target.value })} />
              </label>
              <label>
                종료
                <input type="time" value={w.end} onChange={(e) => updateWindow(i, { end: e.target.value })} />
              </label>
              <Button
                type="button"
                variant="danger"
                small
                onClick={() => update({ publishWindows: persona.publishWindows.filter((_, idx) => idx !== i) })}
              >
                삭제
              </Button>
              {fieldErrors[`publishWindows[${i}]`] ? (
                <p className="field__error" role="alert">
                  {fieldErrors[`publishWindows[${i}]`]}
                </p>
              ) : null}
            </li>
          ))}
        </ul>
        <Button
          type="button"
          variant="secondary"
          onClick={() => update({ publishWindows: [...persona.publishWindows, { start: "10:00", end: "11:00" }] })}
        >
          시간대 추가
        </Button>
      </Card>
      </div>

      <div id="persona-panel-examples" role="tabpanel" aria-labelledby="persona-tab-examples" hidden={tab !== "examples"}>
      <PersonaPreview storeId={storeId} persona={persona} />
      <StyleSamples storeId={storeId} toast={toast} />
      </div>
    </div>
  );
}

interface ToastApi {
  show: (message: string, tone?: "info" | "success" | "danger") => void;
}

function PersonaPreview({ storeId, persona }: { storeId: string; persona: PersonaRequest }) {
  const [reviews, setReviews] = useState<ReviewSummary[] | null>(null);
  const [reviewId, setReviewId] = useState("");
  const [result, setResult] = useState<PreviewResponse | null>(null);
  const [blockNotice, setBlockNotice] = useState<{ kind: "risk" | "guardrail"; reasons: string[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    reviewsApi
      .list(storeId, { size: 30 })
      .then((res) => setReviews(res.items))
      .catch(() => setReviews([]));
  }, [storeId]);

  const handlePreview = async () => {
    if (!reviewId) return;
    setLoading(true);
    setResult(null);
    setBlockNotice(null);
    setError(null);
    try {
      const res = await personaApi.preview(storeId, reviewId, persona);
      setResult(res);
    } catch (e) {
      if (e instanceof ApiError && e.code === "RISK_LEVEL_TOO_HIGH") {
        setBlockNotice({ kind: "risk", reasons: (e.details?.riskReasons as string[] | undefined) ?? [] });
      } else if (e instanceof ApiError && e.code === "GUARDRAIL_BLOCKED") {
        setBlockNotice({ kind: "guardrail", reasons: (e.details?.flags as string[] | undefined) ?? [] });
      } else {
        setError(e instanceof ApiError ? e.message : "미리보기를 생성하지 못했어요.");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="persona-page__section">
      <h2>미리보기</h2>
      <p className="field__hint">저장하지 않은 현재 설정값으로 실제 리뷰 1건에 대한 답글을 미리 생성해 봐요.</p>

      {reviews === null ? <Skeleton height={44} /> : null}
      {reviews && reviews.length === 0 ? <EmptyState title="미리보기에 사용할 리뷰가 없어요" /> : null}
      {reviews && reviews.length > 0 ? (
        <Select label="미리보기 대상 리뷰" value={reviewId} onChange={(e) => setReviewId(e.target.value)}>
          <option value="">선택해 주세요</option>
          {reviews.map((r) => (
            <option key={r.id} value={r.id}>
              {r.rating != null ? `★${r.rating} ` : ""}
              {(r.body ?? "(본문 없음)").slice(0, 30)}
            </option>
          ))}
        </Select>
      ) : null}

      <Button type="button" onClick={handlePreview} loading={loading} disabled={!reviewId}>
        미리보기 생성
      </Button>

      {error ? <p className="persona-page__preview-error">{error}</p> : null}

      {blockNotice ? (
        <div className="queue-item__blocked-notice" role="alert">
          <strong>{blockNotice.kind === "risk" ? "⚠ 위험도가 높아 미리보기를 생성할 수 없어요." : "⚠ 생성된 답글이 안전 규칙에 걸렸어요."}</strong>
          <ul>
            {blockNotice.reasons.map((r) => (
              <li key={r}>{blockNotice.kind === "risk" ? describeRiskReason(r) : describeGuardrailFlag(r)}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {result ? (
        <div className="persona-page__preview-result">
          <Badge tone="info">{describeGeneratedBy("AI")}</Badge>
          <p className="persona-page__preview-content">{result.content}</p>
          <p className="field__hint">
            모델 등급 {result.tier ?? "-"} · {result.model ?? "-"} · 프롬프트 버전 {result.promptVersion ?? "-"}
          </p>
          {result.guardrailFlags.length > 0 ? (
            <ul>
              {result.guardrailFlags.map((f) => (
                <li key={f}>{describeGuardrailFlag(f)}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
}

const STYLE_PAGE_SIZE = 10;

function StyleSamples({ storeId, toast }: { storeId: string; toast: ToastApi }) {
  const [page, setPage] = useState(0);
  const [items, setItems] = useState<StyleSampleResponse[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [manualCount, setManualCount] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<StyleSampleResponse | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [newStyle, setNewStyle] = useState("");
  const [adding, setAdding] = useState(false);

  const load = () => {
    setItems(null);
    setError(null);
    personaApi
      .styleSamples(storeId, page, STYLE_PAGE_SIZE)
      .then((res) => {
        setItems(res.items);
        setHasMore(res.hasMore);
        setManualCount(res.manualCount);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "말투 학습 샘플을 불러오지 못했어요."));
  };

  useEffect(load, [storeId, page]);

  const handleAdd = async () => {
    const replyText = newStyle.trim();
    if (!replyText) return;
    setAdding(true);
    try {
      await personaApi.addStyleSample(storeId, replyText);
      setNewStyle("");
      toast.show("답글 형식을 등록했어요.", "success");
      setPage(0);
      load();
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "등록에 실패했어요.", "danger");
    } finally {
      setAdding(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await personaApi.deleteStyleSample(storeId, deleteTarget.id);
      toast.show("삭제했어요.", "success");
      setDeleteTarget(null);
      load();
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "삭제에 실패했어요.", "danger");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Card className="persona-page__section">
      <h2>답글 형식</h2>
      <p className="field__hint">원하는 답글 예시를 최대 3건 등록하세요. 미입력 시 통합 기본 형식을 적용해요.</p>
      <div className={`persona-page__format-status ${manualCount === 0 ? "persona-page__format-status--default" : ""}`}
        role="status" aria-live="polite">
        <strong>{manualCount === 0 ? "통합 기본 형식 적용 중" : `직접 등록 형식 ${manualCount}/3건 적용 중`}</strong>
        <span>{manualCount === 0 ? "등록하지 않아도 업종 공통 안전 형식으로 자동으로 운영돼요." : "등록한 문장은 말투 참고용이며 그대로 복사되지 않아요."}</span>
      </div>
      <div className="persona-page__tag-input">
        <Field label="답글 예시 (최대 3건)" value={newStyle} maxLength={280}
          onChange={(e) => setNewStyle(e.target.value)} hint={`${newStyle.length}/280자`} />
        <Button type="button" variant="secondary" onClick={handleAdd} loading={adding}
          disabled={!newStyle.trim() || manualCount >= 3}>등록</Button>
      </div>

      {items === null && !error ? <Skeleton height={120} /> : null}
      {error ? <EmptyState title="불러오지 못했어요" description={error} /> : null}
      {items && items.length === 0 ? <EmptyState title="아직 수집된 샘플이 없어요" /> : null}

      {items && items.length > 0 ? (
        <ul className="persona-page__sample-list">
          {items.map((s) => (
            <li key={s.id} className="persona-page__sample">
              <p className="persona-page__sample-review">
                {s.rating != null ? `★${s.rating} · ` : ""}
                {s.reviewText}
              </p>
              <p className="persona-page__sample-reply">{s.replyText}</p>
              <Badge tone={s.source === "MANUAL" ? "info" : "neutral"}>
                {s.source === "MANUAL" ? "직접 등록" : "기존 답글 학습"}
              </Badge>
              <Button type="button" variant="danger" small onClick={() => setDeleteTarget(s)}>
                삭제
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {items && items.length > 0 ? <Pagination page={page} hasMore={hasMore} onPageChange={setPage} /> : null}

      <Modal
        open={deleteTarget !== null}
        title="샘플을 삭제하시겠습니까?"
        onClose={() => setDeleteTarget(null)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setDeleteTarget(null)}>
              취소
            </Button>
            <Button type="button" variant="danger" onClick={handleDelete} loading={deleting}>
              삭제해요
            </Button>
          </>
        }
      >
        <p>
          <strong>삭제하면 되돌릴 수 없어요.</strong> 이 자료는 답글 말투 학습의 핵심 자산이에요.
        </p>
      </Modal>
    </Card>
  );
}
