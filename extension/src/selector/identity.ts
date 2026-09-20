/**
 * 리뷰 날짜 파싱과 리뷰 식별자 구성.
 *
 * ★ 왜 이 파일이 필요한가 (실측 2026-09-20)
 *   스마트플레이스 리뷰 DOM 에는 **리뷰 고유 ID 가 없다.** <li> 의 속성은 class 뿐이고
 *   data-review-id 같은 것이 없다. 그런데 reviewHash 는 중복 답글을 막는 유일한
 *   수단이라 무엇으로든 안정적으로 만들어야 한다.
 *
 * ★ 본문으로 해시를 만들면 안 된다. 본문은 "더보기"로 접혀 있다가 펼쳐지면 길이가
 *   달라진다(실측: 92자 + 더보기). 같은 리뷰가 펼침 전후로 서로 다른 해시를 갖게 되어
 *   두 번 처리된다.
 *
 * ★ 대신 작성자 프로필 URL + 작성일을 쓴다. 프로필 URL 은
 *   https://m.place.naver.com/my/{작성자ID}/review 형태로 작성자마다 고정이고,
 *   작성일과 묶으면 사실상 유일하다. 둘 다 본문 펼침과 무관하다.
 *
 * ★ authorRef(프로필 URL)는 서버로 보내지 않는다. 해시 재료로만 쓰고 버린다.
 */

export interface ReviewDates {
  /** 방문일 yyyy-MM-dd. 없으면 null */
  visitedAt: string | null;
  /** 작성일 yyyy-MM-dd. 없으면 null */
  writtenAt: string | null;
}

// "방문일2026. 9. 10(목)1번째작성일2026. 9. 16(수)영수증 인증" 처럼 라벨과 날짜가
// 공백 없이 이어져 온다. ★ 순서가 아니라 **라벨**로 찾는다 — 영수증 리뷰와 예약 리뷰의
// 행 구성이 달라 순서로 집으면 방문일을 작성일로 잘못 읽는다.
function findLabelled(text: string, label: string): string | null {
  const re = new RegExp(`${label}\\s*(\\d{4})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})`);
  const m = re.exec(text);
  if (!m) return null;
  const [, y, mo, d] = m;
  return `${y}-${mo.padStart(2, "0")}-${d.padStart(2, "0")}`;
}

export function parseReviewDates(dateBlock: string | null | undefined): ReviewDates {
  const text = (dateBlock ?? "").replace(/\s+/g, " ");
  return {
    visitedAt: findLabelled(text, "방문일"),
    writtenAt: findLabelled(text, "작성일"),
  };
}

/**
 * reviewHash 의 재료가 되는 식별 문자열.
 *
 * ★ 재료가 하나라도 비면 null 을 반환한다. 빈 값끼리 이어 붙이면 **서로 다른 리뷰가
 *   같은 해시를 갖는다** — 실측 하네스에서 실제로 두 리뷰가 같은 해시를 받았다.
 *   그렇게 되면 두 번째 리뷰는 중복으로 취급돼 영영 답글을 못 받는다.
 *   식별할 수 없으면 처리하지 않는 편이 조용히 뭉개는 것보다 낫다.
 */
export function reviewIdentity(
  authorRef: string | null | undefined,
  writtenAt: string | null | undefined,
  visitedAt?: string | null,
): string | null {
  const ref = (authorRef ?? "").trim();
  const day = (writtenAt ?? "").trim();
  if (!ref || !day) return null;
  // ★ 방문일까지 넣는다 (실측 2026-09-20). 작성자+작성일만으로는 부족했다 —
  //   같은 손님이 **같은 날 두 번의 방문**(5/24, 6/27)에 대해 각각 리뷰를 쓰고
  //   본문까지 같은 경우가 실제 데이터에 있었다. 서로 다른 리뷰인데 해시가 겹쳤다.
  //   방문일이 없는 리뷰도 있으므로 있을 때만 붙인다(빈 문자열이면 종전과 동일).
  return `${ref}|${day}|${(visitedAt ?? "").trim()}`;
}

/**
 * 한 번의 스캔 안에서 중복된 식별자를 찾는다.
 *
 * ★ 마지막 방어선이다. 위 조합으로도 못 가르는 리뷰가 남을 수 있다(방문일이 둘 다
 *   없고 같은 손님이 같은 날 두 건을 쓴 경우). 그때 그냥 두면 두 번째 리뷰가 첫
 *   번째의 답글로 덮이거나 중복 취급돼 조용히 사라진다.
 *   호출부는 여기 담긴 식별자를 **건너뛰고** 셀렉터 미스로 보고해야 한다.
 *   식별할 수 없으면 처리하지 않는다 — 잘못 붙이는 것보다 낫다.
 */
export function collidingIdentities(identities: (string | null)[]): Set<string> {
  const count = new Map<string, number>();
  for (const id of identities) {
    if (!id) continue;
    count.set(id, (count.get(id) ?? 0) + 1);
  }
  return new Set([...count.entries()].filter(([, n]) => n > 1).map(([id]) => id));
}
