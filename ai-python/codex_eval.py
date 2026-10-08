"""저장된 Codex 결과를 평가하는 오프라인 하네스. API·DB·CLI를 호출하지 않는다.

prepare classify → 모델별 배치 JSON 저장 → score → prepare generate → score 순서다.
모델에는 inputs/만 전달한다. manifest.json의 정답과 기존 사장님 답글은 전달하지 않는다.
Codex 결과는 Claude 품질이나 배포 승인 근거가 아니다. 실제 리뷰의 라벨은 사람이 정한다.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from unittest.mock import patch

import eval as golden_eval
import guardrails
import llm
import main as service
import prompts
import router


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def load_rows(paths: list[Path]) -> list[dict]:
    rows = []
    for path in paths:
        for source in golden_eval.load_goldenset(path):
            row = dict(source, corpus=path.stem)
            if not isinstance(row.get("id"), str) or not row["id"]:
                raise ValueError("리뷰 ID가 필요합니다")
            if "body" not in row or not isinstance(row["body"], str):
                raise ValueError("저장된 리뷰 본문이 필요합니다")
            # 기존 답글과 출처 메타데이터를 모델에 넣지 않고 요청 허용 필드만 취한다.
            request(row)
            rows.append(row)
    ids = [r["id"] for r in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("리뷰 ID가 중복되었습니다")
    if not rows:
        raise ValueError("빈 평가셋은 허용하지 않습니다")
    return rows


def request(row: dict) -> service.AnalyzeAndDraftRequest:
    return service.AnalyzeAndDraftRequest(
        reviewId=row["id"], storeId=row.get("store_id", "offline-eval-store"),
        review={"body": row["body"], "rating": row.get("rating"),
                "menus": row.get("menus", []), "platform": row.get("platform", "BAEMIN")},
        persona=row.get("persona", {"personaSeed": 17, "emojiLevel": 1}),
        storeFacts=row.get("store_facts", {}), recentReplies=row.get("recent_replies", []),
    )


def analysis_for(row: dict, prediction: dict) -> dict:
    parsed = prompts.ClassifyOutput.model_validate(prediction)
    platform = request(row).review.platform
    level, keyword_reasons = prompts.upgrade_risk_level(row["body"], parsed.risk_level, platform)
    tags = prompts.issue_tags_for(platform)
    issues = [t for t in parsed.issue_tags if t in tags]
    return {
        **parsed.model_dump(), "risk_level": level,
        "risk_reasons": sorted((set(parsed.risk_reasons) | set(keyword_reasons))
                               & set(prompts.RISK_REASON_VALUES)),
        "issue_tags": issues,
        "praised_tags": [t for t in parsed.praised_tags if t in tags and t not in issues],
        "tone": prompts.upgrade_tone(row["body"], parsed.tone),
    }


def draft_category_for(analysis: dict, platform: str) -> str | None:
    category = analysis["category"]
    if category not in prompts.NO_DRAFT_CATEGORIES:
        return category
    if prompts.is_visit_platform(platform):
        return "COMPLAINT"
    if category == "ABUSIVE" and analysis["risk_level"] >= guardrails.RISK_BLOCK_THRESHOLD:
        return prompts.abusive_draft_category(analysis["risk_reasons"])
    return None


def apology_policy(category: str, risk_reasons: list[str]) -> str:
    """매장 피해 상황은 사과를 강제하지 않는다. 미확인 주장에는 걱정·불편에 대한 사과가 필요하다."""
    if set(risk_reasons) & prompts._OWNER_IS_VICTIM_REASONS:
        return "OWNER_VICTIM_NO_REQUIRED_APOLOGY"
    if set(risk_reasons) & prompts._UNVERIFIED_CLAIM_REASONS:
        return "EMPATHY_WITHOUT_ADMISSION"
    return "COMPLAINT_APOLOGY" if category == "COMPLAINT" else "NO_REQUIRED_APOLOGY"


class SavedProvider:
    """메모리에 저장된 답글만 반환한다. 네트워크 client와 provider 선택을 쓰지 않는다."""
    client = None

    def __init__(self, text: str = "", model: str = "capture"):
        self.text, self.model, self.messages = text, model, None

    def complete(self, system, user, model, max_tokens):
        self.messages = (system, user)
        return llm.LlmResult(self.text, self.model, 0, 0, 0.0)


def validate_style_examples(examples: list[dict]) -> list[dict]:
    """사람이 검수한 저장 예시만 허용한다. 리뷰나 답글을 새로 만들지 않는다."""
    if not isinstance(examples, list) or len(examples) > 4:
        raise ValueError("스타일 예시는 최대 4개여야 합니다")
    ids = set()
    for example in examples:
        if not isinstance(example, dict):
            raise ValueError("스타일 예시 형식이 잘못되었습니다")
        for field in ("source_review_id", "reply_text", "sample_type", "platform"):
            if not isinstance(example.get(field), str) or not example[field].strip():
                raise ValueError(f"스타일 예시 필수 문자열: {field}")
        if not isinstance(example.get("review_text"), str) or "rating" not in example:
            raise ValueError("스타일 예시 리뷰 본문과 별점 필드가 필요합니다")
        rating = example["rating"]
        if rating is not None and (type(rating) is not int or not 0 <= rating <= 5):
            raise ValueError("스타일 예시 별점이 잘못되었습니다")
        if example["sample_type"] not in ("THANKS", "APOLOGY", "GENERAL"):
            raise ValueError("스타일 예시 슬롯이 잘못되었습니다")
        if example["platform"].upper() not in ("BAEMIN", "YOGIYO", "COUPANGEATS", "NAVER"):
            raise ValueError("스타일 예시 플랫폼이 잘못되었습니다")
        if example.get("category") is not None and example["category"] not in prompts.CATEGORY_VALUES:
            raise ValueError("스타일 예시 카테고리가 잘못되었습니다")
        if "issue_tags" in example and (not isinstance(example["issue_tags"], list) or
                                        any(not isinstance(tag, str) for tag in example["issue_tags"])):
            raise ValueError("스타일 예시 태그가 잘못되었습니다")
        if example["source_review_id"] in ids:
            raise ValueError("스타일 예시 출처 ID가 중복되었습니다")
        ids.add(example["source_review_id"])
    return examples


def validate_style_pack(pack: dict | None) -> dict | None:
    if pack is None:
        return None
    if not isinstance(pack, dict) or not all(isinstance(pack.get(k), str) and pack[k].strip()
                                           for k in ("pack_version", "store_id")):
        raise ValueError("검수 스타일팩의 버전과 매장 ID가 필요합니다")
    targets = pack.get("target_review_ids")
    if not isinstance(targets, list) or not targets or any(not isinstance(rid, str) or not rid for rid in targets):
        raise ValueError("검수 스타일팩 대상 리뷰 ID 목록이 필요합니다")
    validate_style_examples(pack.get("examples"))
    return pack


def style_examples_for(row: dict, category: str, pack: dict | None = None) -> list[dict]:
    if pack is not None:
        validate_style_pack(pack)
        if row["id"] not in pack["target_review_ids"]:
            return []
        if row.get("store_id") and str(row["store_id"]) != pack["store_id"]:
            raise ValueError("스타일팩과 대상 리뷰의 매장이 다릅니다")
        examples = pack["examples"]
    else:
        examples = row.get("style_examples", [])
        validate_style_examples(examples)
    return [example for example in examples
            if example["source_review_id"] != row["id"]
            and example["review_text"].strip() != row["body"].strip()
            and example["platform"].upper() == request(row).review.platform.upper()
            and example["sample_type"] in (prompts.style_slot_for(category), "GENERAL")]


def generation_call(function, provider, row: dict, analysis: dict, category: str,
                    style_pack: dict | None = None):
    req = request(row)
    tier = router.route(req.review.rating, req.review.body, category, analysis["risk_level"],
                        issue_tag_count=len(analysis["issue_tags"]))
    # 서비스의 동일 프롬프트·티어·재생성·가드레일을 재사용하되 DB 검색을 완전히 차단한다.
    examples = [service.rag.StyleExample(e["review_text"], e["reply_text"], e["rating"],
                fallback=True, sample_type=e["sample_type"]) for e in style_examples_for(row, category, style_pack)]
    with patch("main.rag.fetch_examples", return_value=examples):
        if function is service._generate_draft:
            return function(provider, tier, category, req, 0, analysis["issue_tags"],
                            analysis["risk_reasons"], analysis["tone"],
                            analysis["praised_tags"], analysis["risk_level"])
        return function(provider, tier, category, req, 0, analysis["risk_level"],
                        analysis["issue_tags"], analysis["risk_reasons"],
                        analysis["tone"], analysis["praised_tags"])


def result_schema(batch: dict) -> dict:
    if batch["task"] == "classify":
        output = prompts.ClassifyOutput.model_json_schema()
        output["additionalProperties"] = False
        # 배치 도구의 구조화 출력은 기본값이 있는 필드도 모두 출력하도록 요구한다.
        output["required"] = list(output["properties"])
    else:
        output = {"type": "string"}
    return {"type": "object", "additionalProperties": False,
            "properties": {
                "batch_id": {"type": "string", "enum": [batch["batch_id"]]},
                "input_hash": {"type": "string", "enum": [batch["input_hash"]]},
                "model": {"type": "string", "enum": [batch["model"]]},
                "prompt_version": {"type": "string", "enum": [batch["prompt_version"]]},
                "results": {"type": "array", "items": {"type": "object", "additionalProperties": False,
                    "properties": {"id": {"type": "string", "enum": [r["id"] for r in batch["rows"]]},
                                   "input_hash": {"type": "string", "enum": [r["input_hash"] for r in batch["rows"]]},
                                   "status": {"type": "string", "enum": ["ok", "error"]},
                                   "output": output},
                    "required": ["id", "input_hash", "status", "output"]}}},
            "required": ["batch_id", "input_hash", "model", "prompt_version", "results"]}


def validate_results(run: Path) -> tuple[dict, dict[str, dict]]:
    manifest = read_json(run / "manifest.json")
    if digest(manifest["rows"]) != manifest["source_hash"]:
        raise ValueError("평가 원본이 준비 이후 변경되었습니다")
    if manifest.get("style_pack") is not None and digest(manifest["style_pack"]) != manifest.get("style_pack_hash"):
        raise ValueError("검수 스타일팩이 준비 이후 변경되었습니다")
    predictions = {}
    for meta in manifest["batches"]:
        batch = read_json(run / "inputs" / f"{meta['batch_id']}.json")
        actual_hash = digest({k: v for k, v in batch.items() if k != "input_hash"})
        if batch["input_hash"] != actual_hash or meta["input_hash"] != actual_hash:
            raise ValueError("모델 입력이 준비 이후 변경되었습니다")
        result_path = run / "outputs" / f"{meta['batch_id']}.json"
        if not result_path.is_file():
            raise ValueError(f"미완료 배치: {meta['batch_id']}")
        result = read_json(result_path)
        for key in ("batch_id", "input_hash", "model", "prompt_version"):
            if result.get(key) != batch[key]:
                raise ValueError(f"결과 메타데이터 불일치: {key}")
        expected = {r["id"]: r for r in batch["rows"]}
        found = set()
        for item in result.get("results", []):
            rid = item.get("id")
            if rid not in expected or rid in found or rid in predictions:
                raise ValueError("결과 ID가 중복되거나 평가 대상과 다릅니다")
            found.add(rid)
            if item.get("input_hash") != expected[rid]["input_hash"]:
                raise ValueError("행별 입력 해시가 다릅니다")
            if item.get("status") != "ok":
                raise ValueError(f"모델 오류/미완료: {rid}")
            output = item.get("output")
            if manifest["task"] == "classify":
                output = prompts.ClassifyOutput.model_validate(output, strict=True).model_dump()
            elif not isinstance(output, str) or not output.strip():
                raise ValueError("생성 답글이 없습니다")
            predictions[rid] = {"output": output, "model": result["model"]}
        if found != set(expected):
            raise ValueError("누락된 결과가 있습니다")
    expected_ids = {r["id"] for r in manifest["rows"] if r["id"] not in manifest["local"]}
    if set(predictions) != expected_ids:
        raise ValueError("전체 평가셋 결과가 일치하지 않습니다")
    return manifest, predictions


def prepare(rows: list[dict], out: Path, *, task: str, model: str, batch_size: int = 20,
            classification_run: Path | None = None, style_examples: dict | None = None) -> dict:
    if not model.strip() or batch_size < 1 or task not in ("classify", "generate"):
        raise ValueError("모델명·양수 배치 크기·평가 유형이 필요합니다")
    if out.exists() and any(out.iterdir()):
        raise ValueError("새 평가 디렉터리를 사용하세요")
    style_pack = validate_style_pack(style_examples)
    if task == "classify" and style_pack is not None:
        raise ValueError("분류 입력에는 스타일팩을 전달하지 않습니다")
    predictions = {}
    classifier_hash = None
    classifier_versions = None
    if task == "generate":
        if classification_run is None:
            raise ValueError("생성 평가는 저장된 분류 결과가 필요합니다")
        prior, predictions = validate_results(classification_run)
        if prior["task"] != "classify":
            raise ValueError("분류 실행 결과가 아닙니다")
        prior_rows = {r["id"]: r for r in prior["rows"]}
        if any(r["id"] not in prior_rows or digest(r) != digest(prior_rows[r["id"]]) for r in rows):
            raise ValueError("분류 입력과 생성 평가 입력이 다릅니다")
        classifier_hash = digest(prior)
        classifier_versions = prior.get("prompt_versions") or sorted({read_json(classification_run / "inputs" / f"{b['batch_id']}.json")["prompt_version"] for b in prior["batches"]})
    tasks, local, contexts = [], {}, {}
    for row in rows:
        req = request(row)
        version = prompts.prompt_version_for(req.review.platform)
        if task == "classify":
            body, _, _ = guardrails.sanitize_review(row["body"])
            system, user = prompts.build_classify_messages(body, req.review.rating,
                                                          req.review.menus, req.review.platform)
        else:
            analysis = analysis_for(row, predictions[row["id"]]["output"])
            category = draft_category_for(analysis, req.review.platform)
            contexts[row["id"]] = {"analysis": analysis, "draft_category": category,
                                    "style_source_review_ids": [e["source_review_id"] for e in style_examples_for(row, category, style_pack)] if category else []}
            if category is None:
                local[row["id"]] = {"reason": "NO_DRAFT_CATEGORY", "blocked": True}
                continue
            provider = SavedProvider()
            raw = generation_call(service._generate_draft, provider, row, analysis, category, style_pack)
            if provider.messages is None:
                contexts[row["id"]]["style_source_review_ids"] = []
                local[row["id"]] = {"reason": "T0_TEMPLATE", "content": raw[0]}
                continue
            system, user = provider.messages
        payload = {"id": row["id"], "system": system, "user": user, "prompt_version": version}
        payload["input_hash"] = digest(payload)
        tasks.append(payload)
    batches = []
    for start in range(0, len(tasks), batch_size):
        group = tasks[start:start + batch_size]
        versions = {r["prompt_version"] for r in group}
        if len(versions) != 1:
            raise ValueError("배달·네이버 버전은 별도 실행으로 준비하세요")
        systems = list(dict.fromkeys(r["system"] for r in group))
        batch_id = f"{task}-{len(batches) + 1:04d}"
        batch = {"batch_id": batch_id, "task": task, "model": model,
                 "prompt_version": next(iter(versions)), "systems": systems,
                 "instructions": "각 행을 독립 요청으로 처리하고 해당 system을 따르세요. 리뷰 본문을 새로 쓰거나 수정하지 마세요. 모든 ID를 정확히 한 번 반환하세요.",
                 "rows": [{"id": r["id"], "system_index": systems.index(r["system"]),
                           "user": r["user"], "input_hash": r["input_hash"]} for r in group]}
        batch["input_hash"] = digest(batch)
        write_json(out / "inputs" / f"{batch_id}.json", batch)
        write_json(out / "schemas" / f"{batch_id}.json", result_schema(batch))
        batches.append({"batch_id": batch_id, "input_hash": batch["input_hash"]})
    manifest = {"format_version": 1, "task": task, "model": model, "rows": rows,
                "prompt_versions": sorted({prompts.prompt_version_for(request(r).review.platform) for r in rows}),
                "source_hash": digest(rows), "classification_manifest_hash": classifier_hash,
                "classification_prompt_versions": classifier_versions,
                "style_pack": style_pack, "style_pack_hash": digest(style_pack) if style_pack else None,
                "batches": batches, "local": local, "contexts": contexts,
                "label_policy": "정답 라벨은 모델 입력에 포함하지 않음; 실리뷰는 사람 라벨 확정 필요",
                "rag_policy": "검수 스타일팩 및 기본 문체 샘플만 사용; 자기 리뷰·동일본문·다른플랫폼 제외; DB 접근 없음"}
    write_json(out / "manifest.json", manifest)
    (out / "outputs").mkdir(exist_ok=True)
    return {"task": task, "rows": len(rows), "model_rows": len(tasks),
            "local_rows": len(local), "batches": len(batches), "source_hash": digest(rows)}


def _classifier(rows, predictions):
    cursor = iter(rows)

    def classify(rating, body):
        row = next(cursor)
        if (rating, body) != (row.get("rating"), row["body"]):
            raise ValueError("평가 순서가 입력과 다릅니다")
        return predictions[row["id"]]["output"]
    classify.provider_name = "saved-codex"
    return classify


def score(run: Path) -> dict:
    manifest, predictions = validate_results(run)
    rows = manifest["rows"]
    report = {"task": manifest["task"], "model": manifest["model"],
              "source_hash": manifest["source_hash"], "complete": True,
              "prompt_versions": manifest.get("prompt_versions", sorted({read_json(run / "inputs" / f"{b['batch_id']}.json")["prompt_version"] for b in manifest["batches"]})),
              "classification_prompt_versions": manifest.get("classification_prompt_versions"),
              "style_pack_version": (manifest.get("style_pack") or {}).get("pack_version"),
              "style_pack_hash": manifest.get("style_pack_hash"),
              "claude_quality_verified": False, "paid_api_calls": 0,
              "scope": "저장된 Codex 결과; 실서비스 Claude 품질 및 게시 적합률은 미검증"}
    bodies = Counter(r["body"] for r in rows if r["body"].strip())
    report["repeated_body_groups"] = sum(count > 1 for count in bodies.values())
    report["split_policy"] = "전체 평가셋 진단. 별도 학습/홀드아웃 분리 없음; 프롬프트 수정 후 점수는 홀드아웃 품질 주장 불가"
    details = []
    if manifest["task"] == "classify":
        regular = [r for r in rows if "category" in r and "expected" in r]
        high = [r for r in rows if "category" not in r and "expected" in r]
        report["classification"] = golden_eval.evaluate(regular, classifier=_classifier(regular, predictions)) if regular else None
        report["high_risk"] = golden_eval.evaluate_high_risk(high, classifier=_classifier(high, predictions)) if high else None
        report["unlabeled_total"] = len(rows) - len(regular) - len(high)
        report["passed"] = bool(regular) and report["classification"]["passed"] and (not high or report["high_risk"]["passed"])
        for row in rows:
            details.append({"id": row["id"], "corpus": row["corpus"],
                            "model_analysis": predictions[row["id"]]["output"],
                            "final_analysis": analysis_for(row, predictions[row["id"]]["output"]),
                            "expected": row.get("expected"), "expected_category": row.get("category")})
    else:
        recent = {}
        for row in rows:
            rid = row["id"]
            context = manifest["contexts"][rid]
            analysis, category = context["analysis"], context["draft_category"]
            if category is None:
                details.append({"id": rid, "no_draft_category": True, "blocked": True})
                continue
            local = manifest["local"].get(rid)
            raw = local["content"] if local else predictions[rid]["output"]
            req = request(row)
            provider = SavedProvider(raw, manifest["model"])
            draft, final_flags = generation_call(service._produce_variant, provider, row, analysis, category, manifest.get("style_pack"))
            selected_examples = [] if local else style_examples_for(row, category, manifest.get("style_pack"))
            flags = guardrails.check(raw, analysis["risk_level"], review_body=row["body"],
                                    recent_replies=req.recent_replies + [e["reply_text"] for e in selected_examples],
                                    extra_banned_words=[(r.word, r.category, r.match_type) for r in req.persona.global_banned_words] + [(word, "STORE", "CONTAINS") for word in req.persona.banned_words],
                                    min_length=guardrails.min_length_for(category, analysis["tone"]))
            store_key = row.get("store_id") or row["corpus"]
            similarity = max((guardrails._ngram_similarity(raw, text) for text in recent.get(store_key, [])), default=0.0)
            recent.setdefault(store_key, []).append(raw)
            policy = apology_policy(category, analysis["risk_reasons"])
            apology_missing_raw = category == "COMPLAINT" and not re.search("죄송|미안|사과|송구", raw)
            apology_missing = apology_missing_raw and policy != "OWNER_VICTIM_NO_REQUIRED_APOLOGY"
            situation_max = min(280, prompts.length_max_for(category, req.persona.length_max,
                                analysis["risk_level"], analysis["risk_reasons"], analysis["tone"]))
            minimum = guardrails.min_length_for(category, analysis["tone"])
            grounded_menus = [menu for menu in req.review.menus if menu in raw]
            # 사실성은 정규식으로 확정할 수 없다. 검토 후보만 표시하고 합격률에 섞지 않는다.
            claims = bool(re.search(r"(교육|교체|개선|변경|점검|확인)(했|하였|완료)|매일|국내산|수제|직접\s*(조리|만들)", raw))
            duplicate = similarity >= guardrails.DUPLICATE_SIMILARITY_THRESHOLD
            details.append({"id": rid, "corpus": row["corpus"], "raw_content": raw,
                            "final_content": draft.content if draft else None,
                            "raw_guardrail_flags": list(flags), "final_content_flags": final_flags,
                            "blocked": draft is None or bool(final_flags) or analysis["risk_level"] >= guardrails.RISK_BLOCK_THRESHOLD,
                            "risk_level": analysis["risk_level"], "tier": draft.tier if draft else None,
                            "style_source_review_ids": [e["source_review_id"] for e in selected_examples],
                            "template": bool(local), "length": len(raw),
                            "emoji_count": len(re.findall(r"[\U0001F300-\U0001FAFF\u2600-\u27BF]", raw)),
                            "length_ok": guardrails.min_length_for(category, analysis["tone"]) <= len(raw) <= 280,
                            "length_min": minimum, "situation_max": situation_max,
                            "hard_length_ok": len(raw) <= 280,
                            "raw_situation_length_ok": minimum <= len(raw) <= situation_max,
                            "final_situation_length_ok": draft is not None and minimum <= len(draft.content) <= situation_max,
                            "cross_row_similarity": similarity, "duplicate": duplicate,
                            "apology_missing_raw": apology_missing_raw, "apology_policy": policy,
                            "apology_missing": apology_missing, "grounded_menus": grounded_menus,
                            "factual_claim_review_needed": claims,
                            "grounding_verified": False})
        generated = [r for r in details if not r.get("no_draft_category")]
        n = len(generated)
        count = Counter(f for r in generated for f in r["raw_guardrail_flags"])
        report["generation"] = {"total": len(rows), "generation_eligible": n,
                "no_draft_categories": len(rows) - n, "template_count": sum(r["template"] for r in generated),
                "length_compliance": sum(r["length_ok"] for r in generated) / n if n else None,
                "hard_length_compliance": sum(r["hard_length_ok"] for r in generated) / n if n else None,
                "raw_situation_length_compliance": sum(r["raw_situation_length_ok"] for r in generated) / n if n else None,
                "final_situation_length_compliance": sum(r["final_situation_length_ok"] for r in generated) / n if n else None,
                "raw_content_guardrail_clean_rate": sum(not any(f != "G8_RISK" for f in r["raw_guardrail_flags"]) for r in generated) / n if n else None,
                "raw_guardrail_counts": dict(count), "duplicate_rate": sum(r["duplicate"] for r in generated) / n if n else None,
                "duplicate_scope": "동일 corpus/명시된 store_id 안에서 비교. 합성셋은 단일 가상매장 반복 스트레스이며 운영 중복률이 아님",
                "emoji_average": sum(r["emoji_count"] for r in generated) / n if n else None,
                "opening_top10": Counter(r["raw_content"].split(".")[0][:25] for r in generated).most_common(10),
                "no_draft_expected_eligible_ids": [r["id"] for r in rows if r["id"] in manifest["local"] and manifest["local"][r["id"]]["reason"] == "NO_DRAFT_CATEGORY" and (r.get("category") not in prompts.NO_DRAFT_CATEGORIES and "expected" in r)],
                "apology_missing_ids": [r["id"] for r in generated if r["apology_missing"]],
                "apology_missing_raw_ids": [r["id"] for r in generated if r["apology_missing_raw"]],
                "factual_claim_review_ids": [r["id"] for r in generated if r["factual_claim_review_needed"]],
                "high_risk_block_misses": [r["id"] for r in generated if r["risk_level"] >= 3 and not r["blocked"]],
                "blocked_total": sum(r["blocked"] for r in details), "human_quality_score": None,
                "quality_limit": "근거·자연스러움·핵심 대응은 블라인드 검토 필요; 패턴 점수는 품질 확정 아님"}
        report["passed"] = bool(n) and report["generation"]["length_compliance"] == 1 and not report["generation"]["high_risk_block_misses"] and all(not r["apology_missing"] and not any(f != "G8_RISK" for f in r["raw_guardrail_flags"]) for r in generated)
    report["details"] = details
    write_json(run / "score.json", report)
    return report


def cli(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--task", choices=["classify", "generate"], required=True)
    prep.add_argument("--dataset", action="append", type=Path)
    prep.add_argument("--out", required=True, type=Path)
    prep.add_argument("--model", required=True)
    prep.add_argument("--batch-size", type=int, default=20)
    prep.add_argument("--classification-run", type=Path)
    prep.add_argument("--style-examples", type=Path)
    scoring = commands.add_parser("score")
    scoring.add_argument("--run", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            datasets = args.dataset or [golden_eval.GOLDENSET_DEFAULT, golden_eval.HIGH_RISK_DEFAULT]
            result = prepare(load_rows(datasets), args.out, task=args.task, model=args.model,
                             batch_size=args.batch_size, classification_run=args.classification_run,
                             style_examples=read_json(args.style_examples) if args.style_examples else None)
        else:
            result = score(args.run)
        print(json.dumps({k: v for k, v in result.items() if k != "details"}, ensure_ascii=False, indent=2))
        return 0 if args.command == "prepare" or result["passed"] else 1
    except (ValueError, OSError, KeyError, TypeError) as exc:
        print(json.dumps({"complete": False, "passed": False, "error": str(exc)}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(cli())
