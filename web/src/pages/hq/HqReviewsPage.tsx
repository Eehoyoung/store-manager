import { useEffect, useMemo, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { hqApi } from "../../api/hq";
import type { HqReviewItem, HqStore } from "../../api/types";
import { ApiError } from "../../api/client";
import { Badge } from "../../components/Badge";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { Pagination } from "../../components/Pagination";
import { Select } from "../../components/Select";
import { Skeleton } from "../../components/Skeleton";
import { DRAFT_STATUS_META } from "../../components/draftStatus";
import { describeCategory, describePlatform, describeRiskReason } from "../../lib/labels";
import { HqNav } from "./HqNav";
import { HqAccessDenied } from "./HqAccessDenied";

/**
 * 문서 14 §11.3 — 브랜드 리뷰 통합 조회.
 *
 * ★★ 완전한 읽기 전용이다. 답글 편집용 textarea·input 을 두지 않고(readOnly 로도 두지 않는다)
 * 텍스트로만 렌더한다. 승인·거절·재생성·저장 버튼을 만들지 않는다 — 클라이언트 명시 요구사항이며
 * 가맹점 운영 권한을 본부가 침범하지 않기 위한 경계다(FR-807/808, CLAUDE.md 가맹본부 절).
 *
 * ★ 위험도 필터가 본부의 핵심 용도다 — 브랜드 차원에서 위생·이물질·법적분쟁 리뷰를 한 번에 찾는다.
 */

const PAGE_SIZE = 20;

const CATEGORIES = [
  ["PRAISE", "칭찬"],
  ["POSITIVE", "긍정"],
  ["IMPROVEMENT", "개선요청"],
  ["COMPLAINT", "불만"],
  ["ABUSIVE", "악성"],
  ["NOISE", "무의미"],
] as const;

const DRAFT_STATUSES = [
  ["DRAFT", "검토 대기"],
  ["APPROVED", "승인됨"],
  ["SCHEDULED", "게시 예정"],
  ["PUBLISHED", "게시 완료"],
  ["FAILED", "실패"],
  ["REJECTED", "거절됨"],
  ["BLOCKED", "사람 확인 필요"],
  ["ALREADY_REPLIED", "이미 답글 있음"],
] as const;

function formatWrittenAt(item: HqReviewItem): string {
  if (!item.writtenAt) return "-";
  const d = new Date(item.writtenAt);
  // REVIEWDATE 는 시각 정보가 없다(CLAUDE.md 데이터처리 1번) — 날짜만 보여주고 시각을 지어내지 않는다.
  return item.writtenDateOnly ? d.toLocaleDateString("ko-KR") : d.toLocaleString("ko-KR");
}

export function HqReviewsPage() {
  const { brand = "" } = useParams<{ brand: string }>();
  // 필터를 URL 과 동기화한다 — 새로고침·뒤로가기에도 유지된다(ReviewsPage 와 동일 방식).
  const [params, setParams] = useSearchParams();

  const [items, setItems] = useState<HqReviewItem[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [stores, setStores] = useState<HqStore[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [retryTick, setRetryTick] = useState(0);

  const filter = useMemo(
    () => ({
      storeId: params.get("storeId") ?? "",
      category: params.get("category") ?? "",
      riskLevel: params.get("riskLevel") ?? "",
      status: params.get("status") ?? "",
      minRating: params.get("minRating") ?? "",
      page: Number(params.get("page") ?? "0"),
    }),
    [params],
  );

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page"); // 필터가 바뀌면 1페이지로 돌아간다
    setParams(next);
  }

  // 매장 필터 선택지 — 실패해도 리뷰 조회는 계속되어야 하므로 조용히 비운다.
  useEffect(() => {
    hqApi
      .stores(brand)
      .then(setStores)
      .catch(() => setStores([]));
  }, [brand]);

  useEffect(() => {
    setItems(null);
    setError(null);
    setNotFound(false);
    hqApi
      .reviews(brand, {
        storeId: filter.storeId || undefined,
        category: filter.category || undefined,
        riskLevel: filter.riskLevel ? Number(filter.riskLevel) : undefined,
        status: filter.status || undefined,
        minRating: filter.minRating ? Number(filter.minRating) : undefined,
        page: filter.page,
        size: PAGE_SIZE,
      })
      .then((res) => {
        setItems(res.items);
        setHasMore(res.hasMore);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 404) setNotFound(true);
        else setError(e instanceof ApiError ? e.message : "리뷰를 불러오지 못했습니다.");
      });
  }, [brand, filter, retryTick]);

  if (notFound) return <HqAccessDenied />;

  return (
    <div className="hq-page">
      <HqNav brand={brand} />
      <h1>리뷰 통합 조회</h1>
      <p className="hq-page__note">
        브랜드 전체 리뷰를 조회합니다. 본부는 <strong>조회만</strong> 할 수 있으며 답글 작성·승인은
        각 가맹점에서 진행합니다.
      </p>

      <div className="hq-filters">
        <Select
          label="매장"
          value={filter.storeId}
          onChange={(e) => setFilter("storeId", e.target.value)}
        >
          <option value="">전체 매장</option>
          {stores.map((s) => (
            <option key={s.storeId} value={s.storeId}>
              {s.name}
            </option>
          ))}
        </Select>

        <Select
          label="위험도"
          value={filter.riskLevel}
          onChange={(e) => setFilter("riskLevel", e.target.value)}
        >
          <option value="">전체</option>
          <option value="3">고위험만 (위생·이물질·법적분쟁)</option>
          <option value="2">주의 이상</option>
          <option value="1">경미 이상</option>
        </Select>

        <Select
          label="카테고리"
          value={filter.category}
          onChange={(e) => setFilter("category", e.target.value)}
        >
          <option value="">전체</option>
          {CATEGORIES.map(([code, label]) => (
            <option key={code} value={code}>
              {label}
            </option>
          ))}
        </Select>

        <Select
          label="답글 상태"
          value={filter.status}
          onChange={(e) => setFilter("status", e.target.value)}
        >
          <option value="">전체</option>
          {DRAFT_STATUSES.map(([code, label]) => (
            <option key={code} value={code}>
              {label}
            </option>
          ))}
        </Select>

        <Select
          label="최소 별점"
          value={filter.minRating}
          onChange={(e) => setFilter("minRating", e.target.value)}
        >
          <option value="">전체</option>
          <option value="4">4점 이상</option>
          <option value="3">3점 이상</option>
          <option value="1">1점 이상</option>
        </Select>
      </div>

      {items === null && !error ? (
        <div className="hq-page__list">
          <Skeleton height={120} />
          <Skeleton height={120} />
        </div>
      ) : null}

      {error ? (
        <EmptyState
          title="리뷰를 불러오지 못했습니다"
          description={error}
          action={
            <Button type="button" onClick={() => setRetryTick((t) => t + 1)}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {items && items.length === 0 ? (
        <EmptyState
          title="조건에 맞는 리뷰가 없습니다"
          description="필터를 바꿔 다시 조회해 보세요."
        />
      ) : null}

      {items && items.length > 0 ? (
        <>
          <ul className="hq-review-list">
            {items.map((item) => (
              <HqReviewCard key={item.id} item={item} />
            ))}
          </ul>
          <Pagination
            page={filter.page}
            hasMore={hasMore}
            onPageChange={(p) => setFilter("page", String(p))}
          />
        </>
      ) : null}
    </div>
  );
}

function HqReviewCard({ item }: { item: HqReviewItem }) {
  const risk = item.analysis?.riskLevel ?? 0;
  const highRisk = risk >= 3;
  const statusMeta = item.draft ? DRAFT_STATUS_META[item.draft.status] : null;

  return (
    <li className={`hq-review${highRisk ? " hq-review--risk" : ""}`}>
      <div className="hq-review__head">
        <span className="hq-review__store">{item.storeName ?? "-"}</span>
        <span className="hq-review__platform">{describePlatform(item.platform)}</span>
        {highRisk ? (
          <Badge tone="danger" icon="⚠">
            고위험
          </Badge>
        ) : null}
        {statusMeta ? (
          <Badge tone={statusMeta.tone} icon={statusMeta.icon}>
            {statusMeta.label}
          </Badge>
        ) : (
          <Badge tone="neutral" icon="·">
            답글 없음
          </Badge>
        )}
      </div>

      <div className="hq-review__meta">
        <span aria-label={`별점 ${item.rating ?? 0}점`}>
          {"★".repeat(item.rating ?? 0)}
          {"☆".repeat(Math.max(0, 5 - (item.rating ?? 0)))}
        </span>
        {/* ★ 규칙6: 서버가 가명처리한 authorMasked 만 쓴다. authorHash 는 절대 렌더하지 않는다. */}
        <span>{item.authorMasked}</span>
        <span>{formatWrittenAt(item)}</span>
      </div>

      {/* ★ 규칙1: 리뷰는 읽기 전용이다. 편집 요소를 두지 않는다. */}
      <p className="hq-review__body">{item.body || "(내용 없음)"}</p>

      {item.orderedMenus.length > 0 ? (
        <p className="hq-review__menus">주문 메뉴: {item.orderedMenus.join(", ")}</p>
      ) : null}

      {item.analysis ? (
        <div className="hq-review__analysis">
          {item.analysis.category ? <span>{describeCategory(item.analysis.category)}</span> : null}
          {item.analysis.riskReasons.map((r) => (
            <Badge key={r} tone="warning" icon="⚠">
              {describeRiskReason(r)}
            </Badge>
          ))}
          {item.analysis.issueTags.map((t) => (
            <Badge key={t} tone="neutral">
              {t}
            </Badge>
          ))}
        </div>
      ) : null}

      {/* 답글은 '보기'만 가능하다 — textarea 를 쓰지 않고 텍스트로 렌더한다. */}
      {item.draft?.content ? (
        <div className="hq-review__reply">
          <p className="hq-review__reply-label">사장님 답글</p>
          <p className="hq-review__reply-body">{item.draft.content}</p>
        </div>
      ) : null}
    </li>
  );
}
