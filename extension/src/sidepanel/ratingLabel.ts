/**
 * 카드 상단 별점 표기.
 *
 * ★ "별점 없음" 과 "별점 1~2" 를 구분해 보여준다. 실기동(2026-09-20)에서 별점 없는
 *   리뷰가 0 으로 접혀 들어와, 칭찬 리뷰에까지 "개별 확인 필요" 배지가 붙었다.
 *   사장님이 보기에 왜 확인해야 하는지 알 수 없는 상태였다.
 *
 * ★ 그래도 **일괄 승인에서는 똑같이 빠진다**(state/machine.bulkApprovable).
 *   낮은 별점인지 모르는 채로 한꺼번에 올리면 절대 규칙을 지켰다고 말할 수 없다.
 *   다른 것은 문구뿐이고 안전 동작은 같다.
 */
export interface RatingDisplay {
  /** 별 문자열. 별점이 없으면 빈 문자열 */
  stars: string;
  /** 배지 문구. 없으면 null */
  badge: string | null;
  /** 카드에 붙일 CSS 클래스 */
  className: string;
}

export function ratingDisplay(rating: number | null): RatingDisplay {
  if (rating === null) {
    return { stars: "별점 없음", badge: "별점을 알 수 없어 개별 확인", className: " unrated" };
  }
  if (rating <= 2) {
    return { stars: "★".repeat(Math.max(0, rating)), badge: "개별 확인 필요", className: " low-rating" };
  }
  return { stars: "★".repeat(rating), badge: null, className: "" };
}

/** 상단 요약의 "부정 N건". ★ 별점 미상은 부정이 아니다 — 모르는 것을 나쁘다고 세지 않는다. */
export function countNegative(ratings: (number | null)[]): number {
  return ratings.filter((r): r is number => r !== null && r <= 2).length;
}
