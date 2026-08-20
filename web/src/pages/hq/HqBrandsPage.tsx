import { useEffect, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqBrand } from "../../api/types";
import { ApiError } from "../../api/client";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/Skeleton";
import { Button } from "../../components/Button";

// 문서 14 §11.1 — 본부 권한을 가진 브랜드 목록. 브랜드가 하나뿐이면(대부분의 본부) 선택 화면 없이
// 바로 가맹점 목록으로 이동한다.
export function HqBrandsPage() {
  const [brands, setBrands] = useState<HqBrand[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retryTick, setRetryTick] = useState(0);

  useEffect(() => {
    setBrands(null);
    setError(null);
    hqApi
      .brands()
      .then(setBrands)
      .catch((e) => setError(e instanceof ApiError ? e.message : "브랜드 목록을 불러오지 못했습니다."));
  }, [retryTick]);

  if (error) {
    return (
      <EmptyState
        title="브랜드 목록을 불러오지 못했습니다"
        description={error}
        action={
          <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
            다시 시도
          </Button>
        }
      />
    );
  }

  if (brands === null) {
    return (
      <div className="hq-page">
        <Skeleton height={80} />
        <Skeleton height={80} />
      </div>
    );
  }

  if (brands.length === 0) {
    return (
      <EmptyState title="본부 권한이 있는 브랜드가 없습니다" description="가맹본부 계정으로 등록된 브랜드가 없습니다." />
    );
  }

  if (brands.length === 1) {
    return <Navigate to={`/hq/brands/${encodeURIComponent(brands[0].brandName)}/stores`} replace />;
  }

  return (
    <div className="hq-page">
      <h1>가맹본부 — 브랜드 선택</h1>
      <ul className="hq-brand-list">
        {brands.map((b) => (
          <li key={b.brandName}>
            <Link to={`/hq/brands/${encodeURIComponent(b.brandName)}/stores`} className="card hq-brand-card">
              <p className="hq-brand-card__name">{b.brandName}</p>
              <p className="hq-brand-card__count">매장 {b.storeCount}개</p>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
