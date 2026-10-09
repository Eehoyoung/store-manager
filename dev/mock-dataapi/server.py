"""개발용 DataAPI 목 서버 — 로컬 리허설 전용. 운영 compose 에 붙이지 말 것.

worker/tests/fixtures 의 실제 응답 JSON 을 그대로 돌려준다. 표준 라이브러리만 쓴다.

  POST /scrap/deliveryapp/{platform}/reviewManagement → {platform}_reviews.json
       LOGINID 가 'fail' 로 시작하면 login_fail.json (로그인 실패 리허설)
       REVIEWDATE 는 요청의 ENDDATE 로 바꿔 '최근 리뷰' 로 보이게 한다
  POST /scrap/deliveryapp/{platform}/CreateComment    → create_comment_success.json
  GET  /health

★ 요청 본문(LOGINPWD 포함)은 로그에 남기지 않는다 — 경로와 플랫폼만 찍는다(절대규칙 5).
"""
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FIXTURES = Path(os.environ.get("MOCK_FIXTURES", Path(__file__).resolve().parents[2] / "worker" / "tests" / "fixtures"))
ROUTE = re.compile(r"^/scrap/deliveryapp/(baemin|yogiyo|coupangeats)/(reviewManagement|CreateComment)$")


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def respond_to(platform: str, endpoint: str, req: dict) -> dict:
    if endpoint == "CreateComment":
        return load("create_comment_success.json")
    if str(req.get("LOGINID", "")).startswith("fail"):
        return load("login_fail.json")
    body = load(f"{platform}_reviews.json")
    end = str(req.get("ENDDATE") or "")
    if re.fullmatch(r"\d{8}", end):
        for store in body.get("data", {}).get("REVIEWLIST") or []:
            for review in store.get("LIST") or []:
                review["REVIEWDATE"] = end
    return body


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):  # noqa: N802
        self._send(200, {"status": "ok", "mock": True}) if self.path == "/health" else self._send(404, {})

    def do_POST(self):  # noqa: N802
        m = ROUTE.match(self.path)
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        if not m:
            return self._send(404, {"errCode": "404", "result": "FAIL"})
        print(f"[mock-dataapi] {m.group(2)} platform={m.group(1)}", flush=True)
        self._send(200, respond_to(m.group(1), m.group(2), req))

    def log_message(self, *args):  # 기본 접근 로그는 끈다 — 위 print 한 줄로 충분하다
        pass


if __name__ == "__main__":
    port = int(os.environ.get("MOCK_PORT", "9000"))
    print(f"[mock-dataapi] listening :{port} fixtures={FIXTURES}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
