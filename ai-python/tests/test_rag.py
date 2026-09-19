"""few-shot 검색 회귀 테스트.

★ DB 없이 돈다. psycopg 를 가짜 모듈로 갈아끼워 **어떤 SQL 을 어떤 인자로 보내는지**만 본다.
  실제 조회 결과가 아니라 검색 정책이 검증 대상이다.
"""
import sys
import types

import rag



# ── 2026-09-17: 매장별 학습 루프 ──────────────────────────────────────────


def test_검색이_분류축을_쿼리에_싣는다(monkeypatch):
    """few-shot 이 최신순이면 배달 지연 리뷰에 칭찬 답글 4건이 예시로 붙는다.

    ★ pgvector 를 쓰지 않는다 — embedding 이 아직 결정론적 해시라 의미 유사도가 없다(T-15).
      이미 계산해 둔 category·issue_tags 가 추가 비용 0 으로 더 나은 신호를 준다."""
    captured = {}

    class _Cur:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def execute(self, sql, params):
            captured.setdefault("sql", []).append(sql)
            captured.setdefault("params", []).append(params)
        def fetchall(self): return []

    class _Conn:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def cursor(self): return _Cur()

    fake = types.ModuleType("psycopg")
    fake.connect = lambda *a, **kw: _Conn()
    monkeypatch.setitem(sys.modules, "psycopg", fake)

    rag.fetch_examples("1", "배달이 늦었어요", k=4, category="COMPLAINT", issue_tags=["배달지연", "온도"])

    second = captured["sql"][1]
    assert "source = 'EDITED'" in second, "사람이 고친 답글이 최우선이어야 한다"
    assert "INTERSECT" in second, "이슈 태그 겹침으로 정렬해야 한다"
    assert "created_at DESC" in second, "단서가 없을 때의 폴백은 남겨 둔다"
    # 태그와 카테고리가 실제로 바인딩되는가
    assert captured["params"][1][1] == ["배달지연", "온도"]
    assert captured["params"][1][2] == "COMPLAINT"


def test_태그가_없어도_검색은_깨지지_않는다(monkeypatch):
    """RC_LIST 는 태그가 없다. 빈 배열이 와도 최신순 폴백으로 동작해야 한다."""

    class _Cur:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def execute(self, sql, params): pass
        def fetchall(self): return []

    class _Conn:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def cursor(self): return _Cur()

    fake = types.ModuleType("psycopg")
    fake.connect = lambda *a, **kw: _Conn()
    monkeypatch.setitem(sys.modules, "psycopg", fake)

    assert rag.fetch_examples("1", "본문", k=4) == []
    assert rag.fetch_examples("1", "본문", k=4, category=None, issue_tags=None) == []


# ── 2026-09-19: 네이버 경계 — RAG few-shot 에 platform 조건을 준다 ──────────────


def _capture_sql(monkeypatch):
    captured = {}

    class _Cur:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def execute(self, sql, params):
            captured.setdefault("sql", []).append(sql)
            captured.setdefault("params", []).append(params)
        def fetchall(self): return []

    class _Conn:
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def cursor(self): return _Cur()

    fake = types.ModuleType("psycopg")
    fake.connect = lambda *a, **kw: _Conn()
    monkeypatch.setitem(sys.modules, "psycopg", fake)
    return captured


def test_platform_인자를_안_넘긴_기존_배달_호출부는_SQL이_그대로다(monkeypatch):
    """platform 기본값(None)은 지금 배달 경로와 바이트 단위로 같은 SQL 을 내야 한다."""
    captured = _capture_sql(monkeypatch)

    rag.fetch_examples("1", "배달이 늦었어요", k=4, category="COMPLAINT", issue_tags=["배달지연"])
    baseline_sql = captured["sql"][1]

    captured2 = _capture_sql(monkeypatch)
    rag.fetch_examples("1", "배달이 늦었어요", k=4, category="COMPLAINT", issue_tags=["배달지연"], platform="BAEMIN")

    assert captured2["sql"][1] == baseline_sql, "배달 플랫폼 문자열을 명시해도 SQL이 달라지면 안 된다"
    assert "platform = 'NAVER'" not in baseline_sql


def test_네이버는_non_MANUAL_조회에_platform_조건이_붙는다(monkeypatch):
    captured = _capture_sql(monkeypatch)

    rag.fetch_examples("1", "친절하고 좋아요", k=4, category="PRAISE", issue_tags=[], platform="NAVER")

    second = captured["sql"][1]
    assert "platform = 'NAVER'" in second
    first = captured["sql"][0]
    assert "platform" not in first, "MANUAL 조회는 플랫폼 무관 — 조건을 걸지 않는다"
