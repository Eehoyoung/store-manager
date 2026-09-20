/**
 * 페어링 실패 문구.
 *
 * ★ 원인마다 사장님이 할 일이 완전히 다르다 — 코드가 틀렸으면 재발급, 서버에 못
 *   닿았으면 주소·서버 확인이다. 한 문구로 뭉치면 엉뚱한 곳을 고치게 된다.
 *   실기동(2026-09-20)에서 API 주소가 운영 서버로 남아 요청이 로컬에 닿지도
 *   않았는데 화면은 "코드가 올바르지 않습니다" 만 띄웠다. 코드는 멀쩡했다.
 *
 * ★ 모르는 이유도 숨기지 않고 코드를 그대로 보여준다. 조용히 뭉개면 원인을
 *   찾을 길이 없다(riskBanner 의 미지 사유 처리와 같은 규율).
 */
export function pairErrorMessage(reason?: string, baseUrl?: string): string {
  if (reason === "BAD_CODE") return "코드가 올바르지 않거나 만료되었습니다. 새로 발급받아 주세요.";
  if (reason === "UNREACHABLE") {
    return `서버에 연결하지 못했습니다 (${baseUrl ?? "주소 미상"}). 아래 'API 주소 설정' 을 확인해 주세요.`;
  }
  return `서버가 오류를 돌려줬습니다 (${reason ?? "원인 미상"}). 잠시 후 다시 시도해 주세요.`;
}

/**
 * 승인(초안 삽입) 실패 문구.
 *
 * ★ 초안은 **그 리뷰의** 입력창에만 넣는다. 열려 있는 아무 입력창에 넣으면 다른
 *   손님 리뷰에 남의 답글이 게시된다. 그래서 못 넣는 경우가 정상적으로 존재하고,
 *   그때 무엇을 해야 하는지 화면이 말해 줘야 한다. 조용히 실패하면 사장님은
 *   승인이 된 줄 안다.
 */
export function approveErrorMessage(reason?: string): string {
  switch (reason) {
    case "BOX_NOT_OPEN":
      return "네이버 화면에서 이 리뷰의 '답글 쓰기' 를 먼저 눌러 입력창을 열어 주세요.";
    case "REVIEW_NOT_FOUND":
      return "이 리뷰가 지금 화면에 보이지 않습니다. 리뷰 목록을 새로고침하거나 스크롤해 주세요.";
    case "NO_TAB":
      return "네이버 리뷰 페이지 탭을 찾지 못했습니다. 탭을 열고 다시 시도해 주세요.";
    case "NOT_VIEWED":
      return "먼저 리뷰 내용을 확인해 주세요.";
    case "NO_SPEC":
      return "리뷰 페이지 준비가 아직 끝나지 않았습니다. 잠시 후 다시 눌러 주세요.";
    default:
      return `승인하지 못했습니다 (${reason ?? "원인 미상"}).`;
  }
}
