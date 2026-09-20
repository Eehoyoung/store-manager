import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { reviewsApi } from "../api/reviews";
import { canApproveBlockedDraft, draftsApi, DRAFT_CONTENT_MAX_LENGTH } from "../api/drafts";
import type { ReviewDetail, ReviewSummary } from "../api/types";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { Badge } from "../components/Badge";
import { Select } from "../components/Select";
import { Field } from "../components/Field";
import { Modal } from "../components/Modal";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { Button } from "../components/Button";
import { useToast } from "../components/Toast";
import { DRAFT_STATUS_META } from "../components/draftStatus";
import {
  describeCategory,
  describeGeneratedBy,
  describeGuardrailFlag,
  describePlatform,
  describeRiskReason,
} from "../lib/labels";
import { useShellStore } from "../layout/AppShell";

const CATEGORY_OPTIONS = ["PRAISE", "POSITIVE", "IMPROVEMENT", "COMPLAINT", "ABUSIVE", "NOISE"];
const RATING_OPTIONS = [1, 2, 3, 4, 5];
const RISK_OPTIONS = [0, 1, 2, 3];
const PAGE_SIZE = 10;

// ★ 절대규칙 3: riskLevel>=3 리뷰를 눈에 띄게 구분한다.
const HIGH_RISK_THRESHOLD = 3;

