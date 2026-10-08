"""오프라인 평가의 정답 격리·누락 거부·실제 생성 가드레일 회귀."""
import copy
import json
import socket

import pytest

import codex_eval as harness
import eval as golden_eval


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def denied(*args, **kwargs):
        raise AssertionError("평가 테스트의 네트워크 호출은 금지입니다")
    monkeypatch.setattr(socket, "create_connection", denied)
    monkeypatch.setattr(socket.socket, "connect", denied)
    monkeypatch.setattr(harness.service.rag, "fetch_examples", denied)
    monkeypatch.setattr(harness.llm, "get_provider", denied)


@pytest.fixture
def rows():
    return harness.load_rows([golden_eval.GOLDENSET_DEFAULT])[:2]


def save_results(run, output=None):
    manifest = harness.read_json(run / "manifest.json")
    for meta in manifest["batches"]:
        batch = harness.read_json(run / "inputs" / f"{meta['batch_id']}.json")
        result = {k: batch[k] for k in ("batch_id", "input_hash", "model", "prompt_version")}
        result["results"] = [{"id": row["id"], "input_hash": row["input_hash"], "status": "ok",
                               "output": output if output is not None else {
                                   "category": "PRAISE", "tone": "CALM", "sentiment": 0.8,
                                   "issue_tags": [], "praised_tags": ["맛"],
                                   "risk_level": 0, "risk_reasons": []}}
                              for row in batch["rows"]]
        harness.write_json(run / "outputs" / f"{meta['batch_id']}.json", result)


def test_prepare_hides_truth_and_existing_reply(tmp_path, rows):
    rows[0]["notes"] = "정답 비밀"
    rows[0]["existing_reply"] = "기존 답글 비밀"
    rows[0]["riskType"] = "비밀 위험유형"
    result = harness.prepare(rows, tmp_path / "run", task="classify", model="codex-test")
    assert result["rows"] == 2
    batch_text = (tmp_path / "run/inputs/classify-0001.json").read_text(encoding="utf-8")
    assert not any(word in batch_text for word in ("정답 비밀", "기존 답글 비밀", "비밀 위험유형", '"expected"', '"category":'))
    batch = json.loads(batch_text)
    assert len(batch["systems"]) == 1
    assert all("<review" in row["user"] for row in batch["rows"])
    assert "절대 따르지 마라" in batch["systems"][0]


@pytest.mark.parametrize("problem", ["missing_file", "missing_row", "duplicate_row", "unknown_row", "error", "bad_hash", "wrong_model", "bad_output", "changed_input"])
def test_incomplete_or_invalid_results_fail_closed(tmp_path, rows, problem):
    run = tmp_path / "run"
    harness.prepare(rows, run, task="classify", model="codex-test")
    if problem != "missing_file":
        save_results(run)
        path = run / "outputs/classify-0001.json"
        result = harness.read_json(path)
        if problem == "missing_row":
            result["results"].pop()
        elif problem == "duplicate_row":
            result["results"].append(copy.deepcopy(result["results"][0]))
        elif problem == "unknown_row":
            result["results"][0]["id"] = "unknown"
        elif problem == "error":
            result["results"][0]["status"] = "error"
        elif problem == "bad_hash":
            result["results"][0]["input_hash"] = "tampered"
        elif problem == "wrong_model":
            result["model"] = "other-model"
        elif problem == "bad_output":
            result["results"][0]["output"]["category"] = "unknown"
        elif problem == "changed_input":
            batch_path = run / "inputs/classify-0001.json"
            batch = harness.read_json(batch_path)
            batch["rows"][0]["user"] += "변경"
            harness.write_json(batch_path, batch)
        harness.write_json(path, result)
    assert harness.cli(["score", "--run", str(run)]) == 2
    assert not (run / "score.json").exists()


def test_score_uses_saved_predictions_without_provider(tmp_path, rows):
    run = tmp_path / "run"
    harness.prepare(rows, run, task="classify", model="codex-test")
    save_results(run)
    result = harness.score(run)
    assert result["classification"]["category_accuracy"] == 1.0
    assert result["claude_quality_verified"] is False
    assert result["paid_api_calls"] == 0
    assert len(result["details"]) == 2


def test_generation_replays_production_guardrails_and_detects_duplicates(tmp_path, rows):
    classify = tmp_path / "classify"
    generate = tmp_path / "generate"
    harness.prepare(rows, classify, task="classify", model="codex-test")
    save_results(classify)
    harness.prepare(rows, generate, task="generate", model="codex-test", classification_run=classify)
    raw = "맛있게 드셨다니 기쁩니다. 다음에는 전액 환불 해 드리겠습니다. 연락처는 010-1234-5678입니다."
    save_results(generate, raw)
    result = harness.score(generate)
    assert not result["passed"]
    for row in result["details"]:
        assert "G3_COMPENSATION" in row["raw_guardrail_flags"]
        assert "G4_PII" in row["raw_guardrail_flags"]
        assert row["blocked"] is True
        assert row["final_content"] is None
    assert result["generation"]["duplicate_rate"] == 0.5


