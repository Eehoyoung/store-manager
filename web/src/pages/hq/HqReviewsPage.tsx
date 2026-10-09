import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqFeatures, HqReviewDetail, HqReviewListItem, HqStore } from "../../api/types";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Select } from "../../components/Select";
import { Field } from "../../components/Field";
import { Skeleton } from "../../components/Skeleton";
import { DRAFT_STATUS_META } from "../../components/draftStatus";
import { describeCategory, describePlatform, describeRiskReason } from "../../lib/labels";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

const RATING_OPTIONS = [1, 2, 3, 4, 5];
const RISK_OPTIONS = [0, 1, 2, 3];
const PAGE_SIZE = 20;

function replyStatusLabel(code: string | null): string {
  if (!code) return "미확인";
  return DRAFT_STATUS_META[code as keyof typeof DRAFT_STATUS_META]?.label ?? code;
}

interface Filters {
  from: string;
  to: string;
  storeId: string;
  platform: string;
  rating: string;
  status: string;
  riskLevel: string;
}

function defaultFilters(): Filters {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 29); // 기본 최근 30일(문서 26a hq.reviews)
  const iso = (d: Date) => d.toISOString().slice(0, 10);
  return { from: iso(from), to: iso(to), storeId: "", platform: "", rating: "", status: "", riskLevel: "" };
}

/**
 * 문서 26 §6.5, 26a hq.reviews — 개별 리뷰 조회. HQ_REVIEW_ACCESS_ENABLED 가 꺼져 있으면
 * 서버가 404 를 주므로 진입 전에 /hq/features 로 먼저 확인해 홈으로 돌려보낸다.
 *
 * ★ 필터는 URL 이 아니라 컴포넌트 state 에만 둔다(문서 26a web.ux) — 리뷰 본문·검색어가
 * 주소창·공유 링크·브라우저 이력에 남지 않게 하기 위함이다.
 */
export function HqReviewsPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [features, setFeatures] = useState<HqFeatures | null>(null);
  const [stores, setStores] = useState<HqStore[]>([]);
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [items, setItems] = useState<HqReviewListItem[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [cursorStack, setCursorStack] = useState<(string | null)[]>([null]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [selected, setSelected] = useState<HqReviewDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    hqApi.features().then(setFeatures).catch(() => setFeatures({ reviewAccessEnabled: false }));
    hqApi.stores(brand).then(setStores).catch(() => setStores([]));
  }, [brand]);

  const currentCursor = cursorStack[cursorStack.length - 1] ?? null;

  useEffect(() => {
    if (features && !features.reviewAccessEnabled) return; // 홈으로 리다이렉트는 아래에서 처리
    setError(null);
    setNotFound(false);
    setLoadingMore(currentCursor !== null);
    hqApi
      .reviews(brand, {
        from: filters.from || undefined,
        to: filters.to || undefined,
        storeId: filters.storeId || undefined,
        platform: filters.platform || undefined,
        rating: filters.rating ? Number(filters.rating) : undefined,
        status: filters.status || undefined,
        riskLevel: filters.riskLevel ? Number(filters.riskLevel) : undefined,
        cursor: currentCursor ?? undefined,
        size: PAGE_SIZE,
      })
      .then((res) => {
        setItems((prev) => (currentCursor ? [...(prev ?? []), ...res.items] : res.items));
        setNextCursor(res.nextCursor);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "개별 리뷰를 불러오지 못했어요.");
      })
      .finally(() => setLoadingMore(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brand, filters, currentCursor, retryTick, features]);

  const applyFilter = (patch: Partial<Filters>) => {
    setItems(null);
    setCursorStack([null]);
    setFilters((f) => ({ ...f, ...patch }));
  };

  const openDetail = (reviewId: string) => {
    setDetailError(null);
    setSelected(null);
    hqApi
      .review(brand, reviewId)
      .then(setSelected)
      .catch((e) => setDetailError(e instanceof ApiError ? e.message : "리뷰 상세를 불러오지 못했어요."));
  };

  if (notFound) return <HqAccessDenied />;

  if (features && !features.reviewAccessEnabled) {
    return (
      <div className="hq-page">
        <HqNav brand={brand} />
        <EmptyState title="개별 리뷰 조회를 사용할 수 없어요" description="이 브랜드는 개별 리뷰 제공 동의 범위가 열려 있지 않아요." />
      </div>
    );
  }

  return (
    <div className="hq-page">
      <HqNav brand={brand} />
      <h1>개별 리뷰</h1>
      <p className="hq-page__note">읽기 전용이에요. 답글 처리·승인·게시 버튼은 없어요.</p>

      <Card className="hq-filters">
        <Field label="시작일" type="date" value={filters.from} onChange={(e) => applyFilter({ from: e.target.value })} />
        <Field label="종료일" type="date" value={filters.to} onChange={(e) => applyFilter({ to: e.target.value })} />
        <Select label="매장" value={filters.storeId} onChange={(e) => applyFilter({ storeId: e.target.value })}>
          <option value="">전체</option>
          {stores.map((s) => (
            <option key={s.storeId} value={s.storeId}>{s.name}</option>
          ))}
        </Select>
        <Select label="플랫폼" value={filters.platform} onChange={(e) => applyFilter({ platform: e.target.value })}>
          <option value="">전체</option>
          <option value="BAEMIN">배달의민족</option>
          <option value="YOGIYO">요기요</option>
          <option value="COUPANGEATS">쿠팡이츠</option>
        </Select>
        <Select label="별점" value={filters.rating} onChange={(e) => applyFilter({ rating: e.target.value })}>
          <option value="">전체</option>
          {RATING_OPTIONS.map((n) => <option key={n} value={n}>{n}점</option>)}
        </Select>
        <Select label="위험도" value={filters.riskLevel} onChange={(e) => applyFilter({ riskLevel: e.target.value })}>
          <option value="">전체</option>
          {RISK_OPTIONS.map((n) => <option key={n} value={n}>{n} 이상</option>)}
        </Select>
      </Card>

      {items === null && !error ? (
        <div className="hq-page__list">
          <Skeleton height={120} />
          <Skeleton height={120} />
        </div>
      ) : null}

      {error ? (
        <EmptyState
          title="개별 리뷰를 불러오지 못했어요"
          description={error}
          action={<Button type="button" onClick={() => setRetryTick((t) => t + 1)}>다시 시도</Button>}
        />
      ) : null}

      {items && items.length === 0 ? <EmptyState title="조건에 해당하는 리뷰가 없어요" /> : null}

      {items && items.length > 0 ? (
        <div className="hq-two-col">
          <ul className="hq-review-list">
            {items.map((r) => (
              <li key={r.reviewId}>
                <button type="button" className="hq-review hq-review--button" onClick={() => openDetail(r.reviewId)}>
                  <ReviewSummaryRow item={r} />
                </button>
              </li>
            ))}
          </ul>
          <Card>
            {detailError ? <p className="auth-card__error" role="alert">{detailError}</p> : null}
            {!detailError && !selected ? <EmptyState title="목록에서 리뷰를 선택하면 전체 내용을 볼 수 있어요" /> : null}
            {selected ? <ReviewDetailPanel review={selected} /> : null}
          </Card>
        </div>
      ) : null}

      {items && items.length > 0 && nextCursor ? (
        <Button
          type="button"
          variant="secondary"
          loading={loadingMore}
          onClick={() => setCursorStack((s) => [...s, nextCursor])}
        >
          더 보기
        </Button>
      ) : null}
    </div>
  );
}

