"""Explicit per-request deadlines and conservative token reservations.

Deadline is issued by Java; model/tool arguments cannot change this context.
"""
from __future__ import annotations
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from threading import Lock
import time
from .config import settings


class BudgetExceeded(Exception):
    pass


class CapacityExceeded(Exception):
    pass


@dataclass
class Budget:
    merchant: int
    deadline: float
    tokens: int = 0
    reserved: bool = False


current: ContextVar[Budget | None] = ContextVar("agent_budget", default=None)
_lock = Lock()
_active: dict[int, int] = {}
_total = 0
_quotas: dict[tuple[int, int], int] = {}


def remaining(deadline: float | None = None) -> float:
    budget = current.get()
    end = deadline if deadline is not None else (budget.deadline if budget else None)
    if end is None:
        return settings.total_timeout
    value = end - time.time()
    if value <= 1:
        raise BudgetExceeded("agent_deadline")
    return value - 1  # connect/read overhead margin


@contextmanager
def admission(merchant: int, deadline: float):
    global _total
    with _lock:
        if _total >= settings.max_concurrent or _active.get(merchant, 0) >= settings.merchant_concurrent:
            raise CapacityExceeded("agent_capacity")
        _total += 1
        _active[merchant] = _active.get(merchant, 0) + 1
    token = current.set(Budget(merchant, min(deadline, time.time() + settings.total_timeout)))
    try:
        remaining()
        yield
    finally:
        current.reset(token)
        with _lock:
            _total -= 1
            _active[merchant] -= 1
            if not _active[merchant]:
                del _active[merchant]


def reserve_tokens(amount: int) -> None:
    budget = current.get()
    if budget is None:  # isolated model unit tests; all HTTP calls use admission
        return
    remaining()
    if amount > settings.max_prompt_bytes or budget.tokens + amount > settings.request_token_budget:
        raise BudgetExceeded("agent_token_budget")
    if not budget.reserved:
        if settings.rate_limit_backend == "redis":
            from .main import _get_redis_client
            script = """
local used=tonumber(redis.call('GET',KEYS[1]) or '0')
if used+tonumber(ARGV[1])>tonumber(ARGV[2]) then return 0 end
redis.call('INCRBY',KEYS[1],ARGV[1])
redis.call('EXPIRE',KEYS[1],172800)
return 1
"""
            key = f"{settings.rate_limit_key_prefix}:tokens:{budget.merchant}:{int(time.time()//86400)}"
            if int(_get_redis_client().eval(script, 1, key, settings.request_token_budget, settings.merchant_daily_tokens)) != 1:
                raise CapacityExceeded("merchant_token_quota")
        else:
            day = int(time.time() // 86400)
            with _lock:
                for key in list(_quotas):
                    if key[1] < day:
                        del _quotas[key]
                key = (budget.merchant, day)
                used = _quotas.get(key, 0)
                if used + settings.request_token_budget > settings.merchant_daily_tokens:
                    raise CapacityExceeded("merchant_token_quota")
                _quotas[key] = used + settings.request_token_budget
        budget.reserved = True
    # UTF-8 bytes plus reserved output tokens intentionally overestimates tokens.
    budget.tokens += amount


def gauges() -> dict:
    with _lock:
        return {"active": _total, "activeMerchants": len(_active)}
