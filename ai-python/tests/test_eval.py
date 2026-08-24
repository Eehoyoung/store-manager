"""eval.py 하네스 테스트 — 골든셋을 읽어 지표를 계산하는지, 기준 미달 시 exit 1 인지."""
import json
from collections import Counter

import eval as eval_mod


# ── 골든셋 파일 자체의 무결성 (T2 산출물 검증) ───────────────────────────

def test_goldenset_has_no_duplicate_bodies():
    """★ 같은 본문이 여러 번 들어가면 안 된다.

    예전 이 테스트는 카테고리별·업종별 '정확한 개수' 를 요구했다. 그 할당량을 채우려고
    같은 문장을 최대 5회까지 복사해 넣었고(2026-08-23 발견), 그 결과
      - 측정 비용만 늘고 신호는 늘지 않았다
      - 한 표현을 놓치면 5건이 한꺼번에 틀려 재현율이 실제보다 나쁘게 보였다
    개수를 맞추는 것보다 중복이 없는 것이 중요하다.
    """
    rows = eval_mod.load_goldenset(eval_mod.GOLDENSET_DEFAULT)
    bodies = [r["body"].strip() for r in rows if r["body"].strip()]
    dupes = {b for b in bodies if bodies.count(b) > 1}
    assert not dupes, f"중복 본문 {len(dupes)}종: {list(dupes)[:3]}"
    assert len({r["id"] for r in rows}) == len(rows), "id 중복"


def test_goldenset_covers_every_category_and_industry():
    """분포는 고정하지 않되, 어느 카테고리도 비어 있으면 안 된다.

    ABUSIVE 는 2026-08-25 에 8 → 32종으로 늘렸다(욕설·인신공격·조롱·협박·폐업요구·매장무관).
    8종일 때는 재현율 1건이 12.5%p 를 움직여 95% 게이트가 통계적으로 무의미했다.
    여전히 전부 합성 문장이므로, 실매장 리뷰가 쌓이면 실제 사례로 교체한다.
    """
    rows = eval_mod.load_goldenset(eval_mod.GOLDENSET_DEFAULT)
    counts = Counter(r["category"] for r in rows)
    for category in ("PRAISE", "POSITIVE", "IMPROVEMENT", "COMPLAINT", "ABUSIVE", "NOISE"):
        assert counts[category] >= 5, f"{category} 표본이 너무 적다: {counts[category]}건"
    assert counts["ABUSIVE"] >= 30, "ABUSIVE 표본을 30종 밑으로 줄이지 말 것 — 재현율이 불안정해진다"
    assert len({r["industry"] for r in rows}) >= 3


def test_high_risk_set_matches_spec():
    rows = eval_mod.load_goldenset(eval_mod.HIGH_RISK_DEFAULT)
    assert len(rows) == 50
    assert Counter(r["riskType"] for r in rows) == {
        "FOOD_POISONING": 15, "FOREIGN_OBJECT": 15, "HYGIENE": 10, "LEGAL": 5, "MEDIA": 5,
    }
    assert len({r["id"] for r in rows}) == 50


def test_high_risk_evaluation_exposes_context_false_positives():
    """맥락 오탐이 남아 있음을 드러내는 테스트.

    ★ 2026-08-25 갱신 — 키워드 룰을 좁히면서 재현율 0.978 → 1.000,
      정밀도 0.60 하한 → 0.957 이 됐다. H-030("깨 토핑을 플라스틱으로 착각했지만
      확인해 보니 깨였어요")은 근접 조건이 생기면서 더 이상 오탐이 아니다.
      이 테스트는 옛 버그를 정답으로 고정하고 있었으므로 함께 고친다.

    ★ 남은 2건은 의미 이해가 필요해 키워드로는 못 고친다. 방향은 안전측이다.
      H-015 "예전에 배탈 났었는데 이번에는 괜찮고" — 과거·부정 문맥
      H-029 "서비스로 받은 장난감 벌레가 귀엽고"   — 비유·사물 지칭
      이걸 고치겠다고 '배탈'·'벌레' 를 빼지 말 것. 진짜 사고를 놓친다.
    """
    report = eval_mod.evaluate_high_risk(eval_mod.load_goldenset(eval_mod.HIGH_RISK_DEFAULT))
    assert report["recall"] >= 0.95
    assert report["precision"] >= 0.60
    assert report["passed"] is True
    assert not report["misses"], f"고위험을 놓쳤다: {report['misses']}"
    assert {"H-015", "H-029"} <= set(report["false_positives"])


def test_goldenset_risk3_rows_all_marked_must_block():
    """절대규칙 3: risk_level>=3 은 골든셋에서도 mustBlockAutoPublish 누락이 0건이어야 한다."""
    rows = eval_mod.load_goldenset(eval_mod.GOLDENSET_DEFAULT)
    risk3_rows = [r for r in rows if r["expected"]["riskLevel"] >= 3]
    assert len(risk3_rows) >= 5  # 문서 12 §5.1: 위생/이물질/식중독/법적/언론 유형 커버
    assert all(r["expected"]["mustBlockAutoPublish"] for r in risk3_rows)


# ── keyword_risk() 룰 (문서 12 §1.2) ─────────────────────────────────────

def test_keyword_risk_food_poisoning():
    level, reasons = eval_mod.keyword_risk("먹고 배탈나서 응급실 갔어요")
    assert level == 3 and "FOOD_POISONING" in reasons


def test_keyword_risk_foreign_object():
    level, reasons = eval_mod.keyword_risk("머리카락이 나왔어요")
    assert level == 3 and "FOREIGN_OBJECT" in reasons


def test_keyword_risk_legal():
    level, reasons = eval_mod.keyword_risk("소비자원에 신고할 겁니다")
    assert level == 3 and "LEGAL" in reasons


