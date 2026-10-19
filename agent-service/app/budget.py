"""Explicit per-request deadlines and conservative token reservations.

Deadline is issued by Java; model/tool arguments cannot change this context.
"""
from __future__ import annotations
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from threading import Lock
import logging
import time
import uuid
import redis
from .config import settings

logger = logging.getLogger(__name__)


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
    reserved_amount: int = 0
    quota_day: int = 0
    redis_key: str = ""
    receipt_key: str = ""
    input_tokens: int = 0
    output_tokens: int = 0
    reported_calls: int = 0


current: ContextVar[Budget | None] = ContextVar("agent_budget", default=None)
_lock = Lock()
_active: dict[tuple[str, int], int] = {}
_total = 0
_quotas: dict[tuple[int, int], int] = {}
_settlement_failures = 0

# Preserve the existing quota key/balance. A per-request receipt makes
# settlement idempotent after an uncertain network result on single Redis.
_RESERVE_QUOTA = """
local used=tonumber(redis.call('GET',KEYS[1]) or '0')
local amount=tonumber(ARGV[1])
if used+amount>tonumber(ARGV[2]) then return 0 end
redis.call('INCRBY',KEYS[1],amount)
redis.call('EXPIRE',KEYS[1],172800)
redis.call('SET',KEYS[2],amount,'EX',172800)
return 1
"""
_SETTLE_QUOTA = """
local reserved=tonumber(redis.call('GET',KEYS[2]) or '0')
if reserved==0 then return 0 end
local unused=math.max(0,reserved-tonumber(ARGV[1]))
local used=tonumber(redis.call('GET',KEYS[1]) or '0')
if used>0 then redis.call('DECRBY',KEYS[1],math.min(unused,used)) end
redis.call('DEL',KEYS[2])
return 1
"""


def _settle(budget: Budget) -> None:
    global _settlement_failures
    if not budget.reserved:
        return
    # Failed/timeout provider calls still retain their conservative reservation.
    # Only budget never dispatched to a provider can be returned.
    if budget.redis_key:
        try:
            from .main import _get_redis_client
            _get_redis_client().eval(_SETTLE_QUOTA, 2, budget.redis_key,
                                     budget.receipt_key, budget.tokens)
        except Exception:
            with _lock:
                _settlement_failures += 1
            logger.warning("Quota settlement unavailable; unused reservation retained")
    else:
        with _lock:
            key = (budget.merchant, budget.quota_day)
            if key in _quotas:
                _quotas[key] = max(0, _quotas[key] - budget.reserved_amount + budget.tokens)



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
def admission(merchant: int, deadline: float, *, campus_user_id: int | None = None):
    global _total
    scope = ("campus-user", campus_user_id) if campus_user_id is not None else ("merchant", merchant)
    limit = settings.campus_user_concurrent if campus_user_id is not None else settings.merchant_concurrent
    with _lock:
        if _total >= settings.max_concurrent or _active.get(scope, 0) >= limit:
            raise CapacityExceeded("agent_capacity")
        _total += 1
        _active[scope] = _active.get(scope, 0) + 1
    budget = Budget(merchant, min(deadline, time.time() + settings.total_timeout))
    token = current.set(budget)
    try:
        remaining()
        yield
    finally:
        try:
            _settle(budget)
        finally:
            current.reset(token)
            with _lock:
                _total -= 1
                _active[scope] -= 1
                if not _active[scope]:
                    del _active[scope]


def reserve_tokens(amount: int) -> None:
    budget = current.get()
    if budget is None:  # isolated model unit tests; all HTTP calls use admission
        return
    remaining()
    if amount > settings.max_prompt_bytes or budget.tokens + amount > settings.request_token_budget:
        raise BudgetExceeded("agent_token_budget")
    if not budget.reserved:
        budget.quota_day = int(time.time() // 86400)
        budget.reserved_amount = settings.request_token_budget
        daily_limit = settings.campus_daily_tokens if budget.merchant == 0 else settings.merchant_daily_tokens
        if settings.rate_limit_backend == "redis":
            from .main import _get_redis_client
            budget.redis_key = f"{settings.rate_limit_key_prefix}:tokens:{budget.merchant}:{budget.quota_day}"
            budget.receipt_key = f"{budget.redis_key}:reservation:{uuid.uuid4().hex}"
            try:
                allowed = _get_redis_client().eval(_RESERVE_QUOTA, 2, budget.redis_key,
                          budget.receipt_key, budget.reserved_amount, daily_limit)
            except redis.RedisError:
                # A lost acknowledgement may have charged the quota. Do not retry
                # or refund an uncertain reservation; fail closed before dispatch.
                raise CapacityExceeded("quota_backend_unavailable") from None
            if int(allowed) != 1:
                raise CapacityExceeded("campus_token_quota" if budget.merchant == 0 else "merchant_token_quota")
        else:
            with _lock:
                # Keep yesterday's bucket for requests crossing UTC midnight.
                for key in list(_quotas):
                    if key[1] < budget.quota_day - 1:
                        del _quotas[key]
                key = (budget.merchant, budget.quota_day)
                used = _quotas.get(key, 0)
                if used + budget.reserved_amount > daily_limit:
                    raise CapacityExceeded("campus_token_quota" if budget.merchant == 0 else "merchant_token_quota")
                _quotas[key] = used + budget.reserved_amount
        budget.reserved = True
    # UTF-8 bytes plus output allowance overestimates tokens. Charge before
    # dispatch: provider failures/retries cannot silently consume free quota.
    budget.tokens += amount


def gauges() -> dict:
    with _lock:
        return {"active": _total, "activeScopes": len(_active),
                "quotaSettlementFailures": _settlement_failures}


def record_provider_usage(usage) -> None:
    budget = current.get()
    if budget is None or usage is None:
        return
    incoming = getattr(usage, "prompt_tokens", None)
    outgoing = getattr(usage, "completion_tokens", None)
    if not all(isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= 1000000
               for v in (incoming, outgoing)):
        return
    budget.input_tokens += incoming
    budget.output_tokens += outgoing
    budget.reported_calls += 1


def provider_usage() -> dict | None:
    budget = current.get()
    if budget is None or not budget.reported_calls:
        return None
    return {"inputTokens": budget.input_tokens, "outputTokens": budget.output_tokens,
            "modelCalls": budget.reported_calls}
