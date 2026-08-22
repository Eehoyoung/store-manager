import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqAnalyticsResponse, HqDailyRiskItem, HqIssueTagItem, HqStoreComparisonItem } from "../../api/types";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/Skeleton";
import { describeCategory, describeRiskReason } from "../../lib/labels";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

/** 기존 분석 결과만 집계하는 브랜드 이상징후 레이더. 별도 LLM 호출은 없다. */
type SortKey = "unprocessed" | "rating" | "reviews" | "completion";
type RangeDays = 7 | 30 | 90;

const pct = (v: number) => `${Math.round(v * 100)}%`;

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

function issueLink(brand: string, item: HqIssueTagItem, from: string, to: string): string {
  const qs = new URLSearchParams({ issueTag: item.tag, from, to });
  return `/hq/brands/${encodeURIComponent(brand)}/reviews?${qs.toString()}`;
}

export function HqAnalyticsPage() {
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
    hqApi.analytics(brand, selected.from, selected.to).then(setData).catch((e) => {
      if (e instanceof ApiError && e.status === 404) setNotFound(true);
      else setError(e instanceof ApiError ? e.message : "이상징후를 불러오지 못했습니다.");
    });
  }, [brand, rangeDays, retryTick]);

  const sortedStores = useMemo(() => {
    if (!data) return [];
    return [...data.storeComparison].sort((a, b) => {
      if (sortKey === "rating") return (a.avgRating ?? Infinity) - (b.avgRating ?? Infinity);
      if (sortKey === "reviews") return b.reviewCount - a.reviewCount;
      if (sortKey === "completion") return a.replyCompletionRate - b.replyCompletionRate;
      return b.unprocessedCount - a.unprocessedCount;
    });
  }, [data, sortKey]);

  if (notFound) return <HqAccessDenied />;
  return <div className="hq-page">
    <HqNav brand={brand} />
    <div className="hq-radar__title-row">
      <div><h1>브랜드 이상징후 레이더</h1><p className="hq-page__note">여러 매장에 번지는 문제를 직전 동일 기간과 비교합니다.</p></div>
      <div className="hq-range" aria-label="분석 기간">{([7, 30, 90] as const).map((days) => <Button key={days} type="button" variant={rangeDays === days ? "primary" : "secondary"} onClick={() => setRangeDays(days)}>{days}일</Button>)}</div>
    </div>
    {data === null && !error ? <div className="hq-page__list"><Skeleton height={120} /><Skeleton height={260} /></div> : null}
    {error ? <EmptyState title="이상징후를 불러오지 못했습니다" description={error} action={<Button type="button" onClick={() => setRetryTick((t) => t + 1)}>다시 시도</Button>} /> : null}
    {data ? <RadarContent brand={brand} data={data} sortedStores={sortedStores} sortKey={sortKey} setSortKey={setSortKey} /> : null}
  </div>;
}

