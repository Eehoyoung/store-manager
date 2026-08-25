import { useEffect, useState } from "react";
import { adminApi, type CollectFailureRow, type FailureReport, type PublishFailureRow } from "../api/admin";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";

/**
 * 운영자 화면 — 재시도를 소진한 실패 건.
 *
 * ★ 이 화면의 존재 이유: DataAPI 재시도는 2회까지다(호출당 과금이라 무한 재시도는
 *   실패 1건에 호출료를 계속 태운다). 2회로 안 되면 사람이 본다. **이 화면이 없으면
 *   실패한 답글은 아무도 모르게 사라진다** — 사장님은 답글이 달린 줄 알고,
 *   우리는 실패한 줄 모른다.
 *
 * ★ 조회 전용이다. 재시도 버튼을 두지 않는다 — 댓글 등록은 되돌릴 수 없고(수정 API
 *   스펙 미수령), 화면에서 한 번 더 쏘는 길을 만들면 중복 게시가 난다.
 *   원인을 고친 뒤 정상 경로로 다시 태운다.
 *
 * ★ 게시 실패를 위에 둔다. 수집 실패는 '리뷰가 안 들어온 것'이고 게시 실패는
 *   '사장님이 기다리는 답글이 안 나간 것'이다. 뒤쪽이 더 급하다.
 */
const fmt = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" }) : "-";

function Fact({ label, value, mono }: { label: string; value: string | null; mono?: boolean }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={mono ? "admin-request__mono" : undefined}>{value ?? "-"}</dd>
    </div>
  );
}

function PublishCard({ row }: { row: PublishFailureRow }) {
  return (
    <li>
      <Card className="admin-request">
        <div className="admin-request__head">
          <span className="label-etched">답글 미게시</span>
          <h3 className="admin-request__brand">{row.storeName}</h3>
        </div>
        <p>{row.reviewExcerpt || "(본문 없는 사진 리뷰)"}</p>
        <dl className="admin-request__facts">
          <Fact label="플랫폼" value={row.platform} />
          <Fact label="별점" value={row.rating == null ? null : `${row.rating}점`} />
          <Fact label="리뷰 ID" value={row.platformReviewId} mono />
          <Fact label="실패 코드" value={row.failCode} mono />
          <Fact label="시도 횟수" value={`${row.retryCount}회`} />
          <Fact label="발생" value={fmt(row.failedAt)} />
        </dl>
        {row.failReason ? <p className="admin-request__mono">{row.failReason}</p> : null}
      </Card>
    </li>
  );
}

function CollectCard({ row }: { row: CollectFailureRow }) {
  return (
    <li>
      <Card className="admin-request">
        <div className="admin-request__head">
          <span className="label-etched">리뷰 미수집</span>
          <h3 className="admin-request__brand">{row.storeName ?? "(매장 미상)"}</h3>
        </div>
        <dl className="admin-request__facts">
          <Fact label="플랫폼" value={row.platform} />
          <Fact label="계정" value={row.loginIdMasked} mono />
          <Fact label="종류" value={row.jobType} />
          <Fact label="구간" value={`${row.startDate ?? "-"} ~ ${row.endDate ?? "-"}`} />
          <Fact label="오류 코드" value={row.ecode} mono />
          <Fact label="발생" value={fmt(row.failedAt)} />
        </dl>
      </Card>
    </li>
  );
}

export function AdminFailures() {
  const [data, setData] = useState<FailureReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    adminApi
      .failures()
      .then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "실패 목록을 불러오지 못했습니다."));
  }, []);

  return (
    <div className="admin-page">
      <div className="stores-page__header">
        <div>
          <h1>실패 건</h1>
          <p>
            재시도 2회를 모두 소진한 건입니다. 자동으로 다시 시도하지 않으므로 사람이 원인을
            확인해야 합니다. 원인을 고치면 다음 수집 주기에 정상 경로로 처리됩니다.
          </p>
        </div>
      </div>

      {error ? (
        <p className="auth-card__error" role="alert">
          {error}
        </p>
      ) : null}

      {data === null && !error ? <Skeleton height={200} /> : null}

      {data ? (
        <>
          <h2>답글이 나가지 않은 건 ({data.publishFailures.length})</h2>
          <p>사장님이 기다리는 답글입니다. 리뷰에는 아직 아무 답글도 달리지 않았습니다.</p>
          {data.publishFailures.length === 0 ? (
            <EmptyState title="게시 실패가 없습니다" description="모든 답글이 정상 등록됐습니다." />
          ) : (
            <ul className="admin-request-list">
              {data.publishFailures.map((r) => (
                <PublishCard key={r.draftId ?? `${r.platform}-${r.platformReviewId}`} row={r} />
              ))}
            </ul>
          )}

          <h2>리뷰가 들어오지 않은 건 ({data.collectFailures.length})</h2>
          <p>화면이 조용히 비어 보이는 것이 가장 위험합니다 — 사장님은 리뷰가 없는 줄 압니다.</p>
          {data.collectFailures.length === 0 ? (
            <EmptyState title="수집 실패가 없습니다" description="모든 계정에서 정상 조회됐습니다." />
          ) : (
            <ul className="admin-request-list">
              {data.collectFailures.map((r, i) => (
                <CollectCard key={`${r.platform}-${r.failedAt}-${i}`} row={r} />
              ))}
            </ul>
          )}
        </>
      ) : null}
    </div>
  );
}
