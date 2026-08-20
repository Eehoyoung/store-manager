import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { draftsApi } from "../api/drafts";
import { fetchReviewBestEffort } from "../api/reviews";
import type { DraftResponse, ReviewResponse } from "../api/types";
import { ApiError } from "../api/client";
import { Card } from "../components/Card";
import { Button } from "../components/Button";
import { Badge } from "../components/Badge";
import { Select } from "../components/Select";
import { Modal } from "../components/Modal";
import { EmptyState } from "../components/EmptyState";
import { Skeleton } from "../components/Skeleton";
import { Pagination } from "../components/Pagination";
import { useToast } from "../components/Toast";
import { DRAFT_STATUS_META } from "../components/draftStatus";
import { describeGuardrailFlag, describeRiskReason } from "../lib/labels";
import { useShellStore } from "../layout/AppShell";

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "전체 상태" },
  { value: "DRAFT", label: "검토 대기" },
  { value: "APPROVED", label: "승인됨" },
  { value: "SCHEDULED", label: "게시 예정" },
  { value: "PUBLISHED", label: "게시 완료" },
  { value: "FAILED", label: "실패" },
  { value: "REJECTED", label: "거절됨" },
  { value: "BLOCKED", label: "사람 확인 필요" },
  { value: "ALREADY_REPLIED", label: "이미 답글 있음" },
];

const PAGE_SIZE = 10;

export function QueuePage() {
  const { storeId = "" } = useParams<{ storeId: string }>();
  const { setStoreId } = useShellStore();
  const toast = useToast();

  // URL 로 직접 들어온 경우에도 셸 내비게이션(승인 큐/리뷰/대시보드 링크)이 이 매장을 가리키도록 동기화한다.
  useEffect(() => {
    if (storeId) setStoreId(storeId);
  }, [storeId, setStoreId]);

  // 대시보드의 "검수 대기/차단" 카드에서 ?status=DRAFT 등으로 들어오는 경우 초기값으로 반영한다(진입 시 1회).
  const [searchParams] = useSearchParams();
  const [status, setStatus] = useState(() => searchParams.get("status") ?? "");
  const [page, setPage] = useState(0);
  const [items, setItems] = useState<DraftResponse[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reviews, setReviews] = useState<Record<string, ReviewResponse | null>>({});
  // 이미 조회를 시작한 reviewId 추적용(state 아님) — 중복 fetch 방지. state 업데이터 안에서 부수효과를 일으키지 않기 위해 ref 로 둔다.
  const fetchedReviewIds = useRef(new Set<string>());

  const load = useCallback(() => {
    setLoadError(null);
    setItems(null);
    draftsApi
      .queue(storeId, { status: status || undefined, page, size: PAGE_SIZE })
      .then((res) => {
        setItems(res.items);
        setHasMore(res.hasMore);
        res.items.forEach((d) => {
          if (fetchedReviewIds.current.has(d.reviewId)) return;
          fetchedReviewIds.current.add(d.reviewId);
          fetchReviewBestEffort(d.reviewId).then((r) => {
            setReviews((prev) => ({ ...prev, [d.reviewId]: r }));
          });
        });
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : "승인 큐를 불러오지 못했습니다."));
  }, [storeId, status, page]);

  useEffect(load, [load]);

  const updateDraft = (updated: DraftResponse) => {
    setItems((prev) => prev?.map((d) => (d.id === updated.id ? updated : d)) ?? prev);
  };

  if (!storeId) {
    return <EmptyState title="매장을 먼저 선택해 주세요" />;
  }

  return (
    <div className="queue-page">
      <div className="queue-page__header">
        <h1>승인 큐</h1>
        <Select
          label="상태"
          value={status}
          onChange={(e) => {
            setPage(0);
            setStatus(e.target.value);
          }}
        >
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </Select>
      </div>

      <BulkApproveBar storeId={storeId} onDone={load} toast={toast} />

      {items === null && !loadError ? (
        <div className="queue-page__list">
          <Skeleton height={220} />
          <Skeleton height={220} />
        </div>
      ) : null}

      {loadError ? (
        <EmptyState
          title="승인 큐를 불러오지 못했습니다"
          description={loadError}
          action={
            <Button type="button" onClick={load}>
              다시 시도
            </Button>
          }
        />
      ) : null}

      {items && items.length === 0 ? (
        <EmptyState title="표시할 항목이 없습니다" description="선택한 상태에 해당하는 답글이 없습니다." />
      ) : null}

      {items && items.length > 0 ? (
        <ul className="queue-page__list">
          {items.map((d) => (
            <li key={d.id}>
              <QueueItem draft={d} review={reviews[d.reviewId]} onUpdated={updateDraft} toast={toast} />
            </li>
          ))}
        </ul>
      ) : null}

      {items && items.length > 0 ? <Pagination page={page} hasMore={hasMore} onPageChange={setPage} /> : null}
    </div>
  );
}

