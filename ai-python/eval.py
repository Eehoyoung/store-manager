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
import os
import json
import re
import sys
from pathlib import Path

import guardrails
import prompts

GOLDENSET_DEFAULT = Path(__file__).resolve().parent / "goldenset" / "reviews.jsonl"
HIGH_RISK_DEFAULT = Path(__file__).resolve().parent / "goldenset" / "high_risk.jsonl"
# "auto"면 main.py의 실제 분류 파이프라인을 사용한다.
AUTO = "auto"

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
"

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
"
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


def evaluate_high_risk(rows: list[dict], classifier=AUTO) -> dict:
    """고위험 강화셋의 위험 재현율과 정상 문맥 오탐을 별도로 계산한다."""
    main_classifier = _try_import_main_classifier() if classifier is AUTO else classifier
    positives = [row for row in rows if row["expected"]["riskLevel"] >= 3]
    negatives = [row for row in rows if row["expected"]["riskLevel"] < 3]
    predicted = {row["id"]: predict(row, main_classifier) for row in rows}
    misses = [row["id"] for row in positives if predicted[row["id"]]["risk_level"] < 3]
    false_positives = [row["id"] for row in negatives if predicted[row["id"]]["risk_level"] >= 3]
    recall = 1 - len(misses) / len(positives) if positives else 1.0
    true_positives = len(positives) - len(misses)
    precision = true_positives / (true_positives + len(false_positives)) if true_positives else 0.0
    false_positive_rate = len(false_positives) / len(negatives) if negatives else 0.0
    return {
        "total": len(rows),
        "positive_total": len(positives),
        "recall": recall,
        "precision": precision,
        "misses": misses,
        "false_positive_rate": false_positive_rate,
        "false_positives": false_positives,
        "used_main_classifier": main_classifier is not None,
        "passed": recall >= 0.95,
    }


def evaluate(rows: list[dict], threshold_recall: float = 0.95, classifier=AUTO) -> dict:
    """골든셋 rows 에 대해 문서 12 §6.1 지표를 계산한다.
"

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


# ── 실행 게이트 ────────────────────────────────────────────────────────────
# 골든셋 1회 평가는 500건 × 분류 호출이다. 실측 2,725,508 입력 토큰 / 약 4,400원(3회분).
# 매장이 몇 개 없는 단계에서 이걸 반복하면 매출보다 측정비가 커진다.
#
# ★ 두 조건을 모두 만족해야 돈다. 어느 하나라도 빠지면 실행하지 않는다.
#   1) 가입 매장 30개 이상
#   2) 운영자가 GOLDENSET_EVAL_ENABLED=true 로 직접 켰을 것
# ★ 30개를 넘겨도 자동으로 켜지지 않는다 — 켜는 것은 사람의 결정이다(2026-08-23 운영자 지시).
MIN_STORES_FOR_EVAL = int(os.environ.get("GOLDENSET_MIN_STORES", "30"))


def _store_count() -> int | None:
    """가입 매장 수. DB 를 못 읽으면 None (판단 불가)."""
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        return None
    try:
        import psycopg

        with psycopg.connect(dsn, connect_timeout=3) as conn, conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM store WHERE deleted_at IS NULL")
            return int(cur.fetchone()[0])
    except Exception:
        return None


def _check_gate() -> str | None:
    """실행을 막아야 하면 이유를 반환한다. 통과면 None.

    ★ 이 게이트가 막는 것은 '비용' 이지 '평가' 가 아니다. API 키가 없으면 실제 호출이
      나가지 않아(룰 기반 폴백으로 동작) 비용이 0 이므로 막지 않는다 — CI 의 유닛테스트가
      여기에 걸리면 안 된다.
    """
    if not os.environ.get("ANTHROPIC_API_KEY"):
        return None
    if os.environ.get("GOLDENSET_EVAL_ENABLED", "false").lower() != "true":
        return (
            "골든셋 평가가 꺼져 있습니다 (GOLDENSET_EVAL_ENABLED != true). "
            "1회 실행에 500건 x LLM 분류 호출이 나갑니다(실측 약 1,500원). "
            "켜려면: GOLDENSET_EVAL_ENABLED=true python eval.py"
        )
    stores = _store_count()
    if stores is None:
        return (
            "매장 수를 확인할 수 없어 실행하지 않습니다 (DATABASE_URL 미설정 또는 조회 실패). "
            "확인 없이 돌리려면 --force 를 주세요."
        )
    if stores < MIN_STORES_FOR_EVAL:
        return (
            f"가입 매장이 {stores}개로 기준({MIN_STORES_FOR_EVAL}개) 미만이라 실행하지 않습니다. "
            "이 단계에서는 측정비가 매출보다 큽니다. 안전 규칙은 무료 유닛테스트가 지킵니다. "
            "그래도 돌리려면 --force 를 주세요."
        )
    return None


def main(argv: list[str] | None = None, classifier=AUTO) -> int:
    parser = argparse.ArgumentParser(description="골든셋 자동 평가 하네스")
    parser.add_argument("--force", action="store_true",
                        help="실행 게이트를 무시한다. 비용이 발생하므로 의도적으로만 쓸 것")
    parser.add_argument("--goldenset", type=Path, default=GOLDENSET_DEFAULT)
    parser.add_argument("--threshold-recall", type=float, default=0.95)
    parser.add_argument("--high-risk", action="store_true")
    args = parser.parse_args(argv)

    if not args.force:
        blocked = _check_gate()
        if blocked:
            print("[골든셋 평가 실행 안 함]")
            print(blocked)
            return 0  # 게이트에 막힌 것은 실패가 아니다 — CI 를 빨간불로 만들지 않는다

    if args.high_risk:
        report = evaluate_high_risk(load_goldenset(HIGH_RISK_DEFAULT))
        print(f"[고위험 강화셋] 총 {report['total']}건 / 재현율 {report['recall']:.1%} / "
              f"정밀도 {report['precision']:.1%}")
        print(f"  누락: {report['misses']}")
        print(f"  오탐: {report['false_positive_rate']:.1%} {report['false_positives']}")
        return 0 if report["passed"] else 1

    rows = load_goldenset(args.goldenset)
    report = evaluate(rows, threshold_recall=args.threshold_recall, classifier=classifier)
    _print_report(report)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
