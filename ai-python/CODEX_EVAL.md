# Codex 오프라인 골든셋 평가

Claude API·DB·DataAPI·게시 경로를 호출하지 않는 `prepare → 저장된 결과 → score` 하네스다.
모델 실행은 ChatGPT 로그인한 Codex CLI로 별도 수행한다. Codex 점수는 Claude 품질 검증이나
배포 승인 근거가 아니다. 결과 폴더는 Git에서 제외된 `tmp/`에 둔다.

저장소 루트에서 분류 입력을 준비한다. `--dataset`을 반복하여 가명처리된 실리뷰 후보도 넣을 수 있다.

```powershell
python ai-python/codex_eval.py prepare --task classify --model <실행할-Codex-모델> --batch-size 50 --out tmp/codex-classify
```

모델에는 `inputs/classify-NNNN.json`만 전달하고, 해당 `schemas/classify-NNNN.json`으로 구조화
출력을 제한한다. 정답이 든 `manifest.json`과 원본 골든셋 파일을 읽을 권한은 주지 않는다.
동일 시스템 프롬프트는 배치에서 공통화되며 `system_index`가 각 행의 지침을 가리킨다.

결과는 `outputs/classify-NNNN.json`에 저장한다. 배치의 `batch_id/input_hash/model/prompt_version`
값을 그대로 포함하고, `results`에 `{id,input_hash,status:"ok",output:{분류 JSON}}`을 각 행당 한 번 넣는다.
생성 배치의 `output`은 답글 문자열이다. 누락·중복·오류·해시·모델 불일치는 채점하지 않고 exit 2로 거부한다.

```powershell
python ai-python/codex_eval.py score --run tmp/codex-classify
python ai-python/codex_eval.py prepare --task generate --model <실행할-Codex-모델> --batch-size 20 --classification-run tmp/codex-classify --out tmp/codex-generate
python ai-python/codex_eval.py score --run tmp/codex-generate
```

생성 평가에도 분류 때와 동일한 `--dataset` 목록을 전달해야 한다. 생성 프롬프트에는 정답 대신
저장된 모델의 분류 결과를 사용한다. T0는 로컬 템플릿으로 처리하고 초안 금지 카테고리는 별도 분모로 집계한다.
RAG는 오프라인 기본 문체 샘플로 고정하며 DB에 접근하지 않는다. `main._generate_draft`와
`main._produce_variant`를 재사용해 실제 길이 후처리·가드레일·재생성을 검증한다.

`score.json`은 원시 답글 위반과 서비스 후처리 결과를 구분한다. 위험 3의 검수 초안은 `blocked:true`가
정상이다. 합성셋의 답글 중복률은 가상매장 반복 스트레스이며 운영 중복률이 아니다. 사실 주장 패턴·
사과 누락·메뉴 대응·이모지·도입부 반복은 검토 후보를 찾는 지표다. 입력 근거와 자연스러움은
블라인드 검토로 확정해야 한다. 정답 없는 실리뷰는 분류 정확도 분모에서 제외하며 사람이 라벨링한다.

exit 0은 완료 및 해당 진단 게이트 통과, exit 1은 완료했으나 진단 기준 미달, exit 2는 결과 미완료/검증 실패다.
골든셋 라벨이나 실리뷰 원문을 자동 수정하지 않는다. 전체셋으로 튜닝한 점수를 홀드아웃 품질로 보고하지 않는다.

검수한 실제 사장님 답글은 생성 준비의 `--style-examples <팩.json>` 또는
`prepare(..., style_examples=팩)`으로 전달할 수 있다. 팩은 `pack_version`, `store_id`,
`target_review_ids`, `examples`를 가진다. 예시는 최대 4개이며 각 항목은
`source_review_id`, `review_text`, `reply_text`, `rating`, `sample_type`(THANKS/APOLOGY/GENERAL),
`platform` 필수 필드와 선택 `category`·`issue_tags`로 구성한다.

팩 대상 ID와 명시된 매장 ID가 일치할 때만 사용하며, 자기 리뷰·동일 본문·다른 플랫폼·
다른 응대 슬롯 예시는 제외한다. 분류 입력에는 예시를 넣지 않으며 분류 원본 해시도 유지하므로
같은 분류 결과를 재사용할 수 있다. `manifest.json`과 채점 결과에 검수팩 버전·해시와 행별
사용 예시 출처를 남긴다. 예시는 실제 `rag.StyleExample`을 통해 프롬프트와 G7에 연결하지만
DB를 읽거나 쓰지 않는다. T0 템플릿은 스타일팩을 사용하지 않는다.
