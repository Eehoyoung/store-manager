import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { analyticsApi } from "../api/analytics";
import type {
  AnalyticsIssuesResponse,
  AnalyticsMenusResponse,
  AnalyticsResponsePerformance,
  AnalyticsSummaryResponse,
  AnalyticsTrendResponse,
} from "../api/types";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { Button } from "../components/Button";
import { describeCategory } from "../lib/labels";
import { useShellStore } from "../layout/AppShell";

const RATINGS = [5, 4, 3, 2, 1];

function pct(rate: number): string {
  return `${Math.round(rate * 100)}%`;
}

// avgResponseMinutes 는 분 단위 숫자 — 사람이 읽는 형태로 변환한다. null 이면 0 으로 그리지 않는다.
function formatMinutes(minutes: number | null): string {
  if (minutes == null) return "아직 데이터가 없습니다";
  if (minutes < 60) return `${minutes.toFixed(1)}분`;
  return `${Math.floor(minutes / 60)}시간 ${Math.round(minutes % 60)}분`;
}

function formatDateOnly(iso: string | null): string {
  if (!iso) return "-";
  return iso.slice(0, 10);
}

interface DashboardData {
  summary: AnalyticsSummaryResponse;
  trend: AnalyticsTrendResponse;
  issues: AnalyticsIssuesResponse;
  menus: AnalyticsMenusResponse;
  response: AnalyticsResponsePerformance;
}

