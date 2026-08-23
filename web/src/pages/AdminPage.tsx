import { useEffect, useState } from "react";
import { adminApi, type AffiliationRequest } from "../api/admin";
import { ApiError } from "../api/client";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";

/**
 * 운영자 화면 — 가맹점 소속 승인.
 *
 * ★ 여기는 조종석이 아니라 관제탑이다. 사장님 화면은 "내 매장에 무슨 일이 있나" 지만
 *   운영자는 "이 요청을 통과시킬까" 하나만 판단한다. 그래서 화면이 답해야 할 것은
 *   '무엇을 근거로 판단하는가' 와 '통과시키면 무슨 일이 일어나는가' 둘뿐이다.
 * ★ 승인은 본부가 그 매장의 리뷰를 조회하게 만드는 행위다. 그 사실을 버튼 옆에 적는다 —
 *   결과를 모르는 채 누르는 승인은 동의 절차라고 부를 수 없다.
 */
export function AdminPage() {
  const [items, setItems] = useState<AffiliationRequest[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = () =>
    adminApi
      .requests()
      .then(setItems)
      .catch((e) => setError(e instanceof ApiError ? e.message : "승인 대기 목록을 불러오지 못했습니다."));

  useEffect(() => {
    void load();
  }, []);

  const decide = async (item: AffiliationRequest, decision: "APPROVE" | "REJECT") => {
    setError(null);
    setBusyId(item.id);
    try {
      await adminApi.decide(item.id, decision);
      setItems((current) => current?.filter((i) => i.id !== item.id) ?? []);
      // 행이 그냥 사라지면 무엇을 처리했는지 남지 않는다. 방금 한 일을 한 줄로 남긴다.
      setDone(
        decision === "APPROVE"
          ? `${item.storeName} 을(를) ${item.brandName} 본부에 연결했습니다.`
          : `${item.storeName} 의 ${item.brandName} 소속 신청을 거절했습니다.`,
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="admin-page">
      <div className="stores-page__header">
        <div>
          <h1>가맹점 소속 승인</h1>
          <p>
            가맹코드를 입력한 매장이 여기로 옵니다. 승인하면 해당 본부가 그 매장의 리뷰를 조회할 수 있게 됩니다.
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

      {items === null && !error ? <Skeleton height={140} /> : null}
      {items?.length === 0 ? (
        <EmptyState title="승인 대기 신청이 없습니다" description="가맹점이 가맹코드를 입력하면 이 목록에 나타납니다." />
      ) : null}

      <ul className="admin-request-list">
        {items?.map((item) => (
          <li key={item.id}>
            <Card className="admin-request">
              <div className="admin-request__head">
                <span className="label-etched">소속 신청</span>
                <h2 className="admin-request__brand">{item.brandName}</h2>
              </div>

              {/* 판단 근거. 운영자가 대조할 값이므로 라벨을 붙여 나란히 세운다. */}
              <dl className="admin-request__facts">
                <div>
                  <dt>매장</dt>
                  <dd>{item.storeName}</dd>
                </div>
                <div>
                  <dt>주소</dt>
                  <dd>{item.storeAddress ?? "입력 없음"}</dd>
                </div>
                <div>
                  <dt>신청자</dt>
                  <dd>{item.requesterName}</dd>
                </div>
                <div>
                  <dt>이메일</dt>
                  <dd className="admin-request__mono">{item.requesterEmail}</dd>
                </div>
              </dl>

              <p className="admin-request__consequence">
                승인하면 <strong>{item.brandName}</strong> 본부가 <strong>{item.storeName}</strong> 의 리뷰·분석을
                조회하게 됩니다. 구독·결제 내역과 배달앱 계정은 계속 보이지 않습니다.
              </p>

              <div className="admin-request__actions">
                <Button
                  type="button"
                  loading={busyId === item.id}
                  onClick={() => void decide(item, "APPROVE")}
                >
                  승인하고 본부에 연결
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={busyId === item.id}
                  onClick={() => void decide(item, "REJECT")}
                >
                  거절
                </Button>
              </div>
            </Card>
          </li>
        ))}
      </ul>
    </div>
  );
}
