import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqOverviewResponse } from "../../api/types";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/Skeleton";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

const pct = (v: number) => `${Math.round(v * 100)}%`;

function fmt(iso: string | null): string {
  if (!iso) return "데이터 없음";
  return new Date(iso).toLocaleString("ko-KR");
}

// 문서 26 §6.1 — 본부 홈. "지금 무엇을 봐야 하나" 하나에 답한다. 대시보드가 아니라 계기판이다.
export function HqOverviewPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [data, setData] = useState<HqOverviewResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    setData(null);
    setError(null);
    setNotFound(false);
    hqApi
      .overview(brand)
      .then(setData)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "본부 홈 정보를 불러오지 못했습니다.");
      });
  }, [brand, retryTick]);

  if (notFound) return <HqAccessDenied />;

  return (
    <div className="hq-page">
      <HqNav brand={brand} />
      <h1>본부 홈</h1>

      {data === null && !error ? (
        <div className="hq-page__list">
          <Skeleton height={120} />
          <Skeleton height={200} />
        </div>
      ) : null}

      {error ? (
        <EmptyState
          title="본부 홈 정보를 불러오지 못했습니다"
          description={error}
          action={
            <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {data ? <OverviewContent brand={brand} data={data} /> : null}
    </div>
  );
}

function OverviewContent({ brand, data }: { brand: string; data: HqOverviewResponse }) {
  const encoded = encodeURIComponent(brand);
  const coverageLow = data.analysisCoverageRate < 0.95;

  return (
    <>
      <p className="hq-page__note">
        데이터 기준 {fmt(data.dataAsOf)} · 분석 커버리지 {pct(data.analysisCoverageRate)}
      </p>
      {coverageLow ? (
        <div className="hq-radar__coverage-warning" role="alert">
          ⚠ 분석 커버리지 {pct(data.analysisCoverageRate)} — 미분석 리뷰가 있어 아래 수치가 실제보다 낮게 보일 수 있습니다.
        </div>
      ) : null}

      <div className="hq-kpis">
        <Card><p className="hq-kpi__value">{data.storeCount}</p><p className="hq-kpi__label">관리 매장</p></Card>
        <Card><p className="hq-kpi__value">{data.serviceActiveCount}</p><p className="hq-kpi__label">서비스 중</p></Card>
        <Card className={data.suspendedCount > 0 ? "hq-kpi--warning" : undefined}>
          <p className="hq-kpi__value">{data.suspendedCount}</p><p className="hq-kpi__label">정지</p>
        </Card>
        <Card className={data.unlinkedCount > 0 ? "hq-kpi--warning" : undefined}>
          <p className="hq-kpi__value">{data.unlinkedCount}</p><p className="hq-kpi__label">미연동</p>
        </Card>
      </div>

      <div className="hq-kpis">
        <Card className={data.pendingReviewCount > 0 ? "hq-kpi--warning" : undefined}>
          <p className="hq-kpi__value">{data.pendingReviewCount}</p><p className="hq-kpi__label">검수 대기</p>
        </Card>
        <Card className={data.blockedCount > 0 ? "hq-kpi--warning" : undefined}>
          <p className="hq-kpi__value">{data.blockedCount}</p><p className="hq-kpi__label">차단</p>
        </Card>
        <Card className={data.highRiskCount > 0 ? "hq-kpi--danger" : undefined}>
          <p className="hq-kpi__value">{data.highRiskCount}</p><p className="hq-kpi__label">고위험</p>
        </Card>
      </div>

      <Card>
        <h2>우선 확인 매장</h2>
        {data.priorityStores.length === 0 ? (
          <EmptyState title="지금 우선 확인할 매장이 없습니다" />
        ) : (
          <ul className="hq-menu-issues">
            {data.priorityStores.map((s) => (
              <li key={s.storeId}>
                <span>
                  <strong>{s.storeName}</strong>
                  <small>{s.reason}</small>
                </span>
                <span>
                  고위험 {s.highRiskCount}건 · 검수대기 {s.pendingCount}건
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <div className="hq-two-col">
        <Card>
          <h2>수집 지연 매장</h2>
          {data.collectDelayedStores.length === 0 ? (
            <EmptyState title="지연된 매장이 없습니다" />
          ) : (
            <ul className="hq-menu-issues">
              {data.collectDelayedStores.map((s) => (
                <li key={s.storeId}>
                  <span>{s.storeName}</span>
                  <span>최근 수집 {fmt(s.lastCollectedAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card>
          <h2>신규·급증 이슈</h2>
          {data.risingIssues.length === 0 ? (
            <EmptyState title="신규·급증 이슈가 없습니다" />
          ) : (
            <ul className="hq-alert-list">
              {data.risingIssues.map((item) => (
                <li key={item.tag} className="hq-alert hq-alert--warning">
                  <Badge tone="warning" icon="↑">{item.signal === "NEW" ? "신규" : "급증"}</Badge>
                  <span>
                    <strong>{item.tag}</strong> {item.count}건
                    {item.deltaRatePoints != null ? ` · ${item.deltaRatePoints > 0 ? "+" : ""}${item.deltaRatePoints.toFixed(1)}%p` : ""}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <p className="hq-page__note">
        더 자세한 흐름은 <Link to={`/hq/brands/${encoded}/analytics`}>브랜드 집계</Link>에서 확인할 수 있습니다.
      </p>
    </>
  );
}
