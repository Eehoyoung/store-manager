import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqAnalyticsResponse, HqStoreComparisonItem } from "../../api/types";
import { ApiError } from "../../api/client";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/Skeleton";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

type SortKey = "unprocessed" | "rating" | "reviews" | "completion";
type RangeDays = 7 | 30 | 90;

const pct = (v: number) => `${Math.round(v * 100)}%`;
// 표본이 작으면(1~4건) 정확한 수치·비율을 함께 가린다(문서 26 §6.4) — 가려졌음을 "데이터 없음"과
// 구분해 보여준다. 가린 것과 없는 것은 다르다.
const MASKED = "표시 기준 미달";

function dateInputValue(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function range(days: RangeDays) {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - days + 1);
  return { from: dateInputValue(from), to: dateInputValue(to) };
}

// 문서 26 §6.4 — 매장 비교. HqAnalyticsPage 의 storeComparison 과 같은 집계를 전용 화면으로 분리했다.
export function HqComparePage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [rangeDays, setRangeDays] = useState<RangeDays>(30);
  const [data, setData] = useState<HqAnalyticsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [retryTick, setRetryTick] = useState(0);
  const [sortKey, setSortKey] = useState<SortKey>("unprocessed");

  useEffect(() => {
    const selected = range(rangeDays);
    setData(null);
    setError(null);
    setNotFound(false);
    hqApi
      .analytics(brand, selected.from, selected.to)
      .then(setData)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "매장 비교 정보를 불러오지 못했습니다.");
      });
  }, [brand, rangeDays, retryTick]);

  const sortedStores = useMemo(() => {
    if (!data) return [];
    return [...data.storeComparison].sort((a, b) => {
      if (sortKey === "rating") return (a.avgRating ?? Infinity) - (b.avgRating ?? Infinity);
      if (sortKey === "reviews") return (b.reviewCount ?? 1) - (a.reviewCount ?? 1);
      if (sortKey === "completion") return (a.replyCompletionRate ?? 0) - (b.replyCompletionRate ?? 0);
      return (b.unprocessedCount ?? 1) - (a.unprocessedCount ?? 1);
    });
  }, [data, sortKey]);

  if (notFound) return <HqAccessDenied />;

  return (
    <div className="hq-page">
      <HqNav brand={brand} />
      <div className="hq-radar__title-row">
        <div>
          <h1>매장 비교</h1>
          <p className="hq-page__note">같은 기간 동안 매장마다 어떻게 다른지 나란히 봅니다.</p>
        </div>
        <div className="hq-range" aria-label="비교 기간">
          {([7, 30, 90] as const).map((days) => (
            <Button key={days} type="button" variant={rangeDays === days ? "primary" : "secondary"} onClick={() => setRangeDays(days)}>
              {days}일
            </Button>
          ))}
        </div>
      </div>

      {data === null && !error ? <Skeleton height={260} /> : null}
      {error ? (
        <EmptyState
          title="매장 비교 정보를 불러오지 못했습니다"
          description={error}
          action={
            <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {data ? (
        <Card>
          <div className="hq-sort">
            <label htmlFor="hq-compare-sort">정렬</label>
            <select id="hq-compare-sort" value={sortKey} onChange={(e) => setSortKey(e.target.value as SortKey)}>
              <option value="unprocessed">미처리 많은 순</option>
              <option value="rating">평점 낮은 순</option>
              <option value="completion">답글 완료율 낮은 순</option>
              <option value="reviews">리뷰 많은 순</option>
            </select>
          </div>
          {sortedStores.length === 0 ? (
            <EmptyState title="비교할 매장이 없습니다" />
          ) : (
            <div className="hq-table-wrap">
              <table className="hq-store-table">
                <thead>
                  <tr>
                    <th>매장</th>
                    <th>리뷰</th>
                    <th>평균 별점</th>
                    <th>답글 완료율</th>
                    <th>미처리</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedStores.map((s) => (
                    <CompareRow key={s.storeId} store={s} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      ) : null}
    </div>
  );
}

function CompareRow({ store }: { store: HqStoreComparisonItem }) {
  const needsAttention = store.unprocessedCount == null || store.unprocessedCount > 0;
  return (
    <tr className={needsAttention ? "hq-store-row--problem" : ""}>
      <td>{store.storeName}</td>
      <td>{store.reviewCount == null ? MASKED : `${store.reviewCount}건`}</td>
      <td>{store.reviewCount == null ? MASKED : store.avgRating != null ? `${store.avgRating.toFixed(1)}점` : "데이터 없음"}</td>
      <td>{store.replyCompletionRate == null ? MASKED : store.reviewCount === 0 ? "데이터 없음" : pct(store.replyCompletionRate)}</td>
      <td className={needsAttention ? "hq-store-row__risk" : undefined}>
        {store.unprocessedCount == null ? MASKED : `${store.unprocessedCount}건`}
      </td>
    </tr>
  );
}
