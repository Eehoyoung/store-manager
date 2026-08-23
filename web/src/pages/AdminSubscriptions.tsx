import { useEffect, useState } from "react";
import { adminApi, type StoreServiceRow } from "../api/admin";
import { ApiError } from "../api/client";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Field } from "../components/Field";
import { Skeleton } from "../components/Skeleton";

/**
 * 운영자: 매장별 서비스 상태와 구독 활성화.
 *
 * ★ 활성화는 그 매장에 DataAPI 호출과 LLM 토큰을 쓰기 시작한다는 뜻이다. 돈이 나가는 결정이므로
 *   버튼 옆에 그 사실을 적고, 근거(입금자명·입금일)를 반드시 받는다 — 요금 분쟁 시 유일한 기록이다.
 * ★ 두 게이트를 나눠 보여준다. 계약(법적 근거)과 구독(요금)은 다른 것이고, 둘 다 통과해야 서비스한다.
 */

const SUB_LABEL: Record<string, { tone: "success" | "danger" | "warning" | "neutral"; text: string }> = {
  ACTIVE: { tone: "success", text: "이용 중" },
  TRIAL: { tone: "warning", text: "입금 대기" },
  PAST_DUE: { tone: "danger", text: "연체" },
  SUSPENDED: { tone: "danger", text: "정지" },
  CANCELED: { tone: "neutral", text: "해지" },
};

export function AdminSubscriptions() {
  const [rows, setRows] = useState<StoreServiceRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});

  const load = () =>
    adminApi
      .stores()
      .then(setRows)
      .catch((e) => setError(e instanceof ApiError ? e.message : "매장 목록을 불러오지 못했습니다."));

  useEffect(() => {
    void load();
  }, []);

  const act = async (row: StoreServiceRow, action: "activate" | "suspend") => {
    const note = (notes[row.storeId] ?? "").trim();
    if (!note) {
      setError("판단 근거를 입력해 주세요. 입금자명·입금일처럼 나중에 확인할 수 있는 내용이면 됩니다.");
      return;
    }
    setError(null);
    setBusyId(row.storeId);
    try {
      await (action === "activate" ? adminApi.activate : adminApi.suspend)(row.storeId, note);
      setDone(
        action === "activate"
          ? `${row.storeName} 서비스를 시작했습니다. 이제 리뷰 수집과 답글 생성에 비용이 발생합니다.`
          : `${row.storeName} 서비스를 정지했습니다. 수집·생성·게시가 모두 멈춥니다.`,
      );
      setNotes((c) => ({ ...c, [row.storeId]: "" }));
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "처리하지 못했습니다.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="admin-page">
      <div className="stores-page__header">
        <div>
          <h1>매장 서비스 상태</h1>
          <p>
            입금을 확인한 뒤 활성화합니다. 가입이나 전자계약만으로는 서비스가 시작되지 않습니다.
          </p>
        </div>
      </div>

      {error ? (
        <p className="auth-card__error" role="alert">
          {error}
        </p>
      ) : null}
      {done ? (
        <p className="admin-page__done" role="status">
          {done}
        </p>
      ) : null}

      {rows === null && !error ? <Skeleton height={160} /> : null}

      <ul className="admin-request-list">
        {rows?.map((row) => {
          const sub = row.subscriptionStatus ? SUB_LABEL[row.subscriptionStatus] : null;
          return (
            <li key={row.storeId}>
              <Card className={`admin-request ${row.serviceActive ? "admin-request--live" : ""}`}>
                <div className="admin-request__head">
                  <span className="label-etched">{row.serviceActive ? "서비스 중" : "서비스 안 함"}</span>
                  <h2 className="admin-request__brand">{row.storeName}</h2>
                </div>

                <dl className="admin-request__facts">
                  <div>
                    <dt>사장님</dt>
                    <dd>{row.ownerName ?? "-"}</dd>
                  </div>
                  <div>
                    <dt>이메일</dt>
                    <dd className="admin-request__mono">{row.ownerEmail ?? "-"}</dd>
                  </div>
                  <div>
                    <dt>전자계약</dt>
                    <dd>
                      <Badge tone={row.contractSigned ? "success" : "warning"} icon={row.contractSigned ? "✓" : "•"}>
                        {row.contractSigned ? "완료" : "미완료"}
                      </Badge>
                    </dd>
                  </div>
                  <div>
                    <dt>구독</dt>
                    <dd>
                      <Badge tone={sub?.tone ?? "warning"} icon={sub?.tone === "success" ? "✓" : "•"}>
                        {sub?.text ?? "구독 없음"}
                      </Badge>
                    </dd>
                  </div>
                </dl>

                <p className="admin-request__consequence">
                  {row.serviceActive
                    ? "이 매장은 지금 리뷰를 수집하고 답글을 생성합니다. 정지하면 즉시 멈춥니다."
                    : "활성화하면 이 매장의 리뷰 수집(호출당 과금)과 답글 생성(LLM 토큰)이 시작됩니다."}
                </p>

                <Field
                  label="판단 근거"
                  hint="입금자명·입금일처럼 나중에 확인할 수 있는 내용을 적어 주세요. 요금 문의가 오면 이 기록으로 답합니다."
                  value={notes[row.storeId] ?? ""}
                  maxLength={200}
                  onChange={(e) => setNotes((c) => ({ ...c, [row.storeId]: e.target.value }))}
                />

                <div className="admin-request__actions">
                  {row.serviceActive ? (
                    <Button
                      type="button"
                      variant="danger"
                      loading={busyId === row.storeId}
                      onClick={() => void act(row, "suspend")}
                    >
                      서비스 정지
                    </Button>
                  ) : (
                    <Button
                      type="button"
                      loading={busyId === row.storeId}
                      disabled={!row.contractSigned}
                      onClick={() => void act(row, "activate")}
                    >
                      입금 확인 · 서비스 시작
                    </Button>
                  )}
                  {!row.contractSigned ? (
                    <span className="admin-request__blocked">전자계약이 끝나야 활성화할 수 있습니다.</span>
                  ) : null}
                </div>
              </Card>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
