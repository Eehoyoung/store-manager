import type { QueueState } from "./machine";

/** 사이드 패널에 보이는 승인 큐 한 항목. background 가 chrome.storage.local 에 보관한다. */
export interface QueueEntry {
  reviewHash: string;
  storeId: string;
  /** 별점. ★ null 은 "별점 없음" 이고 0 과 다르다 — 0 은 최저 평점이다. */
  rating: number | null;
  /** 마스킹된 리뷰 본문(1·2차 마스킹 완료). 원문이 아니다. */
  body: string;
  draftContent: string;
  blocked: boolean;
  riskLevel: number;
  /** 위험 사유 코드. 비어 있으면 위험 표시를 하지 않는다. */
  riskReasons: string[];
  state: QueueState;
  edited: boolean;
  detectedAt: number;
}
