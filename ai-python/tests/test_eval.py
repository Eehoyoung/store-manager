"""eval.py 하네스 테스트 — 골든셋을 읽어 지표를 계산하는지, 기준 미달 시 exit 1 인지."""
import json
from collections import Counter

import eval as eval_mod


# ── 골든셋 파일 자체의 무결성 (T2 산출물 검증) ───────────────────────────

def test_goldenset_category_ratio_matches_spec():
    rows = eval_mod.load_goldenset(eval_mod.GOLDENSET_DEFAULT)
    assert len(rows) == 100
    counts = Counter(r["category"] for r in rows)
    assert counts == {
        "PRAISE": 30, "POSITIVE": 25, "IMPROVEMENT": 20,
        "COMPLAINT": 15, "ABUSIVE": 5, "NOISE": 5,
    }


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
