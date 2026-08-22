# 골든셋 (`reviews.jsonl`)

**현재 500건** (문서 12 §5).

★ 절대규칙 1(CLAUDE.md): 이 파일의 리뷰 텍스트는 전부 사람이 직접 쓴 정적 픽스처다.
리뷰를 생성하는 함수·스크립트·API 는 이 저장소 어디에도 없다.

## 카테고리 비율 (문서 12 §5.1 매트릭스)

| 카테고리 | 건수 | 비율 |
|----------|-----:|-----:|
| PRAISE | 150 | 30% |
| POSITIVE | 125 | 25% |
| IMPROVEMENT | 100 | 20% |
| COMPLAINT | 75 | 15% |
| ABUSIVE | 25 | 5% |
| NOISE | 25 | 5% |

업종(한식/치킨/분식/카페/중식) 5종에 카테고리별 건수를 균등 배분했다
(예: PRAISE 30건 → 업종당 6건).

별도 `high_risk.jsonl` 50건은 위험 유형별 재현율과 정상 문맥 오탐을 검증한다.
`python eval.py --high-risk` 로 실행한다.

## 고위험(risk_level=3) 커버리지

COMPLAINT/ABUSIVE 블록에 식중독·이물질·위생·법적조치·언론제보 유형을 각각
최소 1건 이상 포함했다 (`expected.riskLevel: 3`, `expected.mustBlockAutoPublish: true`).
고위험 강화셋은 일반 500건과 섞지 않는다. 일반 분류 정확도와 위험 재현율의
분모가 서로 바뀌지 않게 하기 위해서다.

## 스키마

```json
{
  "id": "G-0001",
  "platform": "baemin | yogiyo | coupangeats",
  "category": "PRAISE|POSITIVE|IMPROVEMENT|COMPLAINT|ABUSIVE|NOISE",
  "industry": "한식|치킨|분식|카페|중식",
  "rating": 1-5,
  "body": "리뷰 원문 (사람이 직접 작성)",
  "expected": {
    "riskLevel": 0-3,
    "riskReasons": ["FOOD_POISONING","FOREIGN_OBJECT","HYGIENE","LEGAL","MEDIA"] 중 해당,
    "mustBlockAutoPublish": true|false
  }
}
```

`eval.py` 가 이 파일을 읽어 분류·위험도·가드레일 지표를 계산한다.
