import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { reviewsApi } from "../api/reviews";
import type { ReviewDetail, ReviewSummary } from "../api/types";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { Badge } from "../components/Badge";
import { Select } from "../components/Select";
import { Field } from "../components/Field";
import { Modal } from "../components/Modal";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { Pagination } from "../components/Pagination";
import { Button } from "../components/Button";
import { DRAFT_STATUS_META } from "../components/draftStatus";
import { describeCategory, describePlatform, describeRiskReason } from "../lib/labels";
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
  const page = Number(searchParams.get("page") ?? 0);

  // 필터 값을 URL 쿼리스트링에 반영한다(새로고침·뒤로가기에도 유지). 필터가 바뀌면 1페이지로 되돌린다.
  const updateFilter = (patch: Record<string, string>) => {
    const next = new URLSearchParams(searchParams);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    next.delete("page");
    setSearchParams(next);
  };

  const setPage = (p: number) => {
    const next = new URLSearchParams(searchParams);
    if (p > 0) next.set("page", String(p));
    else next.delete("page");
    setSearchParams(next);
  };

  const [items, setItems] = useState<ReviewSummary[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
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
        page,
        size: PAGE_SIZE,
      })
      .then((res) => {
        setItems(res.items);
        setHasMore(res.hasMore);
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "리뷰 목록을 불러오지 못했습니다."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storeId, category, minRating, maxRating, riskLevel, hasReply, from, to, page, retryTick]);

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

      {items && items.length > 0 ? <Pagination page={page} hasMore={hasMore} onPageChange={setPage} /> : null}

      <ReviewDetailModal reviewId={selectedId} onClose={() => setSelectedId(null)} />
    </div>
  );
}

function ReviewCard({ review, onOpen }: { review: ReviewSummary; onOpen: () => void }) {
  const analysis = review.analysis;
  const highRisk = (analysis?.riskLevel ?? 0) >= HIGH_RISK_THRESHOLD;
  const meta = review.draft ? DRAFT_STATUS_META[review.draft.status] : null;

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

// ★ 절대규칙 1·3: 여기서는 읽기 전용으로만 보여준다. 승인 경로는 만들지 않는다(승인은 승인 큐에서만).
function ReviewDetailModal({ reviewId, onClose }: { reviewId: string | null; onClose: () => void }) {
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!reviewId) {
      setDetail(null);
      setError(null);
      return;
    }
    reviewsApi
      .get(reviewId)
      .then(setDetail)
      .catch((e) => setError(e instanceof ApiError ? e.message : "리뷰 상세를 불러오지 못했습니다."));
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
              {detail.drafts.map((d) => {
                const meta = DRAFT_STATUS_META[d.status];
                return (
                  <li key={d.id} className="review-detail__draft">
                    <Badge tone={meta.tone} icon={meta.icon}>
                      {meta.label}
                    </Badge>
                    <p>{d.content}</p>
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