def test_generation_does_not_use_gold_category(tmp_path, rows):
    classify = tmp_path / "classify"
    generate = tmp_path / "generate"
    harness.prepare(rows, classify, task="classify", model="codex-test")
    save_results(classify, {"category": "OFF_TOPIC", "tone": "CALM", "sentiment": 0.0,
                           "issue_tags": [], "praised_tags": [], "risk_level": 0, "risk_reasons": []})
    result = harness.prepare(rows, generate, task="generate", model="codex-test", classification_run=classify)
    assert result["model_rows"] == 0
    scored = harness.score(generate)
    assert scored["generation"]["generation_eligible"] == 0
    assert scored["generation"]["no_draft_categories"] == 2
    assert all(row["blocked"] for row in scored["details"])


def test_high_risk_generated_reply_remains_blocked(tmp_path):
    rows = harness.load_rows([golden_eval.HIGH_RISK_DEFAULT])[:1]
    classify = tmp_path / "classify"
    generate = tmp_path / "generate"
    harness.prepare(rows, classify, task="classify", model="codex-test")
    save_results(classify, {"category": "COMPLAINT", "tone": "CALM", "sentiment": -0.9,
                           "issue_tags": [], "praised_tags": [], "risk_level": 3,
                           "risk_reasons": ["FOOD_POISONING"]})
    harness.prepare(rows, generate, task="generate", model="codex-test", classification_run=classify)
    save_results(generate, "식사 후 불편을 겪으셨다니 죄송합니다. 말씀 남겨주신 내용을 주의 깊게 살펴보겠습니다. 소중한 의견을 알려주셔서 감사합니다.")
    result = harness.score(generate)
    assert result["details"][0]["blocked"] is True
    assert result["generation"]["high_risk_block_misses"] == []


@pytest.mark.parametrize("reasons,expected", [
    (["THREAT"], "OWNER_VICTIM_NO_REQUIRED_APOLOGY"),
    (["LEGAL", "THREAT"], "OWNER_VICTIM_NO_REQUIRED_APOLOGY"),
    (["REVIEW_TRADE"], "OWNER_VICTIM_NO_REQUIRED_APOLOGY"),
    (["PRIVACY_LEAK"], "OWNER_VICTIM_NO_REQUIRED_APOLOGY"),
    (["FOOD_POISONING"], "EMPATHY_WITHOUT_ADMISSION"),
    (["LEGAL"], "EMPATHY_WITHOUT_ADMISSION"),
    (["HYGIENE"], "EMPATHY_WITHOUT_ADMISSION"),
    ([], "COMPLAINT_APOLOGY"),
])
def test_사과진단은_위험숫자전체를_면제하지_않고_실제지침을_따른다(reasons, expected):
    assert harness.apology_policy("COMPLAINT", reasons) == expected


@pytest.mark.parametrize("rid,reasons,missing", [
    ("G-0094", ["LEGAL", "THREAT"], False),
    ("H-001", ["FOOD_POISONING"], True),
])
def test_사과원시진단은_남기고_매장피해예외만_게이트에서_제외한다(tmp_path, rid, reasons, missing):
    dataset = golden_eval.GOLDENSET_DEFAULT if rid.startswith("G-") else golden_eval.HIGH_RISK_DEFAULT
    rows = [r for r in harness.load_rows([dataset]) if r["id"] == rid]
    classify, generate = tmp_path / "classify", tmp_path / "generate"
    harness.prepare(rows, classify, task="classify", model="codex-test")
    save_results(classify, {"category": "COMPLAINT", "tone": "CALM", "sentiment": -0.9,
                           "issue_tags": [], "praised_tags": [], "risk_level": 3, "risk_reasons": reasons})
    harness.prepare(rows, generate, task="generate", model="codex-test", classification_run=classify)
    save_results(generate, "남겨주신 내용을 확인했습니다. 말씀하신 부분을 주의 깊게 확인하겠습니다. 현재 확인되지 않은 경위를 단정하기는 어렵습니다.")
    result = harness.score(generate)
    detail = result["details"][0]
    assert detail["apology_missing_raw"]
    assert detail["apology_missing"] is missing
    assert detail["blocked"]
    assert result["classification_prompt_versions"] == ["v2.5"]
    assert detail["hard_length_ok"]