interface ToastApi {
  show: (message: string, tone?: "info" | "success" | "danger") => void;
}

function ReviewPreview({ review }: { review: ReviewResponse | null | undefined }) {
  if (review === undefined) {
    return (
      <div className="review-preview">
        <Skeleton height={72} />
      </div>
    );
  }
  if (review === null) {
    return (
      <div className="review-preview review-preview--unavailable">
        <p>원본 리뷰 정보를 불러올 수 없습니다. (리뷰 조회 화면은 준비 중입니다)</p>
      </div>
    );
  }
  // ★ 절대규칙 1·6: 리뷰 본문은 읽기 전용으로만 보여준다. authorMasked 는 서버가 이미 마스킹한 값이며
  // 원본 닉네임은 이 화면 어디에도 없다(타입에도 없음). rating/body 는 무텍스트·사진만 리뷰 등에서 null 일 수 있다.
  const rating = review.rating;
  return (
    <blockquote className="review-preview">
      <div className="review-preview__head">
        {rating != null ? (
          <span aria-label={`별점 ${rating}점`}>
            {"★".repeat(rating)}
            {"☆".repeat(Math.max(0, 5 - rating))}
          </span>
        ) : (
          <span>별점 없음</span>
        )}
        <span className="review-preview__author">{review.authorMasked}</span>
        <span className="review-preview__platform">{review.platform}</span>
      </div>
      <p className="review-preview__body">{review.body ?? "(본문 없는 리뷰입니다 — 사진만 등록되었을 수 있습니다)"}</p>
      {review.orderedMenus.length > 0 ? (
        <p className="review-preview__menus">주문 메뉴: {review.orderedMenus.join(", ")}</p>
      ) : null}
    </blockquote>
  );
}

interface QueueItemProps {
  draft: DraftResponse;
  review: ReviewResponse | null | undefined;
  onUpdated: (d: DraftResponse) => void;
  toast: ToastApi;
}