export function DashboardPage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();

  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    if (!storeId) return;
    setData(null);
    setError(null);
    Promise.all([
      analyticsApi.summary(storeId),
      analyticsApi.trend(storeId),
      analyticsApi.issues(storeId),
      analyticsApi.menus(storeId),
      analyticsApi.response(storeId),
    ])
      .then(([summary, trend, issues, menus, response]) => setData({ summary, trend, issues, menus, response }))
      .catch((e) => setError(e instanceof ApiError ? e.message : "대시보드 데이터를 불러오지 못했습니다."));
  }, [storeId, retryTick]);

  if (!storeId) {
    return <EmptyState title="매장을 먼저 선택해 주세요" />;
  }

  if (error) {
    return (
      <EmptyState
        title="대시보드를 불러오지 못했습니다"
        description={error}
        action={
          <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
            다시 시도
          </Button>
        }
      />
    );
  }

  if (!data) {
    return (
      <div className="dashboard-page">
        <Skeleton height={100} />
        <Skeleton height={220} />
        <Skeleton height={220} />
      </div>
    );
  }

  const { summary, trend, issues, menus, response } = data;

  return (
    <div className="dashboard-page">
      <h1>대시보드</h1>

      <div className="dashboard-stats">
        <Card className="dashboard-stat">
          <p className="dashboard-stat__value">{summary.totalReviews}</p>
          <p className="dashboard-stat__label">총 리뷰</p>
        </Card>
        <Card className="dashboard-stat">
          <p className="dashboard-stat__value">{summary.avgRating != null ? summary.avgRating.toFixed(1) : "-"}</p>
          <p className="dashboard-stat__label">평균 별점</p>
        </Card>
        <Card className="dashboard-stat">
          <p className="dashboard-stat__value">{pct(summary.replyCompletionRate)}</p>
          <p className="dashboard-stat__label">답글 완료율</p>
        </Card>
        <Card className="dashboard-stat">
          <p className="dashboard-stat__value">{summary.pendingCount}</p>
          <p className="dashboard-stat__label">자동 처리 대기</p>
        </Card>
        <Card className="dashboard-stat">
          <p className="dashboard-stat__value">{summary.blockedCount}</p>
          <p className="dashboard-stat__label">안전 규칙 차단</p>
        </Card>
        <Link
          to={`/stores/${storeId}/reviews?riskLevel=3`}
          className="card dashboard-stat dashboard-stat--clickable dashboard-stat--danger"
        >
          <p className="dashboard-stat__value">{summary.highRiskCount}</p>
          <p className="dashboard-stat__label">고위험 → 리뷰 목록으로 이동</p>
        </Link>
      </div>

      <Card className="dashboard-section">
        <h2>별점 분포</h2>
        {summary.totalReviews === 0 ? (
          <EmptyState title="아직 리뷰가 없습니다" />
        ) : (
          <RatingBars summary={summary} />
        )}
      </Card>

      <Card className="dashboard-section">
        <h2>카테고리 분포</h2>
        {summary.categoryDistribution.length === 0 ? (
          <EmptyState title="아직 분류된 리뷰가 없습니다" />
        ) : (
          <CategoryBars summary={summary} />
        )}
      </Card>

      <Card className="dashboard-section">
        <h2>일자별 추이 (최근 30일)</h2>
        {trend.items.length === 0 ? <EmptyState title="아직 데이터가 없습니다" /> : <TrendChart trend={trend} />}
      </Card>

      <Card className="dashboard-section">
        <h2>이슈 태그 랭킹</h2>
        {issues.items.length === 0 ? (
          <EmptyState title="아직 집계된 이슈 태그가 없습니다" />
        ) : (
          <table className="dashboard-table">
            <thead>
              <tr>
                <th>순위</th>
                <th>태그</th>
                <th>건수</th>
                <th>평균 별점</th>
                <th>최근 발생</th>
              </tr>
            </thead>
            <tbody>
              {issues.items.map((it, i) => (
                <tr key={it.tag}>
                  <td>{i + 1}</td>
                  <td>{it.tag}</td>
                  <td>{it.count}건</td>
                  <td>{it.avgRating != null ? it.avgRating.toFixed(1) : "-"}</td>
                  <td>{formatDateOnly(it.lastOccurredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card className="dashboard-section">
        <h2>메뉴별 만족도</h2>
        {menus.items.length === 0 ? (
          <EmptyState title="아직 집계된 메뉴가 없습니다" />
        ) : (
          <table className="dashboard-table">
            <thead>
              <tr>
                <th>메뉴</th>
                <th>언급 건수</th>
                <th>평균 별점</th>
              </tr>
            </thead>
            <tbody>
              {menus.items.map((m) => (
                <tr key={m.menu}>
                  <td>{m.menu}</td>
                  <td>{m.count}건</td>
                  <td>{m.avgRating != null ? m.avgRating.toFixed(1) : "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card className="dashboard-section">
        <h2>응답 성과</h2>
        {response.totalReviews === 0 ? (
          <EmptyState title="아직 데이터가 없습니다" />
        ) : (
          <ul className="dashboard-metric-list">
            <li>
              <span>답글 완료율</span>
              <strong>{pct(response.completionRate)}</strong>
            </li>
            <li>
              <span>자동 승인 비율</span>
              <strong>{pct(response.autoApprovalRate)}</strong>
            </li>
            <li>
              <span>평균 응답 시간</span>
              <strong>{formatMinutes(response.avgResponseMinutes)}</strong>
            </li>
            <li>
              <span>재시도 건수</span>
              <strong>{response.retriedCount}건</strong>
            </li>
          </ul>
        )}
      </Card>
    </div>
  );
}

function RatingBars({ summary }: { summary: AnalyticsSummaryResponse }) {
  const countByRating = new Map(summary.ratingDistribution.map((b) => [b.rating, b.count]));
  const max = Math.max(1, ...summary.ratingDistribution.map((b) => b.count));
  return (
    <div className="dashboard-bar-list">
      {RATINGS.map((rating) => {
        const count = countByRating.get(rating) ?? 0;
        return (
          <div className="dashboard-bar-row" key={rating}>
            <span className="dashboard-bar-row__label">{rating}점</span>
            <div className="dashboard-bar-row__track">
              <div className="dashboard-bar-row__fill" style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <span className="dashboard-bar-row__value">{count}건</span>
          </div>
        );
      })}
    </div>
  );
}

function CategoryBars({ summary }: { summary: AnalyticsSummaryResponse }) {
  const max = Math.max(1, ...summary.categoryDistribution.map((b) => b.count));
  return (
    <div className="dashboard-bar-list">
      {summary.categoryDistribution.map((b) => (
        <div className="dashboard-bar-row" key={b.category}>
          <span className="dashboard-bar-row__label">{describeCategory(b.category)}</span>
          <div className="dashboard-bar-row__track">
            <div className="dashboard-bar-row__fill" style={{ width: `${(b.count / max) * 100}%` }} />
          </div>
          <span className="dashboard-bar-row__value">{b.count}건</span>
        </div>
      ))}
    </div>
  );
}

function TrendChart({ trend }: { trend: AnalyticsTrendResponse }) {
  const max = Math.max(1, ...trend.items.map((p) => p.reviewCount));
  return (
    <div className="dashboard-trend" role="img" aria-label="일자별 리뷰 건수 추이 막대 그래프">
      {trend.items.map((p) => (
        <div
          key={p.date}
          className="dashboard-trend__col"
          title={`${p.date}: 리뷰 ${p.reviewCount}건, 게시 ${p.publishedCount}건${p.avgRating != null ? `, 평균 ${p.avgRating.toFixed(1)}점` : ""}`}
        >
          <div className="dashboard-trend__bar" style={{ height: `${(p.reviewCount / max) * 100}%` }} />
        </div>
      ))}
    </div>
  );
}
