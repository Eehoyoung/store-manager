"""DataAPI 호출 예산 게이트 테스트.

★ 이 게이트가 없으면 호출을 다 쓴 뒤에야 로그로 알게 된다. 그때는 이미 과금이 끝났다.
"""
import pytest

import budget


class FakeRedis:
    """eval(Lua) 만 흉내 내는 최소 목. 실제 원자성은 Redis 가 보장한다."""

    def __init__(self, used=0):
        self.store = {}
        if used:
            self.store["dataapi:calls:used"] = used

    def eval(self, script, numkeys, key, limit):
        used = int(self.store.get(key, 0))
        if int(limit) > 0 and used >= int(limit):
            return -1
        self.store[key] = used + 1
        return self.store[key]

    def get(self, key):
        v = self.store.get(key)
        return str(v).encode() if v is not None else None

    def set(self, key, value):
        self.store[key] = int(value)


def test_예산_안에서는_통과하고_사용량이_증가한다(monkeypatch):
    monkeypatch.setattr(budget, "CALL_BUDGET", 3)
    rc = FakeRedis()
    assert budget.reserve("reviewManagement", rc) == 1
    assert budget.reserve("reviewManagement", rc) == 2
    assert budget.reserve("reviewManagement", rc) == 3


def test_예산을_넘으면_호출하지_않고_막는다(monkeypatch):
    monkeypatch.setattr(budget, "CALL_BUDGET", 2)
    rc = FakeRedis(used=2)
    with pytest.raises(budget.BudgetExhaustedError):
        budget.reserve("reviewManagement", rc)
    # ★ 막힌 시도가 카운터를 더 올리면 안 된다 — 재시도할수록 잔여가 줄어드는 착시가 생긴다.
    assert budget.status(rc)["used"] == 2


def test_예산_0은_무제한이라_레디스를_건드리지_않는다(monkeypatch):
    monkeypatch.setattr(budget, "CALL_BUDGET", 0)
    assert budget.reserve("reviewManagement", redis_client=None) == 0


def test_예산소진은_재시도_대상이_아니다(monkeypatch):
    """재시도하면 예산만 더 태우고 절대 성공하지 않는다."""
    from dataapi import is_retryable_exception

    assert is_retryable_exception(budget.BudgetExhaustedError("소진")) is False


def test_남은_횟수를_볼_때는_호출하지_않는다(monkeypatch):
    monkeypatch.setattr(budget, "CALL_BUDGET", 50)
    rc = FakeRedis(used=3)
    assert budget.status(rc) == {"budget": 50, "used": 3, "remaining": 47}
