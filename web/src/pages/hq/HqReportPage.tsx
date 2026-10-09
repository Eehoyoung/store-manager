import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { hqApi, downloadHqReportCsv } from "../../api/hq";
import type { HqReportResponse } from "../../api/types";
import { ApiError } from "../../api/client";
import { Button } from "../../components/Button";
import { Card } from "../../components/Card";
import { EmptyState } from "../../components/EmptyState";
import { Field } from "../../components/Field";
import { Skeleton } from "../../components/Skeleton";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

const pct = (v: number) => `${Math.round(v * 100)}%`;

const BELOW = "표시 기준 미달";
const num = (v: number | null) => (v == null ? "-" : Number.isInteger(v) ? String(v) : v.toFixed(1));

function dateInputValue(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}


// 문서 26 §6.6, 26a hq.report — 인쇄용 보고서 + 집계 CSV. 개별 리뷰 원문은 절대 포함하지 않는다.
export function HqReportPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  const [from, setFrom] = useState(() => dateInputValue(new Date(Date.now() - 29 * 86400000)));
  const [to, setTo] = useState(() => dateInputValue(new Date()));
  const [data, setData] = useState<HqReportResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    setData(null);
    setError(null);
    setNotFound(false);
    hqApi
      .report(brand, from, to)
      .then(setData)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "보고서를 불러오지 못했어요.");
      });
  }, [brand, from, to, retryTick]);

  const downloadCsv = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const blob = await downloadHqReportCsv(brand, from, to);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${brand}_보고서_${from}_${to}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      setDownloadError("CSV 를 내려받지 못했어요. 잠시 후 다시 시도해 주세요.");
    } finally {
      setDownloading(false);
    }
  };

  if (notFound) return <HqAccessDenied />;

  return (
    <div className="hq-page hq-report">
      <div className="hq-report__no-print">
        <HqNav brand={brand} />
        <div className="hq-radar__title-row">
          <div>
            <h1>보고서</h1>
            <p className="hq-page__note">화면 그대로 인쇄하거나 집계 CSV 를 내려받아요. 개별 리뷰 원문은 포함하지 않아요.</p>
          </div>
          <div className="hq-report__controls">
            <Field label="시작일" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            <Field label="종료일" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            <Button type="button" onClick={() => window.print()}>인쇄</Button>
            <Button type="button" variant="secondary" loading={downloading} onClick={() => void downloadCsv()}>
              CSV 다운로드
            </Button>
          </div>
        </div>
        {downloadError ? <p className="auth-card__error" role="alert">{downloadError}</p> : null}
      </div>

      {data === null && !error ? <Skeleton height={260} /> : null}
      {error ? (
        <EmptyState
          title="보고서를 불러오지 못했어요"
          description={error}
          action={<Button type="button" onClick={() => setRetryTick((t) => t + 1)}>다시 시도</Button>}
        />
      ) : null}

      {data ? <ReportBody brand={brand} data={data} /> : null}
    </div>
  );
}

function ReportBody({ brand, data }: { brand: string; data: HqReportResponse }) {
  const t = data.totals;
  const kpis: Array<[string, string]> = [
    ["총 리뷰", String(t.totalReviews)],
    ["분석 완료", String(t.analyzedReviews)],
    ["평균 별점", num(t.avgRating)],
    ["고위험", String(t.highRiskReviews)],
  ];

  return (
    <>
      <header className="hq-report__print-head">
        <h2>{brand} 브랜드 보고서</h2>
        <p>
          기간 {data.period} · 직전 기간 {data.previousPeriod}
        </p>
        <p>
          데이터 기준 {data.dataAsOf ? new Date(data.dataAsOf).toLocaleString("ko-KR") : "수집 데이터 없음"} · 분석 커버리지{" "}
          {pct(data.analysisCoverageRate)}
        </p>
      </header>

      <Card>
        <h2>요약</h2>
        <div className="hq-kpis">
          {kpis.map(([label, value]) => (
            <Card key={label}>
              <p className="hq-kpi__value">{value}</p>
              <p className="hq-kpi__label">{label}</p>
            </Card>
          ))}
        </div>
      </Card>

      <Card>
        <h2>매장별 집계</h2>
        {data.storeRows.length === 0 ? (
          <EmptyState title="매장 집계가 없어요" />
        ) : (
          <div className="hq-table-wrap">
            <table className="hq-store-table">
              <thead>
                <tr><th>매장</th><th>리뷰</th><th>평균 별점</th><th>답글 완료율</th><th>미처리</th></tr>
              </thead>
              <tbody>
                {data.storeRows.map((row) => (
                  <tr key={row.storeId}>
                    <td>{row.storeName}</td>
                    {row.belowThreshold ? (
                      <td colSpan={4}>{BELOW}</td>
                    ) : (
                      <>
                        <td>{num(row.reviewCount)}</td>
                        <td>{num(row.avgRating)}</td>
                        <td>{row.replyCompletionRate == null ? "-" : pct(row.replyCompletionRate)}</td>
                        <td>{num(row.unprocessedCount)}</td>
                      </>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card>
        <h2>이슈별 집계</h2>
        {data.issueRows.length === 0 ? (
          <EmptyState title="이슈 집계가 없어요" />
        ) : (
          <div className="hq-table-wrap">
            <table className="hq-store-table">
              <thead>
                <tr><th>이슈</th><th>건수</th><th>직전 기간</th><th>증감(%p)</th><th>영향 매장</th></tr>
              </thead>
              <tbody>
                {data.issueRows.map((row) => (
                  <tr key={row.tag}>
                    <td>{row.tag}</td>
                    {row.belowThreshold ? (
                      <td colSpan={4}>{BELOW}</td>
                    ) : (
                      <>
                        <td>{num(row.count)}</td>
                        <td>{num(row.previousCount)}</td>
                        <td>{num(row.deltaRatePoints)}</td>
                        <td>{num(row.affectedStoreCount)}</td>
                      </>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
