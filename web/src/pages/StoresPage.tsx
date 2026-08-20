import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { storesApi } from "../api/stores";
import type { StoreResponse } from "../api/types";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { Button } from "../components/Button";
import { Field } from "../components/Field";
import { Badge } from "../components/Badge";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { useToast } from "../components/Toast";
import { useShellStore } from "../layout/AppShell";

export function StoresPage() {
  const [stores, setStores] = useState<StoreResponse[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const { setStoreId } = useShellStore();
  const toast = useToast();

  const load = () => {
    setLoadError(null);
    storesApi
      .list()
      .then(setStores)
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "매장 목록을 불러오지 못했습니다."));
  };

  useEffect(load, []);

  return (
    <div className="stores-page">
      <div className="stores-page__header">
        <h1>내 매장</h1>
        <Button type="button" variant="secondary" onClick={() => setShowForm((v) => !v)}>
          {showForm ? "닫기" : "매장 등록"}
        </Button>
      </div>

      {showForm ? (
        <StoreCreateForm
          onCreated={(s) => {
            setStores((prev) => (prev ? [...prev, s] : [s]));
            setShowForm(false);
            toast.show("매장이 등록되었습니다.", "success");
          }}
        />
      ) : null}

      {stores === null && !loadError ? (
        <div className="stores-page__list">
          <Skeleton height={96} />
          <Skeleton height={96} />
        </div>
      ) : null}

      {loadError ? (
        <EmptyState
          title="매장 목록을 불러오지 못했습니다"
          description={loadError}
          action={
            <Button type="button" onClick={load}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {stores && stores.length === 0 ? (
        <EmptyState title="등록된 매장이 없습니다" description="매장을 등록하면 리뷰 답글 자동화를 시작할 수 있습니다." />
      ) : null}

      {stores && stores.length > 0 ? (
        <ul className="stores-page__list">
          {stores.map((s) => (
            <li key={s.id}>
              <Card className="store-card">
                <div className="store-card__main">
                  <p className="store-card__name">{s.name}</p>
                  {s.brandName ? <p className="store-card__brand">{s.brandName}</p> : null}
                  {!s.activatedAt ? (
                    <Badge tone="warning" icon="⚠">
                      전자계약 서명 전 — 수집·게시가 동작하지 않습니다
                    </Badge>
                  ) : (
                    <Badge tone="success" icon="✓">
                      계약 완료
                    </Badge>
                  )}
                </div>
                <Link to={`/stores/${s.id}/queue`} className="btn btn--secondary" onClick={() => setStoreId(s.id)}>
                  승인 큐 보기
                </Link>
              </Card>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

interface StoreFormState {
  name: string;
  brandName: string;
  category: string;
  address: string;
}

function StoreCreateForm({ onCreated }: { onCreated: (s: StoreResponse) => void }) {
  const [form, setForm] = useState<StoreFormState>({ name: "", brandName: "", category: "", address: "" });
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const update = (key: keyof StoreFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.name.trim()) {
      setError("매장명을 입력해 주세요.");
      return;
    }
    setLoading(true);
    try {
      const created = await storesApi.create({
        name: form.name,
        brandName: form.brandName || undefined,
        category: form.category || undefined,
        address: form.address || undefined,
      });
      onCreated(created);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "매장 등록에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="store-form">
      <form onSubmit={handleSubmit} noValidate>
        <Field label="매장명" required value={form.name} onChange={update("name")} />
        <Field label="브랜드명 (선택)" value={form.brandName} onChange={update("brandName")} />
        <Field label="업종 (선택)" value={form.category} onChange={update("category")} />
        <Field label="주소 (선택)" value={form.address} onChange={update("address")} />
        {error ? (
          <p className="auth-card__error" role="alert">
            {error}
          </p>
        ) : null}
        <Button type="submit" loading={loading}>
          등록
        </Button>
      </form>
    </Card>
  );
}
