// docs/13_내부API명세.md 와 실제 컨트롤러(api-spring/.../DraftDtos.java, StoreDtos.java, AuthDtos.java)를
// 기준으로 맞춘 타입. 문서와 실제 코드가 다르면 코드가 정답이다(오케스트레이터 지시).

export interface ApiErrorEnvelope {
  code: string;
  message: string;
  traceId?: string;
  details?: Record<string, unknown> | null;
}

export interface UserSummary {
  id: string;
  name: string;
  email: string;
}

export interface AccountProfile {
  id: string;
  email: string;
  name: string;
  phone: string | null;
  status: string;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface AuthResponse {
  accessToken: string;
  expiresIn: number;
  user: UserSummary;
}

export interface StoreResponse {
  id: string;
  name: string;
  brandName: string | null;
  category: string | null;
  address: string | null;
  status: string;
  /**
   * ★ 2026-08-20 기준 실제 StoreController.toResponse() 는 activatedAt 을 응답에 포함하지 않는다
   * (Store 엔티티에는 컬럼이 있지만 DTO 매핑에서 빠졌다 — api-spring/src/main/java/.../StoreController.java 확인).
   * 백엔드가 필드를 추가하면 그대로 동작하도록 optional 로 선언해둔다. 필드가 없을 때는 "미서명"으로
   * 안전하게(보수적으로) 취급한다 — CLAUDE.md 의 "activated_at IS NULL → 전량 스킵" 철학과 같은 방향이다.
   */
  activatedAt?: string | null;
}

export type DeliveryPlatform = "BAEMIN" | "YOGIYO" | "COUPANGEATS";

export interface PlatformStoreLinkResponse {
  storeId: string;
  platformStoreId: string;
  storeNameSnapshot: string | null;
}

export interface PlatformAccountResponse {
  id: string;
  platform: DeliveryPlatform;
  maskedLoginId: string;
  linkStatus: string;
  verificationStatus: "DATAAPI_VERIFY_DEFERRED";
  statusMessage: string;
  lastErrorCode: string | null;
  verifiedAt: string | null;
  links: PlatformStoreLinkResponse[];
}

export type DraftStatus =
  | "DRAFT"
  | "SCHEDULED"
  | "PUBLISHED"
  | "FAILED"
  | "BLOCKED"
  | "ALREADY_REPLIED";

/**
 * docs/13 §5, 실제 ReviewController/ReviewDtos.ReviewDetailResponse(api-spring, 다른 에이전트가 작업)
 * 기준. rating 은 nullable(무텍스트·사진만 리뷰 등)이라 number | null 로 둔다.
 * ★ author_hash(원본 닉네임 가명처리 값)는 여기 없다 — authorMasked 만 노출한다(절대규칙 6).
 */
export interface ReviewAnalysisResponse {
  category: string | null;
  sentiment: number | null;
  issueTags: string[];
  riskLevel: number | null;
  riskReasons: string[];
}

export interface ReviewResponse {
  id: string;
  platform: string;
  rating: number | null;
  body: string | null;
  authorMasked: string;
  orderedMenus: string[];
  imageUrls: string[];
  writtenAt: string | null;
  writtenDateOnly: boolean;
  collectedAt: string | null;
  hasOwnerReply: boolean;
  analysis: ReviewAnalysisResponse | null;
}

/** 리뷰 목록/상세용 초안 요약(ReviewDtos.DraftSummaryResponse) — DraftResponse 보다 필드가 적다. */
export interface DraftSummary {
  id: string;
  status: DraftStatus;
  content: string;
}

/** GET /stores/{storeId}/reviews 항목(ReviewDtos.ReviewSummaryResponse). */
export interface ReviewSummary extends ReviewResponse {
  draft: DraftSummary | null;
}

export interface ReviewListResponse {
  items: ReviewSummary[];
  nextCursor: string | null;
  hasMore: boolean;
}

/** GET /reviews/{reviewId}(ReviewDtos.ReviewDetailResponse) — draft 대신 drafts 배열(재생성 이력, 최신순). */
export interface ReviewDetail extends ReviewResponse {
  drafts: DraftSummary[];
}

// ── 페르소나(docs/13 §7, PersonaDtos) ───────────────────────────────────

export type PersonaTone = "POLITE" | "FRIENDLY" | "CHEERFUL" | "CONCISE";

export interface PublishWindow {
  start: string; // HH:mm
  end: string; // HH:mm
}

export interface PersonaRequest {
  tone: PersonaTone;
  useEmoji: boolean;
  emojiLevel: number; // 0~3
  customerTitle: string;
  signature: string;
  openingStyle: string;
  bannedWords: string[];
  lengthMin: number;
  lengthMax: number; // <=280
  delayHours: number;
  publishWindows: PublishWindow[];
}

export interface PersonaResponse extends PersonaRequest {
  storeId: string;
  personaSeed: number;
  updatedAt: string;
}

export interface PreviewRequest {
  reviewId: string;
  persona: PersonaRequest | null;
}

export interface PreviewResponse {
  content: string;
  tier: string | null;
  model: string | null;
  promptVersion: string | null;
  guardrailFlags: string[];
}

export interface StyleSampleResponse {
  id: string;
  reviewText: string;
  replyText: string;
  rating: number | null;
  source: string;
  createdAt: string | null;
}

export interface StyleSampleListResponse {
  items: StyleSampleResponse[];
  hasMore: boolean;
  manualCount: number;
}

// ── 대시보드(docs/13 §8, AnalyticsDtos) ─────────────────────────────────

export interface RatingBucket {
  rating: number;
  count: number;
}

export interface CategoryBucket {
  category: string;
  count: number;
}

export interface AnalyticsSummaryResponse {
  from: string;
  to: string;
  totalReviews: number;
  avgRating: number | null;
  ratingDistribution: RatingBucket[];
  categoryDistribution: CategoryBucket[];
  replyCompletionRate: number;
  pendingCount: number;
  blockedCount: number;
  highRiskCount: number;
}

export interface TrendPoint {
  date: string;
  reviewCount: number;
  avgRating: number | null;
  publishedCount: number;
}

export interface AnalyticsTrendResponse {
  from: string;
  to: string;
  items: TrendPoint[];
}

export interface IssueTagItem {
  tag: string;
  count: number;
  avgRating: number | null;
  lastOccurredAt: string | null;
}

export interface AnalyticsIssuesResponse {
  from: string;
  to: string;
  items: IssueTagItem[];
}

export interface MenuItem {
  menu: string;
  count: number;
  avgRating: number | null;
}

export interface AnalyticsMenusResponse {
  from: string;
  to: string;
  items: MenuItem[];
}

export interface AnalyticsResponsePerformance {
  from: string;
  to: string;
  totalReviews: number;
  completedCount: number;
  completionRate: number;
  autoPublishRate: number;
  avgResponseMinutes: number | null;
  retriedCount: number;
}

// ── 가맹본부(HQ, docs/13 §11.5) ──────────────────────────────────────────
// ★ 문서 §11.5 예시 JSON과 실제 컨트롤러(api-spring/.../hq/HqDtos.java, HqController.java)가 다르다 —
// 코드가 정답이다(오케스트레이터 지시). 특히 /hq/brands, /hq/brands/{brand}/stores 는 문서의
// {items,hasMore} 래핑이 아니라 배열을 그대로 반환한다. analytics 의 issueTagRanking 항목에는
// 문서와 달리 avgRating 이 없고, storeComparison(문서의 stores)의 미처리 필드명은 unprocessedCount 다.

export interface HqBrand {
  brandName: string;
  storeCount: number;
}

export interface HqPlatformLink {
  platform: string;
  /** 확인된 값: PENDING(기본) · ERROR. LINKED 로 표기하는 경로는 아직 코드에 없다 — 미확인 코드는 원문 그대로 보여준다. */
  linkStatus: string;
}

export interface HqStore {
  storeId: string;
  name: string;
  address: string | null;
  activated: boolean;
  /** IN_SERVICE | SUSPENDED — 구독·청구 상세는 절대 내려주지 않는다(H9). */
  serviceStatus: string;
  platformLinks: HqPlatformLink[];
  lastCollectedAt: string | null;
  pendingCount: number;
  blockedCount: number;
  highRiskCount: number;
  recentReviewCount: number;
  recentAvgRating: number | null;
}

export interface HqAnalysis {
  category: string | null;
  sentiment: number | null;
  issueTags: string[];
  riskLevel: number | null;
  riskReasons: string[];
}

export interface HqDraftSummary {
  id: string;
  status: DraftStatus;
  content: string;
}

export interface HqReviewItem {
  id: string;
  storeId: string | null;
  storeName: string | null;
  platform: string;
  rating: number | null;
  body: string | null;
  authorMasked: string;
  orderedMenus: string[];
  imageUrls: string[];
  writtenAt: string | null;
  writtenDateOnly: boolean;
  collectedAt: string | null;
  hasOwnerReply: boolean;
  analysis: HqAnalysis | null;
  draft: HqDraftSummary | null;
}

export interface HqReviewListResponse {
  items: HqReviewItem[];
  hasMore: boolean;
}

export interface HqIssueTagItem {
  tag: string;
  count: number;
  previousCount: number;
  /** 분석 완료 리뷰 100건당 발생 건수. 분석 데이터가 없으면 null. */
  ratePer100: number | null;
  previousRatePer100: number | null;
  deltaRatePoints: number | null;
  affectedStoreCount: number;
  avgRating: number | null;
  signal: "NEW" | "RISING" | "STABLE" | "FALLING";
}

export interface HqRiskClusterItem {
  reason: string;
  count: number;
  previousCount: number;
  affectedStoreCount: number;
}

export interface HqMenuIssueItem {
  menu: string;
  tag: string;
  count: number;
  affectedStoreCount: number;
  avgRating: number | null;
}

export interface HqDailyRiskItem {
  date: string;
  analyzedCount: number;
  issueReviewCount: number;
  highRiskCount: number;
}

export interface HqStoreComparisonItem {
  storeId: string;
  storeName: string;
  reviewCount: number;
  avgRating: number | null;
  replyCompletionRate: number;
  unprocessedCount: number;
}

export interface HqAnalyticsResponse {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  dataAsOf: string | null;
  totalReviews: number;
  analyzedReviews: number;
  analysisCoverageRate: number;
  avgRating: number | null;
  highRiskReviews: number;
  highRiskAffectedStores: number;
  ratingDistribution: RatingBucket[];
  categoryDistribution: CategoryBucket[];
  issueTagRanking: HqIssueTagItem[];
  riskClusters: HqRiskClusterItem[];
  menuIssues: HqMenuIssueItem[];
  dailyRiskTrend: HqDailyRiskItem[];
  storeComparison: HqStoreComparisonItem[];
}
