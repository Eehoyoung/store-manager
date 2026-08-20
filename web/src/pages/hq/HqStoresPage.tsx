import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqStore } from "../../api/types";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/Skeleton";
import { Button } from "../../components/Button";
import { describePlatform } from "../../lib/labels";
import { describeLinkStatus, describeServiceStatus } from "./hqLabels";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

// 문서 14 §11.2 — 가맹점 목록·운영 상태. 미처리·고위험이 많은 매장이 위로 오도록 기본 정렬한다
// (본부가 가장 먼저 봐야 할 매장이다).
function problemScore(s: HqStore): number {
  return s.pendingCount + s.blockedCount + s.highRiskCount;
}

function formatDateTime(iso: string | null): string {
  if (!iso) return "데이터 없음";
  return new Date(iso).toLocaleString("ko-KR");
}

export function HqStoresPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [stores, setStores] = useState<HqStore[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    setStores(null);
    setError(null);
    setNotFound(false);
    hqApi
      .stores(brand)
      .then(setStores)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "가맹점 목록을 불러오지 못했습니다.");
      });
  }, [brand, retryTick]);

  if (notFound) return <HqAccessDenied />;

  return (
    <div className="hq-page">
      <HqNav brand={brand} />
      <h1>가맹점 현황</h1>

      {stores === null && !error ? (
        <div className="hq-page__list">
          <Skeleton height={100} />
          <Skeleton height={100} />
        </div>
      ) : null}

      {error ? (
        <EmptyState
          title="가맹점 목록을 불러오지 못했습니다"
          description={error}
          action={
            <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {stores && stores.length === 0 ? <EmptyState title="소속 가맹점이 없습니다" /> : null}

      {stores && stores.length > 0 ? (
        <div className="hq-table-wrap">
          <table className="hq-store-table">
            <thead>
              <tr>
                <th>매장</th>
                <th>서비스 상태</th>
                <th>연동 상태</th>
                <th>최근 수집</th>
                <th>미처리</th>
                <th>최근 30일</th>
              </tr>
            </thead>
            <tbody>
              {[...stores]
                .sort((a, b) => problemScore(b) - problemScore(a))
                .map((s) => (
                  <StoreRow key={s.storeId} store={s} />
                ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}

function StoreRow({ store }: { store: HqStore }) {
  const hasError = store.platformLinks.some((l) => l.linkStatus === "ERROR");
  const hasProblem = !store.activated || store.serviceStatus === "SUSPENDED" || hasError;

  return (
    <tr className={hasProblem ? "hq-store-row--problem" : ""}>
      <td>
        <p className="hq-store-row__name">{store.name}</p>
        {store.address ? <p className="hq-store-row__address">{store.address}</p> : null}
        {!store.activated ? (
          <Badge tone="warning" icon="⚠">
            계약 전 — 수집·게시 미동작
          </Badge>
        ) : null}
      </td>
      <td>
        {store.serviceStatus === "SUSPENDED" ? (
          <Badge tone="danger" icon="⛔">
            {describeServiceStatus(store.serviceStatus)}
          </Badge>
        ) : (
          <Badge tone="success" icon="✓">
            {describeServiceStatus(store.serviceStatus)}
          </Badge>
        )}
      </td>
      <td>
        {store.platformLinks.length === 0 ? (
          <span className="hq-store-row__muted">연동된 플랫폼 없음</span>
        ) : (
          <div className="hq-platform-links">
            {store.platformLinks.map((l) => (
              <Badge
                key={l.platform}
                tone={l.linkStatus === "ERROR" ? "danger" : "neutral"}
                icon={l.linkStatus === "ERROR" ? "⚠" : "✓"}
              >
                {describePlatform(l.platform)} {describeLinkStatus(l.linkStatus)}
              </Badge>
            ))}
          </div>
        )}
      </td>
      <td>{formatDateTime(store.lastCollectedAt)}</td>
      <td>
        검수대기 {store.pendingCount} · 차단 {store.blockedCount} ·{" "}
        <span className={store.highRiskCount > 0 ? "hq-store-row__risk" : undefined}>
          고위험 {store.highRiskCount}
        </span>
      </td>
      <td>
        {store.recentReviewCount === 0
          ? "데이터 없음"
          : `${store.recentReviewCount}건 · ${
              store.recentAvgRating != null ? store.recentAvgRating.toFixed(1) : "-"
            }점`}
      </td>
    </tr>
  );
}
