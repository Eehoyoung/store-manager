#!/usr/bin/env python3
"""자동 배포 관문 판정 — auto-deploy.sh 가 부른다. 표준 라이브러리만 쓴다(서버 python3).

입력: GitHub check-runs JSON 파일, 커밋 상태(combined status) JSON 파일.
출력: ok(배포) / wait(다음 주기) / fail(배포 안 함) 한 단어.

판정 (2026-10-21, D76 — GitHub Actions 한도 초과 우회):
  1. 끝난 check-run 중 success·skipped 가 아닌 것이 하나라도 있으면 fail. Actions 가 돌아왔을 때 실패를 덮지 않는다.
  2. 커밋 상태에 context "local-ci" 가 있으면 그 상태를 따른다 — success → ok, pending → wait, 그 밖 → fail.
     local-ci 상태는 deploy/local-ci.sh 가 그 커밋을 깨끗한 작업 트리에서 ci.yml 과 같은 단계로 통과시킨 뒤에만 기록한다.
  3. 없으면 기존 규칙: check-run 이 없거나 덜 끝났으면 wait, 전부 success·skipped 면 ok.
"""
import json
import sys

LOCAL_CI_CONTEXT = "local-ci"


def decide(check_runs: dict, combined_status: dict) -> str:
    runs = check_runs.get("check_runs", [])
    done = [r.get("conclusion") for r in runs if r.get("status") == "completed"]
    if any(c not in ("success", "skipped") for c in done):
        return "fail"
    local = [s for s in combined_status.get("statuses", []) if s.get("context") == LOCAL_CI_CONTEXT]
    if local:
        # statuses 는 최신순 — 같은 context 의 첫 항목이 현재 상태다.
        state = local[0].get("state")
        return {"success": "ok", "pending": "wait"}.get(state, "fail")
    if not runs or len(done) < len(runs):
        return "wait"
    return "ok"


def _demo() -> None:
    run = lambda c: {"status": "completed", "conclusion": c}
    st = lambda s: {"statuses": [{"context": LOCAL_CI_CONTEXT, "state": s}]}
    none = {"statuses": []}
    assert decide({"check_runs": []}, none) == "wait"                      # Actions 없음 + 증명 없음 → 대기(기존)
    assert decide({"check_runs": [run("success")]}, none) == "ok"           # 기존 초록
    assert decide({"check_runs": [run("failure")]}, st("success")) == "fail"  # Actions 실패는 로컬 증명으로 못 덮는다
    assert decide({"check_runs": []}, st("success")) == "ok"                # 우회: 로컬 CI 증명
    assert decide({"check_runs": []}, st("pending")) == "wait"
    assert decide({"check_runs": []}, st("failure")) == "fail"
    assert decide({"check_runs": [{"status": "in_progress"}]}, st("success")) == "ok"
    newest_first = {"statuses": [{"context": LOCAL_CI_CONTEXT, "state": "failure"},
                                 {"context": LOCAL_CI_CONTEXT, "state": "success"}]}
    assert decide({"check_runs": []}, newest_first) == "fail"
    print("ci_gate self-check ok")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-check":
        _demo()
        sys.exit(0)
    with open(sys.argv[1], encoding="utf-8") as f1, open(sys.argv[2], encoding="utf-8") as f2:
        print(decide(json.load(f1), json.load(f2)))