function RadarContent({ brand, data, sortedStores, sortKey, setSortKey }: { brand: string; data: HqAnalyticsResponse; sortedStores: HqStoreComparisonItem[]; sortKey: SortKey; setSortKey: (key: SortKey) => void }) {
  const rising = data.issueTagRanking.filter((item) => item.signal === "NEW" || item.signal === "RISING");
  const coverageLow = data.totalReviews > 0 && data.analysisCoverageRate < 0.95;
  return <>
    <p className="hq-page__note">현재 {data.from} ~ {data.to} · 비교 {data.previousFrom} ~ {data.previousTo}{data.dataAsOf ? ` · 최근 수집 ${new Date(data.dataAsOf).toLocaleString("ko-KR")}` : " · 수집 데이터 없음"}</p>
    {coverageLow ? <div className="hq-radar__coverage-warning" role="alert">⚠ 분석 커버리지 {pct(data.analysisCoverageRate)} — 미분석 리뷰가 있어 발생률이 실제와 다를 수 있습니다.</div> : null}

    <div className="hq-kpis">
      <Card className={data.highRiskReviews > 0 ? "hq-kpi--danger" : undefined}><p className="hq-kpi__value">{data.highRiskReviews}</p><p className="hq-kpi__label">고위험 리뷰 · {data.highRiskAffectedStores}개 매장</p></Card>
      <Card className={rising.length > 0 ? "hq-kpi--warning" : undefined}><p className="hq-kpi__value">{rising.length}</p><p className="hq-kpi__label">신규·급증 이슈</p></Card>
      <Card><p className="hq-kpi__value">{pct(data.analysisCoverageRate)}</p><p className="hq-kpi__label">분석 커버리지 · {data.analyzedReviews}/{data.totalReviews}건</p></Card>
      <Card><p className="hq-kpi__value">{data.avgRating != null ? data.avgRating.toFixed(1) : "—"}</p><p className="hq-kpi__label">평균 별점</p></Card>
    </div>

    <Card className="hq-radar__alerts"><h2>지금 확인할 항목</h2>
      {data.riskClusters.every((r) => r.count === 0) && rising.length === 0 ? <p className="hq-radar__clear">✓ 현재 기간에 고위험 군집이나 기준을 넘은 급증 이슈가 없습니다.</p> : <ul className="hq-alert-list">
        {data.riskClusters.filter((r) => r.count > 0).map((risk) => <li key={risk.reason} className="hq-alert hq-alert--danger"><Badge tone="danger" icon="⚠">긴급</Badge><span><strong>{describeRiskReason(risk.reason)}</strong> {risk.count}건 · {risk.affectedStoreCount}개 매장</span><Link to={`/hq/brands/${encodeURIComponent(brand)}/reviews?riskLevel=3&from=${data.from}&to=${data.to}`}>근거 리뷰 보기</Link></li>)}
        {rising.map((item) => <li key={item.tag} className="hq-alert hq-alert--warning"><Badge tone="warning" icon="↑">{item.signal === "NEW" ? "신규" : "급증"}</Badge><span><strong>{item.tag}</strong> {item.count}건 · {item.affectedStoreCount}개 매장 · {deltaLabel(item)}</span><Link to={issueLink(brand, item, data.from, data.to)}>근거 리뷰 보기</Link></li>)}
      </ul>}
    </Card>

    <Card><h2>이슈 발생률 추이</h2><p className="hq-section__hint">분석 리뷰 100건당 발생률입니다. 단순 건수 증가와 리뷰량 증가를 구분합니다.</p>
      {data.issueTagRanking.length === 0 ? <EmptyState title="집계된 이슈가 없습니다" description="분석된 리뷰에 이슈 태그가 생기면 표시됩니다." /> : <div className="hq-table-wrap"><table className="hq-store-table hq-issue-table"><thead><tr><th>신호</th><th>이슈</th><th>현재</th><th>직전</th><th>증감</th><th>영향 매장</th><th>평균 별점</th></tr></thead><tbody>{data.issueTagRanking.map((item) => <IssueRow key={item.tag} brand={brand} item={item} from={data.from} to={data.to} />)}</tbody></table></div>}
    </Card>

    <div className="hq-two-col">
      <Card><h2>일별 위험 흐름</h2><p className="hq-section__hint">이슈 리뷰 비율과 고위험 발생일을 함께 봅니다.</p><DailyTrendChart items={data.dailyRiskTrend} /></Card>
      <Card><h2>메뉴 × 이슈</h2><p className="hq-section__hint">주문 메뉴가 제공된 리뷰에서 반복되는 조합입니다.</p>{data.menuIssues.length === 0 ? <EmptyState title="메뉴 근거가 없습니다" /> : <ul className="hq-menu-issues">{data.menuIssues.map((item) => <li key={`${item.menu}-${item.tag}`}><span><strong>{item.menu}</strong><small>{item.tag}</small></span><span>{item.count}건 · {item.affectedStoreCount}개 매장 · {item.avgRating != null ? `${item.avgRating.toFixed(1)}점` : "별점 없음"}</span></li>)}</ul>}</Card>
    </div>

    <DistributionCards data={data} />
    <Card><h2>매장별 비교</h2><div className="hq-sort"><label htmlFor="hq-sort-select">정렬</label><select id="hq-sort-select" value={sortKey} onChange={(e) => setSortKey(e.target.value as SortKey)}><option value="unprocessed">미처리 많은 순</option><option value="rating">평점 낮은 순</option><option value="completion">답글 완료율 낮은 순</option><option value="reviews">리뷰 많은 순</option></select></div>{sortedStores.length === 0 ? <EmptyState title="비교할 매장이 없습니다" /> : <div className="hq-table-wrap"><table className="hq-store-table"><thead><tr><th>매장</th><th>리뷰</th><th>평균 별점</th><th>답글 완료율</th><th>미처리</th></tr></thead><tbody>{sortedStores.map((s) => <StoreCompareRow key={s.storeId} store={s} brand={brand} />)}</tbody></table></div>}</Card>
  </>;
}