function QueueItem({ draft, review, onUpdated, toast }: QueueItemProps) {
  const meta = DRAFT_STATUS_META[draft.status];
  const [content, setContent] = useState(draft.content);
  const [saving, setSaving] = useState(false);
  const [approving, setApproving] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [blockNotice, setBlockNotice] = useState<{ kind: "risk" | "guardrail"; reasons: string[] } | null>(null);

  const dirty = content !== draft.content;
  const overLimit = content.length > 280; // CLAUDE.md 데이터처리 8번 — 280자 하드 제한
  const editable = draft.status === "DRAFT" || draft.status === "BLOCKED";
  // ★ 절대규칙 3: BLOCKED 는 승인 경로를 열지 않는다. DRAFT 만 승인 가능하다.
  const canApprove = draft.status === "DRAFT";

  const handleSave = async () => {
    if (overLimit || !dirty) return;
    setSaving(true);
    try {
      const updated = await draftsApi.patch(draft.id, content);
      onUpdated(updated);
      toast.show("답글을 저장했습니다.", "success");
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "저장에 실패했습니다.", "danger");
    } finally {
      setSaving(false);
    }
  };

  const handleApprove = async () => {
    setConfirmOpen(false);
    setApproving(true);
    setBlockNotice(null);
    try {
      const updated = await draftsApi.approve(draft.id);
      onUpdated(updated);
      toast.show("승인했습니다. 예약된 시간에 게시됩니다.", "success");
    } catch (e) {
      if (e instanceof ApiError && e.code === "RISK_LEVEL_TOO_HIGH") {
        setBlockNotice({ kind: "risk", reasons: (e.details?.riskReasons as string[] | undefined) ?? [] });
      } else if (e instanceof ApiError && e.code === "GUARDRAIL_BLOCKED") {
        setBlockNotice({ kind: "guardrail", reasons: (e.details?.flags as string[] | undefined) ?? [] });
      } else {
        toast.show(e instanceof ApiError ? e.message : "승인에 실패했습니다.", "danger");
      }
    } finally {
      setApproving(false);
    }
  };

  const handleReject = async () => {
    setRejecting(true);
    try {
      const updated = await draftsApi.reject(draft.id);
      onUpdated(updated);
      toast.show("거절했습니다.", "info");
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "거절에 실패했습니다.", "danger");
    } finally {
      setRejecting(false);
    }
  };

  return (
    <Card className={`queue-item ${draft.status === "BLOCKED" ? "queue-item--blocked" : ""}`}>
      <div className="queue-item__meta">
        <Badge tone={meta.tone} icon={meta.icon}>
          {meta.label}
        </Badge>
        {draft.tier ? <span className="queue-item__tier">모델 등급 {draft.tier}</span> : null}
      </div>

      <ReviewPreview review={review} />

      {draft.status === "BLOCKED" ? (
        <div className="queue-item__blocked-notice" role="alert">
          <strong>⚠ 사람이 직접 확인해야 하는 리뷰입니다.</strong>
          <p>위생·이물질·식중독·법적분쟁 소지가 있어 자동 승인할 수 없습니다. 내용을 직접 확인한 뒤 답글을 다시 작성해 주세요.</p>
          {draft.guardrailFlags.length > 0 ? (
            <ul>
              {draft.guardrailFlags.map((f) => (
                <li key={f}>{describeGuardrailFlag(f)}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {blockNotice ? (
        <div className="queue-item__blocked-notice" role="alert">
          <strong>{blockNotice.kind === "risk" ? "⚠ 위험도가 높아 자동 게시할 수 없습니다." : "⚠ 생성된 답글이 안전 규칙에 걸렸습니다."}</strong>
          <ul>
            {blockNotice.reasons.map((r) => (
              <li key={r}>{blockNotice.kind === "risk" ? describeRiskReason(r) : describeGuardrailFlag(r)}</li>
            ))}
          </ul>
          <p>{blockNotice.kind === "risk" ? "내용을 직접 확인한 뒤 답글을 수정해 주세요." : "직접 작성해 주세요."}</p>
        </div>
      ) : null}

      <label className="queue-item__reply-label" htmlFor={`draft-${draft.id}`}>
        사장님 답글
      </label>
      <textarea
        id={`draft-${draft.id}`}
        className="queue-item__textarea"
        value={content}
        readOnly={!editable}
        onChange={(e) => setContent(e.target.value)}
        rows={4}
      />
      <p className={`queue-item__counter ${overLimit ? "queue-item__counter--over" : ""}`}>
        {content.length} / 280자{overLimit ? " — 글자 수를 줄여주세요" : ""}
      </p>

      <div className="queue-item__actions">
        {editable ? (
          <Button type="button" variant="secondary" onClick={handleSave} loading={saving} disabled={!dirty || overLimit}>
            저장
          </Button>
        ) : null}
        {canApprove ? (
          <Button type="button" onClick={() => setConfirmOpen(true)} loading={approving} disabled={overLimit}>
            승인
          </Button>
        ) : null}
        {draft.status === "DRAFT" ? (
          <Button type="button" variant="danger" onClick={handleReject} loading={rejecting}>
            거절
          </Button>
        ) : null}
      </div>

      <Modal
        open={confirmOpen}
        title="승인하시겠습니까?"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirmOpen(false)}>
              취소
            </Button>
            <Button type="button" onClick={handleApprove} loading={approving}>
              승인합니다
            </Button>
          </>
        }
      >
        <p>승인하면 예약된 시간에 플랫폼에 자동으로 게시됩니다.</p>
        <p>
          <strong>게시 후에는 되돌릴 수 없습니다.</strong> 답글 내용을 다시 확인해 주세요.
        </p>
      </Modal>
    </Card>
  );
}

function BulkApproveBar({ storeId, onDone, toast }: { storeId: string; onDone: () => void; toast: ToastApi }) {
  const [minRating, setMinRating] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  // ★ 안전을 위해 최대 위험도는 항상 0(가장 안전한 리뷰만)으로 고정한다 — 사장님이 조절하는 손잡이로 두지 않는다.
  const filterLabel = minRating ? `별점 ${minRating}점 이상, 위험도 매우 낮음(0)인 답글만` : "위험도 매우 낮음(0)인 답글만";

  const handleConfirm = async () => {
    setLoading(true);
    try {
      const res = await draftsApi.bulkApprove(storeId, {
        minRating: minRating ? Number(minRating) : undefined,
        maxRiskLevel: 0,
      });
      toast.show(`${res.approved}건 승인되었습니다. (건너뜀 ${res.skipped}건)`, "success");
      onDone();
    } catch (e) {
      toast.show(e instanceof ApiError ? e.message : "일괄 승인에 실패했습니다.", "danger");
    } finally {
      setLoading(false);
      setConfirmOpen(false);
    }
  };

  return (
    <Card className="bulk-approve-bar">
      <Select label="일괄 승인 최소 별점" value={minRating} onChange={(e) => setMinRating(e.target.value)}>
        <option value="">전체</option>
        {[3, 4, 5].map((n) => (
          <option key={n} value={n}>
            {n}점 이상
          </option>
        ))}
      </Select>
      <Button type="button" variant="secondary" onClick={() => setConfirmOpen(true)}>
        일괄 승인
      </Button>

      <Modal
        open={confirmOpen}
        title="일괄 승인하시겠습니까?"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirmOpen(false)}>
              취소
            </Button>
            <Button type="button" onClick={handleConfirm} loading={loading}>
              일괄 승인합니다
            </Button>
          </>
        }
      >
        <p>조건: {filterLabel}</p>
        <p>몇 건이 해당하는지는 서버에서 확인 후 즉시 처리되며, 처리 결과(승인/건너뜀 건수)는 완료 직후 알려드립니다.</p>
        <p>
          <strong>승인된 답글은 예약된 시간에 자동 게시되며 되돌릴 수 없습니다.</strong>
        </p>
      </Modal>
    </Card>
  );
}
