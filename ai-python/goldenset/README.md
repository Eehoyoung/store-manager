# 골든셋 (`reviews.jsonl`)

**현재 100건 / 목표 500건** (문서 12 §5).

★ 절대규칙 1(CLAUDE.md): 이 파일의 리뷰 텍스트는 전부 사람이 직접 쓴 정적 픽스처다.
리뷰를 생성하는 함수·스크립트·API 는 이 저장소 어디에도 없다.

## 카테고리 비율 (문서 12 §5.1 매트릭스, 100건 기준)

| 카테고리 | 건수 | 비율 |
|----------|-----:|-----:|
| PRAISE | 30 | 30% |
| POSITIVE | 25 | 25% |
| IMPROVEMENT | 20 | 20% |
| COMPLAINT | 15 | 15% |
| ABUSIVE | 5 | 5% |
| NOISE | 5 | 5% |

업종(한식/치킨/분식/카페/중식) 5종에 카테고리별 건수를 균등 배분했다
(예: PRAISE 30건 → 업종당 6건).

## 확장 시 규칙

500건으로 늘릴 때는 **이 비율을 유지한 채 이어붙인다.** 즉 각 카테고리 블록
끝에 같은 비율(업종 균등 배분 포함)로 새 줄을 추가하면 된다. `id` 는
`G-0101` 부터 이어서 부여한다. 카테고리 순서(PRAISE→POSITIVE→IMPROVEMENT→
COMPLAINT→ABUSIVE→NOISE)와 블록 구조를 유지하면 비율 검증이 쉽다.

## 고위험(risk_level=3) 커버리지

COMPLAINT/ABUSIVE 블록에 식중독·이물질·위생·법적조치·언론제보 유형을 각각
최소 1건 이상 포함했다 (`expected.riskLevel: 3`, `expected.mustBlockAutoPublish: true`).
500건 확장 시에도 문서 12 §5.2 고위험 강화셋 비율(재현율 95% 검증용)을 별도로
채워야 한다 — 이 파일은 그 전 단계인 "일반 100/500건" 매트릭스만 담당한다.

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
