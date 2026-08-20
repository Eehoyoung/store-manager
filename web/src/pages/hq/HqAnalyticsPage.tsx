import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqAnalyticsResponse, HqStoreComparisonItem } from "../../api/types";
import { ApiError } from "../../api/client";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/Skeleton";
import { describeCategory } from "../../lib/labels";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

/**
 * 문서 14 §11.4 — 브랜드 집계.
 *
 * ★ 이슈 태그 랭킹이 본부에게 가장 가치 있는 화면이다("배달지연이 12개 매장 공통" 같은
 * 브랜드 차원의 문제를 여기서 발견한다). 그래서 최상단에 배치한다.
 *
 * 차트 라이브러리를 쓰지 않는다 — DashboardPage 와 동일하게 CSS 폭 비율로 막대를 그린다.
 */

type SortKey = "unprocessed" | "rating" | "reviews" | "completion";

function pct(v: number): string {
  return `${Math.round(v * 100)}%`;
}

export function HqAnalyticsPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [data, setData] = useState<HqAnalyticsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [retryTick, setRetryTick] = useState(0);
  const [sortKey, setSortKey] = useState<SortKey>("unprocessed");

  useEffect(() => {
    setData(null);
    setError(null);
    setNotFound(false);
    hqApi
      .analytics(brand)
      .then(setData)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "집계를 불러오지 못했습니다.");
      });
  }, [brand, retryTick]);

  const sortedStores = useMemo(() => {
    if (!data) return [];
    const arr = [...data.storeComparison];
    arr.sort((a, b) => {
      switch (sortKey) {
        case "rating":
          // 평점이 없는 매장은 뒤로 — null 을 0 으로 취급하면 최하위 매장으로 잘못 보인다.
          return (a.avgRating ?? Number.POSITIVE_INFINITY) - (b.avgRating ?? Number.POSITIVE_INFINITY);
        case "reviews":
          return b.reviewCount - a.reviewCount;
        case "completion":
          return a.replyCompletionRate - b.replyCompletionRate;
        default:
          return b.unprocessedCount - a.unprocessedCount;
      }
    });
    return arr;
  }, [data, sortKey]);

  if (notFound) return <HqAccessDenied />;

  return (
    <div className="hq-page">
      <HqNav brand={brand} />
      <h1>브랜드 집계</h1>

      {data === null && !error ? (
        <div className="hq-page__list">
          <Skeleton height={90} />
          <Skeleton height={200} />
        </div>
      ) : null}

      {error ? (
        <EmptyState
          title="집계를 불러오지 못했습니다"
          description={error}
          action={
            <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {data ? (
        <>
          <p className="hq-page__note">
            집계 기간 {data.from} ~ {data.to}
          </p>

          <div className="hq-kpis">
            <Card>
              <p className="hq-kpi__value">{data.totalReviews}</p>
              <p className="hq-kpi__label">총 리뷰</p>
            </Card>
            <Card>
              <p className="hq-kpi__value">
                {data.avgRating != null ? data.avgRating.toFixed(1) : "—"}
              </p>
              <p className="hq-kpi__label">평균 별점</p>
            </Card>
            <Card>
              <p className="hq-kpi__value">{data.storeComparison.length}</p>
              <p className="hq-kpi__label">가맹점 수</p>
            </Card>
          </div>

          {/* ★ 본부의 핵심 화면 — 브랜드 공통 문제 발견 */}
          <Card>
            <h2>이슈 태그 랭킹</h2>
            <p className="hq-section__hint">
              여러 매장에서 공통으로 나오는 문제를 찾습니다. 브랜드 차원의 개선 지점입니다.
            </p>
            {data.issueTagRanking.length === 0 ? (
              <EmptyState
                title="아직 집계된 이슈 태그가 없습니다"
                description="리뷰가 쌓이면 자동으로 분석됩니다."
              />
            ) : (
              <ul className="hq-bars">
                {data.issueTagRanking.map((t) => {
                  const max = data.issueTagRanking[0]?.count || 1;
                  return (
                    <li key={t.tag} className="hq-bar">
                      <span className="hq-bar__label">{t.tag}</span>
                      <span className="hq-bar__track">
                        <span
                          className="hq-bar__fill"
                          style={{ width: `${Math.max(4, (t.count / max) * 100)}%` }}
                        />
                      </span>
                      <span className="hq-bar__value">{t.count}건</span>
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>

          <div className="hq-two-col">
            <Card>
              <h2>별점 분포</h2>
              {data.ratingDistribution.length === 0 ? (
                <EmptyState title="데이터가 없습니다" />
              ) : (
                <ul className="hq-bars">
                  {[5, 4, 3, 2, 1].map((r) => {
                    const found = data.ratingDistribution.find((x) => x.rating === r);
                    const count = found?.count ?? 0;
                    const max = Math.max(...data.ratingDistribution.map((x) => x.count), 1);
                    return (
                      <li key={r} className="hq-bar">
                        <span className="hq-bar__label">{r}점</span>
                        <span className="hq-bar__track">
                          <span
                            className="hq-bar__fill"
                            style={{ width: `${(count / max) * 100}%` }}
                          />
                        </span>
                        <span className="hq-bar__value">{count}건</span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>

            <Card>
              <h2>카테고리 분포</h2>
              {data.categoryDistribution.length === 0 ? (
                <EmptyState title="데이터가 없습니다" />
              ) : (
                <ul className="hq-bars">
                  {data.categoryDistribution.map((c) => {
                    const max = Math.max(...data.categoryDistribution.map((x) => x.count), 1);
                    return (
                      <li key={c.category} className="hq-bar">
                        <span className="hq-bar__label">{describeCategory(c.category)}</span>
                        <span className="hq-bar__track">
                          <span
                            className="hq-bar__fill"
                            style={{ width: `${(c.count / max) * 100}%` }}
                          />
                        </span>
                        <span className="hq-bar__value">{c.count}건</span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>
          </div>

          <Card>
            <h2>매장별 비교</h2>
            <div className="hq-sort">
              <label htmlFor="hq-sort-select">정렬</label>
              <select
                id="hq-sort-select"
                value={sortKey}
                onChange={(e) => setSortKey(e.target.value as SortKey)}
              >
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
                      <StoreCompareRow key={s.storeId} store={s} brand={brand} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      ) : null}
    </div>
  );
}

function StoreCompareRow({ store, brand }: { store: HqStoreComparisonItem; brand: string }) {
  const needsAttention = store.unprocessedCount > 0;
  return (
    <tr className={needsAttention ? "hq-store-row--problem" : ""}>
      <td>
        {/* 미처리 건을 바로 확인할 수 있게 리뷰 통합 조회로 연결한다 — 숫자만 보여주고 끝내지 않는다. */}
        <Link to={`/hq/brands/${encodeURIComponent(brand)}/reviews?storeId=${store.storeId}`}>
          {store.storeName}
        </Link>
      </td>
      <td>{store.reviewCount}건</td>
      <td>{store.avgRating != null ? `${store.avgRating.toFixed(1)}점` : "데이터 없음"}</td>
      <td>{store.reviewCount === 0 ? "데이터 없음" : pct(store.replyCompletionRate)}</td>
      <td className={needsAttention ? "hq-store-row__risk" : undefined}>
        {store.unprocessedCount}건
      </td>
    </tr>
  );
}
