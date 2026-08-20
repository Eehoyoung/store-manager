// HQ 화면 전용 코드값 한국어 변환. web/src/lib/labels.ts 는 가맹점 화면 소유라 건드리지 않고
// HQ 전용 코드값(serviceStatus, linkStatus)만 여기서 별도로 다룬다.
// 목록에 없는 새 코드가 오면 원본을 그대로 보여준다 — "알 수 없는 오류"로 뭉개지 않는다(문서 14 §5.1 원칙과 동일).

const SERVICE_STATUS_LABELS: Record<string, string> = {
  IN_SERVICE: "이용 중",
  SUSPENDED: "정지",
};

export function describeServiceStatus(code: string): string {
  return SERVICE_STATUS_LABELS[code] ?? code;
}

const LINK_STATUS_LABELS: Record<string, string> = {
  LINKED: "연동됨",
  PENDING: "연동 대기",
  ERROR: "연동 끊김",
};

export function describeLinkStatus(code: string): string {
  return LINK_STATUS_LABELS[code] ?? code;
}
