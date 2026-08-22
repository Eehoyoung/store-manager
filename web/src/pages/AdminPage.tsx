import { useEffect, useState } from "react";
import { adminApi, type AffiliationRequest } from "../api/admin";
import { ApiError } from "../api/client";
import { Button } from "../components/Button";
import { Card } from "../components/Card";

export function AdminPage() {
  const [items, setItems] = useState<AffiliationRequest[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => adminApi.requests().then(setItems).catch((e) =>
    setError(e instanceof ApiError ? e.message : "승인 대기 목록을 불러오지 못했습니다."));
  useEffect(() => { void load(); }, []);

  const decide = async (id: string, decision: "APPROVE" | "REJECT") => {
    try {
      await adminApi.decide(id, decision);
      setItems((current) => current?.filter((item) => item.id !== id) ?? []);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "처리하지 못했습니다.");
    }
  };

  return (
    <div>
      <h1>관리자</h1>
      <h2>가맹점 소속 승인 대기</h2>
      {error ? <p role="alert">{error}</p> : null}
      {items?.length === 0 ? <p>승인 대기 신청이 없습니다.</p> : null}
      {items?.map((item) => (
        <Card key={item.id}>
          <h3>{item.brandName} · {item.storeName}</h3>
          <p>{item.requesterName} / {item.requesterEmail}</p>
          <p>{item.storeAddress ?? "주소 없음"}</p>
          <Button type="button" onClick={() => void decide(item.id, "APPROVE")}>승인</Button>{" "}
          <Button type="button" variant="secondary" onClick={() => void decide(item.id, "REJECT")}>거절</Button>
        </Card>
      ))}
    </div>
  );
}