function IssueRow({ brand, item, from, to }: { brand: string; item: HqIssueTagItem; from: string; to: string }) {
  const tone = item.signal === "NEW" || item.signal === "RISING" ? "warning" : item.signal === "FALLING" ? "success" : "neutral";
  const label = { NEW: "신규", RISING: "급증", STABLE: "유지", FALLING: "감소" }[item.signal];
  return <tr className={item.signal === "NEW" || item.signal === "RISING" ? "hq-store-row--problem" : ""}><td><Badge tone={tone} icon={item.signal === "FALLING" ? "↓" : item.signal === "STABLE" ? "·" : "↑"}>{label}</Badge></td><td><Link to={issueLink(brand, item, from, to)}>{item.tag}</Link></td><td>{rateLabel(item.ratePer100, item.count)}</td><td>{rateLabel(item.previousRatePer100, item.previousCount)}</td><td>{deltaLabel(item)}</td><td>{item.affectedStoreCount}곳</td><td>{item.avgRating != null ? `${item.avgRating.toFixed(1)}점` : "—"}</td></tr>;
}

function rateLabel(rate: number | null, count: number): string {
  return rate == null ? "데이터 없음" : `${rate.toFixed(1)}건 (${count}건)`;
}

function deltaLabel(item: HqIssueTagItem): string {
  if (item.deltaRatePoints == null) return item.previousCount === 0 && item.count > 0 ? "직전 데이터 없음" : "비교 불가";
  return `${item.deltaRatePoints > 0 ? "+" : ""}${item.deltaRatePoints.toFixed(1)}%p`;
}

function DailyTrendChart({ items }: { items: HqDailyRiskItem[] }) {
  if (items.length === 0) return <EmptyState title="분석된 리뷰가 없습니다" />;
  const rates = items.map((item) => item.analyzedCount === 0 ? 0 : item.issueReviewCount / item.analyzedCount * 100);
  const max = Math.max(...rates, 1);
  const x = (index: number) => items.length === 1 ? 50 : index * 100 / (items.length - 1);
  const points = rates.map((rate, index) => `${x(index)},${100 - rate / max * 82}`).join(" ");
  return <div className="hq-trend"><svg viewBox="0 0 100 110" role="img" aria-label={`일별 이슈 리뷰 비율. 최대 ${max.toFixed(1)}퍼센트`} preserveAspectRatio="none"><line x1="0" y1="100" x2="100" y2="100" className="hq-trend__axis" /><polyline points={points} className="hq-trend__line" vectorEffect="non-scaling-stroke" />{items.map((item, index) => item.highRiskCount > 0 ? <circle key={item.date} cx={x(index)} cy={100 - rates[index] / max * 82} r="2.2" className="hq-trend__risk"><title>{item.date}: 고위험 {item.highRiskCount}건</title></circle> : null)}</svg><div className="hq-trend__labels"><span>{items[0].date}</span><span>빨간 점: 고위험 발생</span><span>{items[items.length - 1].date}</span></div></div>;
}

function DistributionCards({ data }: { data: HqAnalyticsResponse }) {
  return <div className="hq-two-col"><Card><h2>별점 분포</h2>{data.ratingDistribution.length === 0 ? <EmptyState title="데이터가 없습니다" /> : <ul className="hq-bars">{[5, 4, 3, 2, 1].map((rating) => { const count = data.ratingDistribution.find((x) => x.rating === rating)?.count ?? 0; const max = Math.max(...data.ratingDistribution.map((x) => x.count), 1); return <li key={rating} className="hq-bar"><span className="hq-bar__label">{rating}점</span><span className="hq-bar__track"><span className="hq-bar__fill" style={{ width: `${count / max * 100}%` }} /></span><span className="hq-bar__value">{count}건</span></li>; })}</ul>}</Card><Card><h2>카테고리 분포</h2>{data.categoryDistribution.length === 0 ? <EmptyState title="데이터가 없습니다" /> : <ul className="hq-bars">{data.categoryDistribution.map((item) => { const max = Math.max(...data.categoryDistribution.map((x) => x.count), 1); return <li key={item.category} className="hq-bar"><span className="hq-bar__label">{describeCategory(item.category)}</span><span className="hq-bar__track"><span className="hq-bar__fill" style={{ width: `${item.count / max * 100}%` }} /></span><span className="hq-bar__value">{item.count}건</span></li>; })}</ul>}</Card></div>;
}

function StoreCompareRow({ store, brand }: { store: HqStoreComparisonItem; brand: string }) {
  const needsAttention = store.unprocessedCount > 0;
  return <tr className={needsAttention ? "hq-store-row--problem" : ""}><td><Link to={`/hq/brands/${encodeURIComponent(brand)}/reviews?storeId=${store.storeId}`}>{store.storeName}</Link></td><td>{store.reviewCount}건</td><td>{store.avgRating != null ? `${store.avgRating.toFixed(1)}점` : "데이터 없음"}</td><td>{store.reviewCount === 0 ? "데이터 없음" : pct(store.replyCompletionRate)}</td><td className={needsAttention ? "hq-store-row__risk" : undefined}>{store.unprocessedCount}건</td></tr>;
}
