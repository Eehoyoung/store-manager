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
  anthropicTransferCountry: "[출시 전 확인: Anthropic 실제 처리·재수탁 국가]",
  anthropicRetention:
    "입력·출력은 원칙적으로 수신·생성 후 30일 이내 자동 삭제(Usage Policy 위반 조사, 법적 의무, 별도 계약·서비스 설정 등 예외)",
  anthropicTraining:
    "상용 API 입력·출력은 기본적으로 모델 학습에 사용하지 않음(명시적 피드백 제공 또는 별도 허용 등 예외)",
} as const;

export const LEGAL_INFO_HAS_PLACEHOLDERS = Object.values(LEGAL_INFO).some((value) =>
  value.startsWith("[출시 전")
);
