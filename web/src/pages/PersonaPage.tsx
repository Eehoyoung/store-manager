import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { personaApi } from "../api/persona";
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
import { describeGuardrailFlag, describeRiskReason } from "../lib/labels";
import { useShellStore } from "../layout/AppShell";

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
  if (p.lengthMax > 280) errors.lengthMax = "280자를 초과할 수 없습니다.";
  if (p.lengthMin < 1) errors.lengthMin = "1 이상이어야 합니다.";
  if (p.lengthMin > p.lengthMax) errors.lengthMin = "최소 길이는 최대 길이 이하여야 합니다.";
  p.publishWindows.forEach((w, i) => {
    if (!w.start || !w.end || !(w.start < w.end)) {
      errors[`publishWindows[${i}]`] = `시간대 ${i + 1}: 시작 시각은 종료 시각보다 이전이어야 합니다.`;
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

  useEffect(() => {
    if (!storeId) return;
    personaApi
      .get(storeId)
      .then((res) => setPersona(toRequest(res)))
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "페르소나 설정을 불러오지 못했습니다."));
  }, [storeId]);

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
      toast.show("페르소나 설정을 저장했습니다.", "success");
    } catch (e) {
      if (e instanceof ApiError && e.code === "VALIDATION_FAILED") {
        setFieldErrors((e.details?.fields as Record<string, string>) ?? {});
        toast.show(e.message, "danger");
      } else {
        toast.show(e instanceof ApiError ? e.message : "저장에 실패했습니다.", "danger");
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
    return <EmptyState title="페르소나 설정을 불러오지 못했습니다" description={loadError} />;
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

      {bannerErrors.length > 0 ? (
        <Card className="persona-page__error-banner" role="alert">
          <ul>
            {bannerErrors.map(([k, v]) => (
              <li key={k}>{v}</li>
            ))}
          </ul>
        </Card>
      ) : null}

      <Card className="persona-page__section">
        <h2>말투</h2>
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
        <Select
          label="이모지 사용 정도"
          value={String(persona.emojiLevel)}
          onChange={(e) => update({ emojiLevel: Number(e.target.value) })}
          disabled={!persona.useEmoji}
          error={fieldErrors.emojiLevel}
        >
          {[0, 1, 2, 3].map((n) => (
            <option key={n} value={n}>
              {n === 0 ? "0 (사용 안 함)" : n}
            </option>
          ))}
        </Select>

        <Field
          label="고객 호칭"
          value={persona.customerTitle}
          maxLength={20}
          onChange={(e) => update({ customerTitle: e.target.value })}
          error={fieldErrors.customerTitle}
          hint="예: 고객님, 손님"
        />
        <Field
          label="서명"
          value={persona.signature}
          maxLength={100}
          onChange={(e) => update({ signature: e.target.value })}
          error={fieldErrors.signature}
          hint="답글 끝에 붙는 문구입니다."
        />
        <Field
          label="답글 시작 스타일"
          value={persona.openingStyle}
          maxLength={100}
          onChange={(e) => update({ openingStyle: e.target.value })}
          error={fieldErrors.openingStyle}
        />
      </Card>

      <Card className="persona-page__section">
        <h2>금칙어</h2>
        <p className="field__hint">답글에 절대 포함되면 안 되는 단어를 등록합니다.</p>
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
          <p className="field__hint">등록된 금칙어가 없습니다.</p>
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
        />
        <Field
          label="최대 길이"
          type="number"
          min={1}
          max={280}
          value={persona.lengthMax}
          onChange={(e) => update({ lengthMax: Number(e.target.value) })}
          error={fieldErrors.lengthMax}
          hint="플랫폼 제한(300자)에 여유를 둔 하드 제한 280자를 넘을 수 없습니다."
        />
      </Card>

      <Card className="persona-page__section">
        <h2>자동 게시</h2>
        <p className="persona-page__auto-publish-notice" role="alert">
          안전 검사를 통과한 답글은 승인 없이 자동 게시됩니다. 위험·가드레일 차단 건은 게시하지 않습니다.
        </p>
        <Field
          label="게시 지연 시간(시간)"
          type="number"
          min={0}
          value={persona.delayHours}
          onChange={(e) => update({ delayHours: Number(e.target.value) })}
          error={fieldErrors.delayHours}
          hint="답글 생성 후 실제 게시까지 기다리는 시간입니다."
        />

        <h2>게시 가능 시간대</h2>
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

      <Button type="button" onClick={handleSave} loading={saving}>
        저장
      </Button>

      <PersonaPreview storeId={storeId} persona={persona} />
      <StyleSamples storeId={storeId} toast={toast} />
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
        setError(e instanceof ApiError ? e.message : "미리보기를 생성하지 못했습니다.");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="persona-page__section">
      <h2>미리보기</h2>
      <p className="field__hint">저장하지 않은 현재 설정값으로 실제 리뷰 1건에 대한 답글을 미리 생성해 봅니다.</p>

      {reviews === null ? <Skeleton height={44} /> : null}
      {reviews && reviews.length === 0 ? <EmptyState title="미리보기에 사용할 리뷰가 없습니다" /> : null}
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
          <strong>{blockNotice.kind === "risk" ? "⚠ 위험도가 높아 미리보기를 생성할 수 없습니다." : "⚠ 생성된 답글이 안전 규칙에 걸렸습니다."}</strong>
          <ul>
            {blockNotice.reasons.map((r) => (
              <li key={r}>{blockNotice.kind === "risk" ? describeRiskReason(r) : describeGuardrailFlag(r)}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {result ? (
        <div className="persona-page__preview-result">
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
      .catch((e) => setError(e instanceof ApiError ? e.message : "말투 학습 샘플을 불러오지 못했습니다."));
  };

  useEffect(load, [storeId, page]);

  const handleAdd = async () => {
    const replyText = newStyle.trim();
    if (!replyText) return;
    setAdding(true);
    try {
      await personaApi.addStyleSample(storeId, replyText);
      setNewStyle("");
      toast.show("답글 형식을 등록했습니다.", "success");
      setPage(0);
      load();
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "등록에 실패했습니다.", "danger");
    } finally {
      setAdding(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await personaApi.deleteStyleSample(storeId, deleteTarget.id);
      toast.show("삭제했습니다.", "success");
      setDeleteTarget(null);
      load();
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "삭제에 실패했습니다.", "danger");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Card className="persona-page__section">
      <h2>답글 형식</h2>
      <p className="field__hint">원하는 답글 예시를 최대 3건 등록하세요. 미입력 시 통합 기본 형식을 적용합니다.</p>
      <div className={`persona-page__format-status ${manualCount === 0 ? "persona-page__format-status--default" : ""}`}
        role="status" aria-live="polite">
        <strong>{manualCount === 0 ? "통합 기본 형식 적용 중" : `직접 등록 형식 ${manualCount}/3건 적용 중`}</strong>
        <span>{manualCount === 0 ? "등록하지 않아도 업종 공통 안전 형식으로 자동 운영됩니다." : "등록한 문장은 말투 참고용이며 그대로 복사되지 않습니다."}</span>
      </div>
      <div className="persona-page__tag-input">
        <Field label="답글 예시 (최대 3건)" value={newStyle} maxLength={280}
          onChange={(e) => setNewStyle(e.target.value)} hint={`${newStyle.length}/280자`} />
        <Button type="button" variant="secondary" onClick={handleAdd} loading={adding}
          disabled={!newStyle.trim() || manualCount >= 3}>등록</Button>
      </div>

      {items === null && !error ? <Skeleton height={120} /> : null}
      {error ? <EmptyState title="불러오지 못했습니다" description={error} /> : null}
      {items && items.length === 0 ? <EmptyState title="아직 수집된 샘플이 없습니다" /> : null}

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
              삭제합니다
            </Button>
          </>
        }
      >
        <p>
          <strong>삭제하면 되돌릴 수 없습니다.</strong> 이 자료는 답글 말투 학습의 핵심 자산입니다.
        </p>
      </Modal>
    </Card>
  );
}
