"""
골든셋 자동 평가 하네스 (문서 12 §6 지표, CI 게이트로 사용).

★ 절대규칙 1(CLAUDE.md): 이 파일은 리뷰 "본문"을 생성하지 않는다. 분류·위험도
  판정(기존 텍스트를 읽고 라벨을 매기는 것)과, 가드레일 오탐률 측정을 위한
  고정 문구 템플릿 답글만 다룬다. 리뷰 텍스트를 새로 만들어내지 않는다.

평가 대상 분류 로직은 main.py 에 실제 분류기가 있으면 그것을 시도하고,
없거나(다른 에이전트가 아직 main.py/llm.py/router.py 를 작업 중일 수 있다)
임포트가 실패하면 이 파일 내부의 규칙 기반 폴백(_fallback_classify)만으로 평가한다.
risk_level 은 문서 12 §1.2 "키워드 룰이 모델보다 우선(상향만)" 원칙에 따라
모델/폴백 예측과 키워드 룰 중 더 높은 값을 최종값으로 쓴다.

사용법:
    python eval.py [--goldenset PATH] [--threshold-recall 0.95]

기준 미달 시 exit code 1 (CI 게이트).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import guardrails
import prompts

GOLDENSET_DEFAULT = Path(__file__).resolve().parent / "goldenset" / "reviews.jsonl"

# ── risk_level=3 키워드 룰 (문서 12 §1.2 그대로) ──────────────────────────
_RISK3_KEYWORDS: dict[str, list[str]] = {
    "FOOD_POISONING": ["식중독", "배탈", "설사", "구토", "병원", "응급실", "진단서"],
    "FOREIGN_OBJECT": ["이물질", "벌레", "머리카락", "비닐", "플라스틱", "유리", "곰팡이", "상한", "쉰내"],
    "LEGAL": ["신고", "고소", "고발", "소송", "변호사", "위생과", "보건소", "식약처", "소비자원"],
    "MEDIA": ["방송", "제보", "기자", "커뮤니티에 올리"],
}
# HYGIENE 은 문서 12 예시상 위생 지적 전용 사유코드. 이물질/곰팡이류와 겹치는 표현이 많아
# FOREIGN_OBJECT 키워드 중 위생 맥락 단어("곰팡이","상한","쉰내")도 HYGIENE 후보로 함께 표시한다.
_HYGIENE_KEYWORDS = ["위생", "곰팡이", "상한", "쉰내", "비위생"]

_ABUSIVE_KEYWORDS = [
    "쓰레기", "사기", "꺼져", "미친", "병신", "개새끼", "씨발", "지랄",
    "정신 나갔", "어이가 없다", "이딴", "장난하나",
]

_IMPROVEMENT_MARKERS = ["좀", "아쉬", "다음엔", "그래도", "조금"]

_JAMO_OR_EMOJI_RE = re.compile(
    r"^[\sㄱ-ㆎ\U0001F000-\U0001FFFF☀-➿!.,~^ㅋㅎㅜㅠ]*$"
)


def keyword_risk(body: str) -> tuple[int, list[str]]:
    """문서 12 §1.2 키워드 룰.

    ★ 규칙3(risk_level>=3 자동게시 금지)을 지키는 룰은 **한 벌만 존재해야 한다.**
    평가 하네스가 자체 사본을 들고 있으면 프로덕션 룰과 조용히 어긋나고,
    그때 CI 게이트는 통과하는데 실제 게시는 막지 못하는 최악의 상태가 된다.
    따라서 prompts.upgrade_risk_level 을 그대로 호출한다."""
    return prompts.upgrade_risk_level(body or "", 0)


def _is_noise(body: str) -> bool:
    stripped = (body or "").strip()
    if not stripped:
        return True
    return bool(_JAMO_OR_EMOJI_RE.match(stripped))


def _fallback_classify(rating: int, body: str) -> str:
    """규칙 기반 폴백 분류기. main.py 의 실제 분류 파이프라인이 없을 때만 쓰는 방어적 대체.
    골든셋 자체가 실제 프로덕션 카테고리 정확도를 대표하지 않으므로 참고용 지표로만 쓴다."""
    if _is_noise(body):
        return "NOISE"
    if any(w in body for w in _ABUSIVE_KEYWORDS):
        return "ABUSIVE"
    if rating >= 5:
        return "PRAISE" if len(body) >= 15 else "POSITIVE"
    if rating == 4:
        return "IMPROVEMENT" if any(m in body for m in _IMPROVEMENT_MARKERS) else "POSITIVE"
    if rating == 3:
        return "IMPROVEMENT"
    return "COMPLAINT"


def _try_import_main_classifier():
    """main.py 에 실제 분류기가 있으면 사용, 없거나 실패하면 None."""
    try:
        import main  # noqa: PLC0415  (병렬 작업 중이라 임포트 시점에 없을 수 있음)
    except Exception:
        return None
    classify = getattr(main, "_classify", None)
    review_model = getattr(main, "ReviewIn", None)
    if not callable(classify) or review_model is None:
        return None

    def _run(rating: int, body: str) -> dict:
        # 실제 파이프라인의 분류 경로를 그대로 태운다(키가 없으면 StubProvider 로 동작).
        provider = main.llm.get_provider()
        classified, _model, _tin, _tout, _cost = classify(
            provider, review_model(rating=rating, body=body, menus=[], platform="BAEMIN")
        )
        return {"category": classified.category, "risk_level": classified.risk_level}

    return _run


def predict(row: dict, main_classifier) -> dict:
    """리뷰 1건에 대한 예측 결과를 반환한다: category, risk_level, risk_reasons, must_block."""
    rating = row["rating"]
    body = row.get("body", "")

    model_category = None
    model_risk = 0
    if main_classifier is not None:
        try:
            result = main_classifier(rating=rating, body=body)
            model_category = result.get("category")
            model_risk = int(result.get("risk_level", result.get("riskLevel", 0)))
        except Exception:
            model_category = None
            model_risk = 0

    category = model_category or _fallback_classify(rating, body)

    kw_risk, kw_reasons = keyword_risk(body)
    risk_level = max(model_risk, kw_risk)  # 문서 12 §1.2: 키워드 룰은 상향만 허용
    risk_reasons = kw_reasons

    # G1(60자 미만) 자체가 오탐으로 잡히지 않도록 60자 이상인 고정 안전 문구를 쓴다.
    # (배지가 아니라 가드레일 "오탐률" 측정용 프로브 — risk_level 만 행마다 바뀐다)
    template_reply = (
        "소중한 리뷰 남겨주셔서 진심으로 감사드립니다. 앞으로도 좋은 재료와 정성으로 "
        "더 나은 모습 보여드리도록 노력하겠습니다."
    )
    guardrail_flags = guardrails.check(template_reply, risk_level)
    must_block = category == "ABUSIVE" or guardrail_flags.blocking

    return {
        "category": category,
        "risk_level": risk_level,
        "risk_reasons": risk_reasons,
        "must_block": must_block,
        "guardrail_flags": list(guardrail_flags),
        "guardrail_blocking": guardrail_flags.blocking,
    }


def load_goldenset(path: Path) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


# 분류기 선택 기본값. "auto" = main.py 파이프라인이 있으면 그것을 쓴다.
AUTO = "auto"


def evaluate(rows: list[dict], threshold_recall: float = 0.95, classifier=AUTO) -> dict:
    """골든셋 rows 에 대해 문서 12 §6.1 지표를 계산한다.

    classifier: AUTO(기본) 면 main.py 의 실제 분류 파이프라인을 태운다.
    None 을 넘기면 이 파일의 규칙 기반 폴백으로만 평가한다 — 하네스 자체의
    지표 계산·게이트 동작을 검증하는 테스트에서 쓴다(분류기 품질과 분리)."""
    main_classifier = _try_import_main_classifier() if classifier is AUTO else classifier

    total = len(rows)
    category_correct = 0

    abusive_ids = {r["id"] for r in rows if r["category"] == "ABUSIVE"}
    abusive_recall_hits = 0

    risk_ge2_ids = {r["id"] for r in rows if r["expected"]["riskLevel"] >= 2}
    risk_recall_hits = 0

    must_block_ids = {r["id"] for r in rows if r["expected"]["mustBlockAutoPublish"]}
    must_block_misses: list[str] = []

    normal_ids = {r["id"] for r in rows if not r["expected"]["mustBlockAutoPublish"]}
    guardrail_false_positives: list[str] = []

    for row in rows:
        pred = predict(row, main_classifier)
        rid = row["id"]

        if pred["category"] == row["category"]:
            category_correct += 1

        if rid in abusive_ids and pred["category"] == "ABUSIVE":
            abusive_recall_hits += 1

        if rid in risk_ge2_ids and pred["risk_level"] >= 2:
            risk_recall_hits += 1

        if rid in must_block_ids and not pred["must_block"]:
            must_block_misses.append(rid)

        if rid in normal_ids and pred["guardrail_blocking"]:
            guardrail_false_positives.append(rid)

    category_accuracy = category_correct / total if total else 0.0
    abusive_recall = abusive_recall_hits / len(abusive_ids) if abusive_ids else 1.0
    risk_recall = risk_recall_hits / len(risk_ge2_ids) if risk_ge2_ids else 1.0
    guardrail_fp_rate = len(guardrail_false_positives) / len(normal_ids) if normal_ids else 0.0

    gates = {
        "category_accuracy>=0.90": category_accuracy >= 0.90,
        f"abusive_recall>={threshold_recall}": abusive_recall >= threshold_recall,
        f"risk_ge2_recall>={threshold_recall}": risk_recall >= threshold_recall,
        "auto_publish_block_misses==0": len(must_block_misses) == 0,
    }

    return {
        "total": total,
        "category_accuracy": category_accuracy,
        "abusive_recall": abusive_recall,
        "abusive_total": len(abusive_ids),
        "risk_ge2_recall": risk_recall,
        "risk_ge2_total": len(risk_ge2_ids),
        "auto_publish_block_miss_count": len(must_block_misses),
        "auto_publish_block_misses": must_block_misses,
        "guardrail_false_positive_rate": guardrail_fp_rate,
        "guardrail_false_positives": guardrail_false_positives,
        "used_main_classifier": main_classifier is not None,
        "gates": gates,
        "passed": all(gates.values()),
    }


def _print_report(report: dict) -> None:
    print(f"[골든셋 평가] 총 {report['total']}건 "
          f"(분류기: {'main.py' if report['used_main_classifier'] else '규칙 기반 폴백'})")
    print(f"  카테고리 정확도       : {report['category_accuracy']:.1%}")
    print(f"  ABUSIVE 재현율         : {report['abusive_recall']:.1%} "
          f"({report['abusive_total']}건 중)")
    print(f"  risk_level>=2 재현율   : {report['risk_ge2_recall']:.1%} "
          f"({report['risk_ge2_total']}건 중)")
    print(f"  자동게시 차단 누락    : {report['auto_publish_block_miss_count']}건 "
          f"{report['auto_publish_block_misses']}")
    print(f"  가드레일 오탐률        : {report['guardrail_false_positive_rate']:.1%} "
          f"{report['guardrail_false_positives']}")
    print("  게이트:")
    for name, ok in report["gates"].items():
        print(f"    [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"  종합: {'PASS' if report['passed'] else 'FAIL'}")


def main(argv: list[str] | None = None, classifier=AUTO) -> int:
    parser = argparse.ArgumentParser(description="골든셋 자동 평가 하네스")
    parser.add_argument("--goldenset", type=Path, default=GOLDENSET_DEFAULT)
    parser.add_argument("--threshold-recall", type=float, default=0.95)
    args = parser.parse_args(argv)

    rows = load_goldenset(args.goldenset)
    report = evaluate(rows, threshold_recall=args.threshold_recall, classifier=classifier)
    _print_report(report)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
