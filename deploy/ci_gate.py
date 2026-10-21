#!/usr/bin/env python3
"""자동 배포 관문 판정 — auto-deploy.sh 가 부른다. 표준 라이브러리만 쓴다(서버 python3).

입력: GitHub check-runs JSON 파일, 커밋 상태 목록(/statuses) JSON 파일, 저장소 소유자 로그인.
출력: ok(배포) / wait(다음 주기) / fail(배포 안 함) 한 단어.

판정 (2026-10-21, D76 — GitHub Actions 한도 초과 우회):
  1. 끝난 check-run 중 success·skipped 가 아닌 것이 하나라도 있으면 fail. Actions 가 돌아왔을 때 실패를 덮지 않는다.
  2. 커밋 상태에 context "local-ci" 가 있으면 그 상태를 따른다 — success → ok, pending → wait, 그 밖 → fail.
     ★ 작성자(creator.login)가 저장소 소유자 계정인 상태만 본다. 다른 앱·토큰이 쓴 local-ci 는 없는 것으로 친다
       (그 success 로 배포되지도, 그 failure 로 배포가 막히지도 않는다).
     local-ci 상태는 deploy/local-ci.sh 가 그 커밋을 깨끗한 작업 트리에서 ci.yml 과 같은 단계로 통과시킨 뒤에만 기록한다.
  3. 없으면 기존 규칙: check-run 이 없거나 덜 끝났으면 wait, 전부 success·skipped 면 ok.
"""
import json
import sys

LOCAL_CI_CONTEXT = "local-ci"


def decide(check_runs: dict, combined_status: dict, owner: str) -> str:
    runs = check_runs.get("check_runs", [])
    done = [r.get("conclusion") for r in runs if r.get("status") == "completed"]
    if any(c not in ("success", "skipped") for c in done):
        return "fail"
    local = [s for s in combined_status.get("statuses", [])
             if s.get("context") == LOCAL_CI_CONTEXT and (s.get("creator") or {}).get("login") == owner]
    if local:
        # statuses 는 최신순 — 같은 context 의 첫 항목이 현재 상태다.
        state = local[0].get("state")
        return {"success": "ok", "pending": "wait"}.get(state, "fail")
    if not runs or len(done) < len(runs):
        return "wait"
    return "ok"


def _demo() -> None:
    o = "owner"
    run = lambda c: {"status": "completed", "conclusion": c}
    st = lambda s, who=o: {"statuses": [{"context": LOCAL_CI_CONTEXT, "state": s, "creator": {"login": who}}]}
    none = {"statuses": []}
    assert decide({"check_runs": []}, none, o) == "wait"                       # Actions 없음 + 증명 없음 → 대기(기존)
    assert decide({"check_runs": [run("success")]}, none, o) == "ok"            # 기존 초록
    assert decide({"check_runs": [run("failure")]}, st("success"), o) == "fail"  # Actions 실패는 로컬 증명으로 못 덮는다
    assert decide({"check_runs": []}, st("success"), o) == "ok"                 # 우회: 로컬 CI 증명
    assert decide({"check_runs": []}, st("pending"), o) == "wait"
    assert decide({"check_runs": []}, st("failure"), o) == "fail"
    assert decide({"check_runs": [{"status": "in_progress"}]}, st("success"), o) == "ok"
    assert decide({"check_runs": []}, st("success", "some-bot"), o) == "wait"   # 남이 쓴 success 는 무시
    assert decide({"check_runs": []}, st("failure", "some-bot"), o) == "wait"   # 남이 쓴 failure 로 막히지도 않음
    assert decide({"check_runs": []}, {"statuses": [{"context": LOCAL_CI_CONTEXT, "state": "success"}]}, o) == "wait"
    newest_first = {"statuses": [{"context": LOCAL_CI_CONTEXT, "state": "failure", "creator": {"login": o}},
                                 {"context": LOCAL_CI_CONTEXT, "state": "success", "creator": {"login": o}}]}
    assert decide({"check_runs": []}, newest_first, o) == "fail"
    print("ci_gate self-check ok")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-check":
        _demo()
        sys.exit(0)
    # 사용: ci_gate.py <check-runs.json> <status.json> <저장소 소유자 로그인>
    # status.json 은 /commits/{sha}/statuses(전체 목록·최신순 배열)다. combined(/status)는 context 마다 최신 1건만 남겨
    # 남이 나중에 쓴 상태가 소유자 상태를 가리므로 쓰지 않는다. 배열이면 {"statuses": [...]} 로 맞춘다.
    with open(sys.argv[1], encoding="utf-8") as f1, open(sys.argv[2], encoding="utf-8") as f2:
        statuses = json.load(f2)
        if isinstance(statuses, list):
            statuses = {"statuses": statuses}
        print(decide(json.load(f1), statuses, sys.argv[3]))