function ReviewSummaryRow({ item }: { item: HqReviewListItem }) {
  return (
    <>
      <div className="hq-review__head">
        <span className="hq-review__store">{item.storeName}</span>
        <span className="hq-review__platform">{describePlatform(item.platform)}</span>
        {item.rating != null ? <Badge tone="neutral">{item.rating}점</Badge> : null}
        {(item.riskLevel ?? 0) >= 3 ? <Badge tone="danger" icon="⚠">고위험</Badge> : null}
      </div>
      <div className="hq-review__meta">
        <span>{item.writtenAt ? new Date(item.writtenAt).toLocaleDateString("ko-KR") : "작성일 미상"}</span>
        <span>{item.authorDisplay}</span>
        <Badge tone={DRAFT_STATUS_META[item.replyStatus as keyof typeof DRAFT_STATUS_META]?.tone ?? "neutral"}>
          {replyStatusLabel(item.replyStatus)}
        </Badge>
      </div>
      <p className="hq-review__body">{item.excerpt}</p>
    </>
  );
}

function ReviewDetailPanel({ review }: { review: HqReviewDetail }) {
  return (
    <>
      <div className="hq-review__head">
        <span className="hq-review__store">{review.storeName}</span>
        <span className="hq-review__platform">{describePlatform(review.platform)}</span>
        {review.rating != null ? <Badge tone="neutral">{review.rating}점</Badge> : null}
      </div>
      <div className="hq-review__meta">
        <span>{review.writtenAt ? new Date(review.writtenAt).toLocaleString("ko-KR") : "작성일 미상"}</span>
        <span>{review.authorDisplay}</span>
        <Badge tone={DRAFT_STATUS_META[review.replyStatus as keyof typeof DRAFT_STATUS_META]?.tone ?? "neutral"}>
          {replyStatusLabel(review.replyStatus)}
        </Badge>
      </div>
      <p className="hq-review__body">{review.body}</p>
      <div className="hq-review__analysis">
        {review.category ? <Badge tone="info">{describeCategory(review.category)}</Badge> : null}
        {review.issueTags.map((t) => <Badge key={t} tone="neutral">{t}</Badge>)}
        {review.riskReasons.map((r) => <Badge key={r} tone="danger">{describeRiskReason(r)}</Badge>)}
      </div>
    </>
  );
}
