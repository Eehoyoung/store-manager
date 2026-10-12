import { useCallback, useEffect, useState } from "react";
import { adminApi, type ReadinessItem, type ReadinessReport, type ReadinessStatus } from "../api/admin";
import { ApiError } from "../api/client";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";

/**
 * 운영자 화면 — 출시 준비 점검.
 * 설정이 맞아 보여도 실수집·실게시·실결제 기록이 없으면 출시 판정을 할 수 없다.
 * UNKNOWN 은 반드시 회색 '알 수 없음' — 초록으로 보이면 안 된다.
 */
const fmt = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short", timeZone: "Asia/Seoul" })
    : "-";

const MODE_LABEL: Record<ReadinessReport["mode"], string> = {
  dev: "개발계",
  prod: "운영계",
  unknown: "알 수 없음",
};

const STATUS_VIEW: Record<ReadinessStatus, { cls: string; icon: string; text: string }> = {
  GREEN: { cls: "badge--success", icon: "✓", text: "정상" },
  RED: { cls: "badge--danger", icon: "✕", text: "문제 있음" },
  UNKNOWN: { cls: "badge--neutral", icon: "?", text: "알 수 없음" },
  INFO: { cls: "badge--info", icon: "i", text: "참고" },
};

/** 모르는 값이 와도 초록이 되지 않게 회색으로 떨어뜨린다. */
const statusView = (s: string) => STATUS_VIEW[s as ReadinessStatus] ?? STATUS_VIEW.UNKNOWN;

function ItemCard({ item }: { item: ReadinessItem }) {
  const v = statusView(item.status);
  return (
    <li>
      <Card className="admin-request">
        <div className="admin-request__head">
          <span className={`badge ${v.cls}`}>
            <span aria-hidden="true">{v.icon}</span> {v.text}
          </span>
          {item.intended ? <span className="label-etched">의도된 빨강(개발계)</span> : null}
          <h3 className="admin-request__brand">{item.label}</h3>
        </div>
        <p>{item.detail}</p>
        <dl className="admin-request__facts">
          <div>
            <dt>마지막 성공</dt>
            <dd>{fmt(item.lastSuccessAt)}</dd>
          </div>
        </dl>
      </Card>
    </li>
  );
}

export function AdminReadiness() {
  const [data, setData] = useState<ReadinessReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    adminApi
      .readiness()
      .then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "출시 준비 상태를 불러오지 못했어요."))
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  return (
    <div className="admin-page">
      <div className="stores-page__header">
        <div>
          <h1>출시 준비</h1>
          <p>설정 점검만으로는 출시 판정을 할 수 없어요. 실수집·실게시·실결제 기록이 함께 있어야 해요.</p>
          {data ? (
            <p>
              <strong>{MODE_LABEL[data.mode] ?? MODE_LABEL.unknown}</strong> · 점검 시각 {fmt(data.generatedAt)}
            </p>
          ) : null}
        </div>
        <Button variant="secondary" onClick={load} loading={loading}>
          새로고침
        </Button>
      </div>

      {error ? (
        <p className="auth-card__error" role="alert">
          {error}
        </p>
      ) : null}

      {data === null && loading ? <Skeleton height={200} /> : null}

      {data ? (
        data.items.length === 0 ? (
          <EmptyState title="점검 항목이 없어요" description="표시할 항목이 아직 없어요." />
        ) : (
          <ul className="admin-request-list">
            {data.items.map((it) => (
              <ItemCard key={it.id} item={it} />
            ))}
          </ul>
        )
      ) : null}
    </div>
  );
}
