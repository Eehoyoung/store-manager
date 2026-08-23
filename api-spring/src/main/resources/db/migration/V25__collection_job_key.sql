-- 워커가 만든 작업 식별자를 담는다 (T-5 수집 이력 복구).
--
-- 지금까지 collection_job 에는 단 한 행도 쌓이지 않았다. 워커는 uuid4().hex 를 jobId 로 보내는데
-- Spring 은 그 값을 BIGSERIAL collection_job.id 로 해석하려다 실패해 갱신을 통째로 건너뛰었다.
-- 결과: 수집 성공률(T-5)을 측정할 근거가 없다.
--
-- 방향을 바꾼다. 워커가 우리 id 를 알 방법이 없으므로(그러려면 새 엔드포인트가 필요한데
-- 서비스 간 경계상 만들 수 없다), Spring 이 결과를 받을 때 행을 만든다.
-- job_key 는 그 상관관계 식별자다 — 백필 한 번이 13개 구간으로 쪼개지므로,
-- 같은 job_key 를 가진 여러 행이 한 번의 백필을 이룬다.
ALTER TABLE collection_job ADD COLUMN job_key VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_job_key ON collection_job (job_key) WHERE job_key IS NOT NULL;

COMMENT ON COLUMN collection_job.job_key IS
    '워커가 생성한 작업 식별자. 백필은 구간마다 한 행이며 같은 job_key 를 공유한다.';
