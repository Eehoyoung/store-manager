export const LEGAL_INFO = {
  operatorName: "소담랩스",
  serviceName: "리뷰파일럿 (Review Pilot)",
  representative: "[출시 전 입력: 대표자명]",
  businessRegistrationNumber: "[출시 전 입력: 사업자등록번호]",
  mailOrderRegistrationNumber: "[출시 전 입력: 통신판매업 신고번호]",
  businessAddress: "[출시 전 입력: 사업장 주소]",
  customerServiceEmail: "[출시 전 입력: 고객센터 이메일]",
  customerServicePhone: "[출시 전 입력: 고객센터 전화번호]",
  privacyOfficer: "[출시 전 입력: 개인정보 보호책임자]",
  effectiveDate: "[출시 전 입력: 시행일]",
  dataApiProcessor: "[출시 전 입력: DataAPI 계약서상 수탁사 법인명]",
  paymentProvider: "[출시 전 입력: Groble 결제 서비스 계약서상 법인명]",
  cloudProvider: "[출시 전 입력: 프로덕션 클라우드 수탁사]",
  anthropicTransferCountry: "[출시 전 확인: Anthropic DPA 기준 이전 국가]",
  anthropicRetention: "[출시 전 확인: Anthropic API 계약 기준 보유기간]",
} as const;

export const LEGAL_INFO_HAS_PLACEHOLDERS = Object.values(LEGAL_INFO).some((value) =>
  value.startsWith("[출시 전")
);