def test_keyword_risk_no_trigger_is_zero():
    level, reasons = eval_mod.keyword_risk("맛있게 잘 먹었습니다")
    assert level == 0 and reasons == []


# ── evaluate() 지표 계산 ─────────────────────────────────────────────────

def _row(id_, category, rating, body, risk, reasons=None, must_block=None):
    if must_block is None:
        must_block = risk >= 2 or category == "ABUSIVE"
    return {
        "id": id_, "platform": "baemin", "category": category, "industry": "한식",
        "rating": rating, "body": body,
        "expected": {"riskLevel": risk, "riskReasons": reasons or [], "mustBlockAutoPublish": must_block},
    }


def test_evaluate_detects_risk3_and_passes_block_gate():
    """키워드 룰이 확실히 잡는 risk3 케이스만 넣으면 자동게시 차단 누락은 0이어야 한다."""
    rows = [
        _row("T-1", "COMPLAINT", 1, "이물질이 나왔어요 벌레가 들어있었습니다", 3, ["FOREIGN_OBJECT"]),
        _row("T-2", "PRAISE", 5, "정말 맛있게 잘 먹었습니다 다음에도 또 시킬게요", 0),
    ]
    report = eval_mod.evaluate(rows, threshold_recall=0.95)
    assert report["auto_publish_block_miss_count"] == 0
    assert report["gates"]["auto_publish_block_misses==0"] is True


def test_evaluate_reports_miss_when_block_case_undetectable():
    """키워드 룰로 못 잡는(별점만 낮은) risk2 사례는 폴백이 놓칠 수 있다 — 그 사실이 그대로 보고돼야 한다."""
    rows = [
        _row("T-3", "COMPLAINT", 1, "그냥 별로였어요 다시는 안 시킬 것 같습니다 화가 나네요", 2),
    ]
    report = eval_mod.evaluate(rows, threshold_recall=0.95)
    assert "T-3" in report["auto_publish_block_misses"]
    assert report["passed"] is False


def test_evaluate_category_accuracy_and_abusive_recall():
    rows = [
        _row("T-4", "NOISE", 5, "", 0),
        _row("T-5", "ABUSIVE", 1, "이 집 진짜 쓰레기같네", 1),
    ]
    # 하네스의 지표 계산 자체를 검증한다 — 분류기 품질은 별개이므로 폴백으로 고정한다.
    report = eval_mod.evaluate(rows, threshold_recall=0.5, classifier=None)
    assert report["category_accuracy"] == 1.0
    assert report["abusive_recall"] == 1.0


# ── CLI (main) — exit code ────────────────────────────────────────────────

def _write_jsonl(tmp_path, rows):
    path = tmp_path / "mini.jsonl"
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    return path


def test_cli_exit_1_when_block_case_missed(tmp_path):
    rows = [_row("T-6", "COMPLAINT", 1, "그냥 별로였어요 화가 나네요", 2)]
    path = _write_jsonl(tmp_path, rows)
    code = eval_mod.main(["--goldenset", str(path)])
    assert code == 1


def test_cli_exit_0_when_all_gates_pass(tmp_path):
    rows = [
        _row("T-7", "COMPLAINT", 1, "이물질이 나왔고 벌레도 있었어요 곰팡이 냄새까지 났습니다", 3, ["FOREIGN_OBJECT", "HYGIENE"]),
        _row("T-8", "ABUSIVE", 1, "이 집 진짜 쓰레기같네 정신 나갔네", 1),
        _row("T-9", "PRAISE", 5, "정말 맛있게 잘 먹었습니다 자주 시켜먹을 것 같아요", 0),
        _row("T-10", "NOISE", 5, "", 0),
    ]
    path = _write_jsonl(tmp_path, rows)
    code = eval_mod.main(["--goldenset", str(path), "--threshold-recall", "0.9"], classifier=None)
    assert code == 0


def test_골든셋_차단_기대값이_코드와_일치한다():
    """`mustBlockAutoPublish` 는 실제 차단 조건과 같아야 한다.

    ★ 코드상 차단 조건은 두 가지뿐이다.
        main.py               : category == "ABUSIVE"      → blocked
        guardrails.py G8      : risk_level >= 2            → blocked
      골든셋이 이와 다르면 채점 기준이 제품과 어긋나, 통과해도 의미가 없고
      떨어져도 원인을 알 수 없다.

    ★ 2026-08-25 실측에서 5건이 어긋나 있었다(같은 risk 2 인데 기대값이 갈렸다).
    """
    from guardrails import RISK_BLOCK_THRESHOLD

    rows = eval_mod.load_goldenset(eval_mod.GOLDENSET_DEFAULT)
    mismatched = [
        r["id"] for r in rows
        if r["expected"]["mustBlockAutoPublish"]
        != (r["category"] == "ABUSIVE" or r["expected"]["riskLevel"] >= RISK_BLOCK_THRESHOLD)
    ]
    assert not mismatched, f"차단 기대값이 코드와 다르다: {mismatched}"


def test_골든셋_risk3은_키워드_룰이_전부_잡는다():
    """★ 절대규칙 3 의 최종 확인. 하나라도 놓치면 그 리뷰는 자동 게시된다.

    모델이 아니라 키워드 룰만으로(base_level=0) 3 이 나와야 한다 —
    모델은 믿을 수 없다는 것이 이 룰의 존재 이유다.
    """
    from prompts import upgrade_risk_level

    rows = eval_mod.load_goldenset(eval_mod.GOLDENSET_DEFAULT)
    missed = [
        r["id"] for r in rows
        if r["expected"]["riskLevel"] >= 3 and upgrade_risk_level(r["body"], 0)[0] < 3
    ]
    assert not missed, f"고위험인데 키워드 룰이 못 잡는다: {missed}"
