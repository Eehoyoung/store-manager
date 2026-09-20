import { apiRequest } from "./client";

export interface BusinessInfo {
  name: string;
  serviceName: string;
  representative: string;
  address: string;
  phone: string;
  email: string;
  registrationNumber: string;
  mailOrderNumber: string;
  privacyOfficer: string;
  /** 아직 채워지지 않은 항목의 환경변수 이름. 비어 있으면 출시 표시 요건을 갖췄다는 뜻이다. */
  pending: string[];
}

export const legalApi = {
  businessInfo: () => apiRequest<BusinessInfo>("/legal/business-info"),
};
