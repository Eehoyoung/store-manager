-- 네이버 초안의 프롬프트 계보를 기록한다 (2026-09-19)
--
-- ★ 왜 필요한가 — 네이버는 배달과 **다른 프롬프트 라인**을 쓴다(naver-v0.x).
--   배달 라인(v2.x)은 골든셋 564건으로 검증됐지만 네이버 라인은 아직 평가셋이 없다.
--   어느 초안이 어느 프롬프트로 나왔는지 남기지 않으면, 나중에 품질 문제가 생겨도
--   "그때 그 프롬프트였나" 를 답할 수 없다. CLAUDE.md LLM 정책의
--   "prompt_version 을 DB에 기록. 변경 시 골든셋 회귀 테스트 필수" 가 요구하는 것이다.
--
-- ★ model 도 함께 남긴다. 티어 라우팅(T0~T3)이 어떤 모델을 골랐는지가 품질 분석의 축이다.
--   분류 모델이 'stub' 이면 실모델 없이 만든 초안이라는 뜻이라 특히 구분이 필요하다.
--
-- 리뷰 원문과는 무관한 메타데이터다 — 본문 컬럼은 여전히 만들지 않는다(V38 주석 참고).

ALTER TABLE naver_review_event ADD COLUMN prompt_version VARCHAR(20);
ALTER TABLE naver_review_event ADD COLUMN model VARCHAR(60);

COMMENT ON COLUMN naver_review_event.prompt_version IS '초안을 만든 프롬프트 버전(네이버는 naver-v0.x 라인)';
COMMENT ON COLUMN naver_review_event.model IS '초안 생성에 쓰인 LLM 모델 id. 실모델이 없으면 stub.';