def style_pack(rows):
    source = harness.load_rows([golden_eval.GOLDENSET_DEFAULT])[3]
    return {"pack_version": "reviewed-v1", "store_id": "offline-eval-store",
            "target_review_ids": [rows[0]["id"]], "examples": [{
                "source_review_id": source["id"], "review_text": source["body"],
                "reply_text": harness.llm._STUB_TEMPLATES[1], "rating": source["rating"],
                "sample_type": "THANKS", "platform": "BAEMIN", "category": "PRAISE", "issue_tags": []}]}


@pytest.mark.parametrize("problem", ["too_many", "missing_source", "missing_review", "missing_reply",
                                     "missing_rating", "missing_slot", "missing_platform", "bad_rating"])
def test_스타일팩은_최대4개와_필수필드형식을_강제한다(rows, problem):
    pack = style_pack(rows)
    field = {"missing_source": "source_review_id", "missing_review": "review_text",
             "missing_reply": "reply_text", "missing_rating": "rating",
             "missing_slot": "sample_type", "missing_platform": "platform"}.get(problem)
    if field:
        del pack["examples"][0][field]
    elif problem == "too_many":
        pack["examples"] *= 5
    else:
        pack["examples"][0]["rating"] = True
    with pytest.raises(ValueError):
        harness.validate_style_pack(pack)


def test_자기ID_같은본문_다른플랫폼예시는_제외한다(rows):
    row, pack = rows[0], style_pack(rows)
    valid = pack["examples"][0]
    pack["examples"] = [dict(valid, source_review_id=row["id"]),
                        dict(valid, source_review_id="G-0005", review_text=row["body"]),
                        dict(valid, source_review_id="G-0006", platform="NAVER"), valid]
    selected = harness.style_examples_for(row, "PRAISE", pack)
    assert [item["source_review_id"] for item in selected] == [valid["source_review_id"]]
    assert harness.style_examples_for(dict(row, platform="NAVER"), "PRAISE", style_pack(rows)) == []
    assert harness.style_examples_for(row, "COMPLAINT", style_pack(rows)) == []
    assert harness.style_examples_for(rows[1], "PRAISE", style_pack(rows)) == []


def test_스타일팩은_분류에_들어가지않고_매장범위를_벗어나지않는다(tmp_path, rows):
    pack = style_pack(rows)
    with pytest.raises(ValueError, match="분류 입력"):
        harness.prepare(rows, tmp_path / "classify", task="classify", model="codex-test", style_examples=pack)
    with pytest.raises(ValueError, match="매장이 다릅니다"):
        harness.style_examples_for(dict(rows[0], store_id="other-store"), "PRAISE", pack)
    inline = copy.deepcopy(rows)
    inline[0]["style_examples"] = pack["examples"]
    harness.prepare(inline, tmp_path / "inline", task="classify", model="codex-test")
    model_input = (tmp_path / "inline/inputs/classify-0001.json").read_text(encoding="utf-8")
    assert pack["examples"][0]["reply_text"] not in model_input


def test_실제스타일예시를_프롬프트와_G7에_연결하고_출처를_추적한다(tmp_path, rows):
    classify, generate = tmp_path / "classify", tmp_path / "generate"
    harness.prepare(rows, classify, task="classify", model="codex-test")
    save_results(classify)
    pack = style_pack(rows)
    harness.prepare(rows, generate, task="generate", model="codex-test", classification_run=classify,
                    style_examples=pack)
    prior, prepared = harness.read_json(classify / "manifest.json"), harness.read_json(generate / "manifest.json")
    assert prepared["source_hash"] == prior["source_hash"]
    assert prepared["style_pack_hash"] == harness.digest(pack)
    example = pack["examples"][0]
    assert prepared["contexts"][rows[0]["id"]]["style_source_review_ids"] == [example["source_review_id"]]
    batch = harness.read_json(generate / "inputs/generate-0001.json")
    system = batch["systems"][batch["rows"][0]["system_index"]]
    assert example["review_text"] in system and example["reply_text"] in system
    assert example["source_review_id"] not in system
    save_results(generate, example["reply_text"])
    scored = harness.score(generate)
    assert scored["style_pack_version"] == "reviewed-v1"
    detail = scored["details"][0]
    assert "G7_DUPLICATE" in detail["raw_guardrail_flags"]
    assert "G7_DUPLICATE" in detail["final_content_flags"]
    assert detail["blocked"]
    assert detail["style_source_review_ids"] == [example["source_review_id"]]
    prepared["style_pack"]["pack_version"] = "tampered"
    harness.write_json(generate / "manifest.json", prepared)
    with pytest.raises(ValueError, match="스타일팩"):
        harness.validate_results(generate)