export function ReviewsPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();
  const [searchParams, setSearchParams] = useSearchParams();

  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  const category = searchParams.get("category") ?? "";
  const minRating = searchParams.get("minRating") ?? "";
  const maxRating = searchParams.get("maxRating") ?? "";
  const riskLevel = searchParams.get("riskLevel") ?? "";
  const hasReply = searchParams.get("hasReply") ?? "";
  const from = searchParams.get("from") ?? "";
  const to = searchParams.get("to") ?? "";

  // 필터 값을 URL 쿼리스트링에 반영한다(새로고침·뒤로가기에도 유지). 필터가 바뀌면 1페이지로 되돌린다.
  const updateFilter = (patch: Record<string, string>) => {
    const next = new URLSearchParams(searchParams);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setCursor(null);
    setCursorHistory([]);
    setSearchParams(next);
  };

  const [items, setItems] = useState<ReviewSummary[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [cursorHistory, setCursorHistory] = useState<(string | null)[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    setItems(null);
    setLoadError(null);
    if (!storeId) return;
    reviewsApi
      .list(storeId, {
        category: category || undefined,
        minRating: minRating ? Number(minRating) : undefined,
        maxRating: maxRating ? Number(maxRating) : undefined,
        riskLevel: riskLevel ? Number(riskLevel) : undefined,
        hasReply: hasReply ? hasReply === "true" : undefined,
        from: from || undefined,
        to: to || undefined,
        cursor: cursor || undefined,
        size: PAGE_SIZE,
      })
      .then((res) => {
        setItems(res.items);
        setHasMore(res.hasMore);
        setNextCursor(res.nextCursor);
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "리뷰 목록을 불러오지 못했습니다."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeId, category, minRating, maxRating, riskLevel, hasReply, from, to, cursor, retryTick]);

  if (!storeId) {
    return <EmptyState title="매장을 먼저 선택해 주세요" />;
  }

  return (
    <div className="reviews-page">
      <div className="reviews-page__header">
        <h1>리뷰 목록</h1>
      </div>

      <Card className="reviews-page__filters">
        <Select label="카테고리" value={category} onChange={(e) => updateFilter({ category: e.target.value })}>
          <option value="">전체</option>
          {CATEGORY_OPTIONS.map((c) => (
            <option key={c} value={c}>
              {describeCategory(c)}
            </option>
          ))}
        </Select>
        <Select label="최소 별점" value={minRating} onChange={(e) => updateFilter({ minRating: e.target.value })}>
          <option value="">전체</option>
          {RATING_OPTIONS.map((n) => (
            <option key={n} value={n}>
              {n}점 이상
            </option>
          ))}
        </Select>
        <Select label="최대 별점" value={maxRating} onChange={(e) => updateFilter({ maxRating: e.target.value })}>
          <option value="">전체</option>
          {RATING_OPTIONS.map((n) => (
            <option key={n} value={n}>
              {n}점 이하
            </option>
          ))}
        </Select>
        <Select label="답글 유무" value={hasReply} onChange={(e) => updateFilter({ hasReply: e.target.value })}>
          <option value="">전체</option>
          <option value="true">답글 있음</option>
          <option value="false">답글 없음</option>
        </Select>
        <Select label="위험도" value={riskLevel} onChange={(e) => updateFilter({ riskLevel: e.target.value })}>
          <option value="">전체</option>
          {RISK_OPTIONS.map((n) => (
            <option key={n} value={n}>
              {n}{n >= HIGH_RISK_THRESHOLD ? " 이상 — 고위험" : " 이상"}
            </option>
          ))}
        </Select>
        <Field
          label="시작일"
          type="date"
          value={from}
          onChange={(e) => updateFilter({ from: e.target.value })}
        />
        <Field label="종료일" type="date" value={to} onChange={(e) => updateFilter({ to: e.target.value })} />
      </Card>

      {items === null && !loadError ? (
        <div className="reviews-page__list">
          <Skeleton height={160} />
          <Skeleton height={160} />
        </div>
      ) : null}

      {loadError ? (
        <EmptyState
          title="리뷰 목록을 불러오지 못했습니다"
          description={loadError}
          action={
            <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {items && items.length === 0 ? (
        <EmptyState title="표시할 리뷰가 없습니다" description="선택한 조건에 해당하는 리뷰가 없습니다." />
      ) : null}

      {items && items.length > 0 ? (
        <ul className="reviews-page__list">
          {items.map((r) => (
            <li key={r.id}>
              <ReviewCard review={r} onOpen={() => setSelectedId(r.id)} />
            </li>
          ))}
        </ul>
      ) : null}

      {items && items.length > 0 ? (
        <nav className="pagination" aria-label="리뷰 페이지 이동">
          <Button
            type="button"
            disabled={cursorHistory.length === 0}
            onClick={() => {
              const previous = cursorHistory[cursorHistory.length - 1] ?? null;
              setCursorHistory((history) => history.slice(0, -1));
              setCursor(previous);
            }}
          >
            이전
          </Button>
          <span className="pagination__label" aria-current="page">{cursorHistory.length + 1} 페이지</span>
          <Button
            type="button"
            disabled={!hasMore || !nextCursor}
            onClick={() => {
              setCursorHistory((history) => [...history, cursor]);
              setCursor(nextCursor);
            }}
          >
            다음
          </Button>
        </nav>
      ) : null}

      <ReviewDetailModal
        reviewId={selectedId}
        onClose={() => setSelectedId(null)}
        onDraftChanged={() => setRetryTick((t) => t + 1)}
      />
    </div>
  );
}

function ReviewCard({ review, onOpen }: { review: ReviewSummary; onOpen: () => void }) {
  const analysis = review.analysis;
  const highRisk = (analysis?.riskLevel ?? 0) >= HIGH_RISK_THRESHOLD;
  const meta = review.draft ? DRAFT_STATUS_META[review.draft.status] : null;
  const generatedByLabel = describeGeneratedBy(review.draft?.generatedBy);

  return (
    <Card
      className={`review-card ${highRisk ? "review-card--risk" : ""}`}
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onOpen();
        }
      }}
    >
      <div className="review-card__head">
        {review.rating != null ? (
          <span aria-label={`별점 ${review.rating}점`}>
            {"★".repeat(review.rating)}
            {"☆".repeat(Math.max(0, 5 - review.rating))}
          </span>
        ) : (
          <span>별점 없음</span>
        )}
        <span className="review-card__platform">{describePlatform(review.platform)}</span>
        <span className="review-card__author">{review.authorMasked}</span>
        {meta ? (
          <Badge tone={meta.tone} icon={meta.icon}>
            {meta.label}
          </Badge>
        ) : (
          <Badge tone="neutral">초안 없음</Badge>
        )}
        {generatedByLabel ? <Badge tone="info">{generatedByLabel}</Badge> : null}
        {highRisk ? (
          <Badge tone="danger" icon="⚠">
            고위험
          </Badge>
        ) : null}
      </div>

      <p className="review-card__body">{review.body ?? "(본문 없는 리뷰입니다 — 사진만 등록되었을 수 있습니다)"}</p>

      {review.orderedMenus.length > 0 ? (
        <p className="review-card__menus">주문 메뉴: {review.orderedMenus.join(", ")}</p>
      ) : null}

      {analysis ? (
        <div className="review-card__analysis">
          {analysis.category ? <Badge tone="info">{describeCategory(analysis.category)}</Badge> : null}
          {analysis.issueTags.map((t) => (
            <Badge key={t} tone="neutral">
              {t}
            </Badge>
          ))}
        </div>
      ) : null}

      {highRisk && analysis ? (
        <div className="queue-item__blocked-notice" role="alert">
          <strong>⚠ 사람이 직접 확인해야 하는 리뷰입니다.</strong>
          {analysis.riskReasons.length > 0 ? (
            <ul>
              {analysis.riskReasons.map((r) => (
                <li key={r}>{describeRiskReason(r)}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
}

// ★ 절대규칙 1: 리뷰 본문 조회는 읽기 전용이다 — 여기서 만드는 것은 리뷰가 아니라 "이미 있는
// 위험 초안"에 대한 사람 승인·거절이다(RiskApprovalController, 2026-08-27 신설). 리뷰 본문을
// 생성·수정하는 코드는 추가하지 않는다.
function ReviewDetailModal({
  reviewId,
  onClose,
  onDraftChanged,
}: {
  reviewId: string | null;
  onClose: () => void;
  onDraftChanged: () => void;
}) {
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = () => {
    if (!reviewId) return;
    reviewsApi
      .get(reviewId)
      .then(setDetail)
      .catch((e) => setError(e instanceof ApiError ? e.message : "리뷰 상세를 불러오지 못했습니다."));
  };

  useEffect(() => {
    if (!reviewId) {
      setDetail(null);
      setError(null);
      return;
    }
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reviewId]);

  return (
    <Modal open={reviewId !== null} title="리뷰 상세" onClose={onClose}>
      {!detail && !error ? <Skeleton height={200} /> : null}
      {error ? <p className="review-detail__error">{error}</p> : null}
      {detail ? (
        <div className="review-detail">
          <div className="review-card__head">
            {detail.rating != null ? (
              <span aria-label={`별점 ${detail.rating}점`}>
                {"★".repeat(detail.rating)}
                {"☆".repeat(Math.max(0, 5 - detail.rating))}
              </span>
            ) : (
              <span>별점 없음</span>
            )}
            <span className="review-card__platform">{describePlatform(detail.platform)}</span>
            <span className="review-card__author">{detail.authorMasked}</span>
          </div>
          <p className="review-detail__body">{detail.body ?? "(본문 없는 리뷰입니다)"}</p>
          {detail.orderedMenus.length > 0 ? <p>주문 메뉴: {detail.orderedMenus.join(", ")}</p> : null}

          {detail.analysis ? (
            <div className="review-detail__analysis">
              <h2>분석 결과</h2>
              <p>카테고리: {detail.analysis.category ? describeCategory(detail.analysis.category) : "미분류"}</p>
              {detail.analysis.issueTags.length > 0 ? <p>이슈 태그: {detail.analysis.issueTags.join(", ")}</p> : null}
              {(detail.analysis.riskLevel ?? 0) >= HIGH_RISK_THRESHOLD ? (
                <div className="queue-item__blocked-notice" role="alert">
                  <strong>⚠ 고위험 리뷰 — 위험도 {detail.analysis.riskLevel}</strong>
                  <ul>
                    {detail.analysis.riskReasons.map((r) => (
                      <li key={r}>{describeRiskReason(r)}</li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </div>
          ) : null}

          <h2>초안 이력</h2>
          {detail.drafts.length === 0 ? (
            <EmptyState title="아직 생성된 답글이 없습니다" />
          ) : (
            <ul className="review-detail__drafts">
              {detail.drafts.map((d, idx) => {
                const meta = DRAFT_STATUS_META[d.status];
                const generatedByLabel = describeGeneratedBy(d.generatedBy);
                // drafts 는 재생성 이력 최신순이다 — 승인·거절은 가장 최근 초안(idx===0)에만 연다.
                const isLatestBlocked = idx === 0 && d.status === "BLOCKED";
                // 예약된 답글은 게시 전까지 멈출 수 있어야 한다 — 없으면 지연 시간이 지나면 그대로 나간다.
                const isCancelable = idx === 0 && d.status === "SCHEDULED";
                return (
                  <li key={d.id} className="review-detail__draft">
                    <Badge tone={meta.tone} icon={meta.icon}>
                      {meta.label}
                    </Badge>
                    {generatedByLabel ? <Badge tone="info">{generatedByLabel}</Badge> : null}
                    <p>{d.content}</p>
                    {isCancelable ? (
                      <CancelScheduledPanel
                        draftId={d.id}
                        scheduledAt={d.scheduledAt}
                        onDone={() => {
                          reload();
                          onDraftChanged();
                        }}
                      />
                    ) : null}
                    {isLatestBlocked ? (
                      <RiskApprovalPanel
                        draftId={d.id}
                        originalContent={d.content}
                        riskReasons={detail.analysis?.riskReasons ?? []}
                        guardrailFlags={d.guardrailFlags ?? []}
                        onDone={() => {
                          reload();
                          onDraftChanged();
                        }}
                      />
                    ) : null}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ) : null}
    </Modal>
  );
}

/**
 * 예약된 답글을 게시 전에 멈추는 패널.
 *
 * ★ 이것이 없으면 위험도 2(화·분노) 답글이 지연 시간이 지나면 그대로 나가고 사장님이 막을
 *   길이 없다. 약관 제6조 제4항이 "게시 전에 취소할 수 있다" 고 말하는 것의 실효 수단이다.
 *
 * ★ 서버가 거절할 수 있다. 워커로 이미 넘어간 건은 상태만 바꿔 봐야 답글이 나가므로
 *   서버가 취소를 거부한다 — 그 메시지를 그대로 보여준다. "취소됐다" 고 거짓말하지 않는다.
 */
function CancelScheduledPanel({
  draftId,
  scheduledAt,
  onDone,
}: {
  draftId: string;
  scheduledAt: string | null;
  onDone: () => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCancel = async () => {
    setBusy(true);
    setError(null);
    try {
      await draftsApi.cancel(draftId);
      toast.show("게시하지 않기로 했습니다.", "info");
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "취소 처리 중 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="queue-item__scheduled-notice" role="group" aria-label="예약된 답글">
      <p>
        {scheduledAt
          ? `${new Date(scheduledAt).toLocaleString("ko-KR")}에 게시될 예정입니다.`
          : "게시 예정입니다."}{" "}
        그 전까지는 멈추실 수 있습니다.
      </p>
      {error ? <p className="field__error" role="alert">{error}</p> : null}
      <Button type="button" variant="secondary" loading={busy} onClick={() => void handleCancel()}>
        게시하지 않기
      </Button>
    </div>
  );
}

/**
 * 위험 초안의 사람 승인·거절 (약관 제6조 4항이 약속한 권리).
 *
 * ★ 화면 검사는 사용자 편의다. 최종 안전 판정은 항상 서버가 한다
 * (RiskApprovalService.APPROVABLE_FLAG — RISK_LEVEL_TOO_HIGH 단독일 때만 승인).
 * 여기서 미리 막는 이유는 사장님이 버튼을 누르고 나서 422 를 받는 일을 없애기 위해서다.
 */
function RiskApprovalPanel({
  draftId,
  originalContent,
  riskReasons,
  guardrailFlags,
  onDone,
}: {
  draftId: string;
  originalContent: string;
  riskReasons: string[];
  guardrailFlags: string[];
  onDone: () => void;
}) {
  const toast = useToast();
  const [content, setContent] = useState(originalContent);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const judgement = canApproveBlockedDraft({ guardrailFlags, riskAcknowledged: acknowledged, content });
  const overLimit = content.length > DRAFT_CONTENT_MAX_LENGTH;

  const handleApprove = async () => {
    setBusy(true);
    setActionError(null);
    try {
      // 수정하지 않았으면 content 를 보내지 않는다 — AI 초안이 그대로 게시된다.
      const edited = content.trim() === originalContent.trim() ? undefined : content.trim();
      await draftsApi.approve(draftId, { riskAcknowledged: acknowledged, content: edited });
      toast.show("승인했습니다. 예정된 시간에 게시됩니다.", "success");
      onDone();
    } catch (e) {
      if (e instanceof ApiError && e.code === "GUARDRAIL_BLOCKED") {
        const flags = Array.isArray(e.details?.flags) ? (e.details?.flags as string[]) : [];
        setActionError(
          `이 답글은 다른 안전규칙도 위반해 승인할 수 없습니다.${
            flags.length > 0 ? " (" + flags.map(describeGuardrailFlag).join(", ") + ")" : ""
          }`,
        );
      } else {
        setActionError(e instanceof ApiError ? e.message : "승인 처리 중 오류가 발생했습니다.");
      }
    } finally {
      setBusy(false);
    }
  };

  const handleReject = async () => {
    setBusy(true);
    setActionError(null);
    try {
      await draftsApi.reject(draftId);
      toast.show("게시하지 않기로 했습니다.", "info");
      onDone();
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : "거절 처리 중 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="queue-item__blocked-notice" role="group" aria-label="위험 리뷰 승인">
      <strong>⚠ 이 답글은 위험 리뷰로 자동 게시가 멈췄습니다.</strong>
      <p>
        {riskReasons.length > 0
          ? `차단 사유: ${riskReasons.map(describeRiskReason).join(", ")} (위 분석 결과 참고)`
          : "구체적인 차단 사유는 위 분석 결과를 확인해 주세요."}
      </p>

      <label className="queue-item__reply-label" htmlFor={`risk-approval-content-${draftId}`}>
        권장 답글 (필요하면 고쳐서 게시할 수 있습니다)
      </label>
      <textarea
        id={`risk-approval-content-${draftId}`}
        className="queue-item__textarea"
        rows={4}
        value={content}
        onChange={(e) => setContent(e.target.value)}
        disabled={busy}
      />
      <p className={`queue-item__counter ${overLimit ? "queue-item__counter--over" : ""}`}>
        {content.length} / {DRAFT_CONTENT_MAX_LENGTH}자{overLimit ? " — 글자 수를 줄여야 승인할 수 있습니다" : ""}
      </p>

      <label className="persona-page__checkbox">
        <input
          type="checkbox"
          checked={acknowledged}
          onChange={(e) => setAcknowledged(e.target.checked)}
          disabled={busy}
        />
        위 차단 사유를 확인했습니다
      </label>

      <p>
        <strong>게시하면 되돌릴 수 없습니다.</strong> 배달 플랫폼이 답글 수정·삭제 기능을 제공하지 않아
        회사도 이후에 고치거나 지울 수 없습니다.
      </p>

      {!judgement.ok && !actionError ? <p className="field__hint">{judgement.reason}</p> : null}

      {actionError ? (
        <p className="review-detail__error" role="alert">
          {actionError}
        </p>
      ) : null}

      <div className="queue-item__actions">
        <Button type="button" variant="primary" disabled={!judgement.ok || busy} loading={busy} onClick={handleApprove}>
          승인하고 게시 예약
        </Button>
        <Button type="button" variant="secondary" disabled={busy} onClick={handleReject}>
          게시하지 않기
        </Button>
      </div>
    </div>
  );
}
