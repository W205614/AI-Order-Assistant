"""Optional, durable pre-dispatch ledger for explicitly authorized paid evals.

The budget is conservative UTF-8 request bytes plus reserved output tokens, not
provider billing. Failed calls and retries are charged and never refunded. A lost
Redis acknowledgement blocks dispatch; it is never retried automatically.
"""
from __future__ import annotations

import redis
from .budget import CapacityExceeded
from .config import settings

_CHARGE = """
local calls=tonumber(redis.call('HGET',KEYS[1],'calls') or '0')
local units=tonumber(redis.call('HGET',KEYS[1],'budgetUnits') or '0')
local maxCalls=math.min(tonumber(redis.call('HGET',KEYS[1],'maxCalls') or ARGV[2]),tonumber(ARGV[2]))
local maxUnits=math.min(tonumber(redis.call('HGET',KEYS[1],'maxBudgetUnits') or ARGV[3]),tonumber(ARGV[3]))
redis.call('HSET',KEYS[1],'maxCalls',maxCalls,'maxBudgetUnits',maxUnits)
if calls+1>maxCalls or units+tonumber(ARGV[1])>maxUnits then return 0 end
redis.call('HSET',KEYS[1],'calls',calls+1,'budgetUnits',units+tonumber(ARGV[1]))
return 1
"""


def ledger_key() -> str:
    return "ai-order-agent:evaluation:" + settings.evaluation_run_id


def charge_provider_attempt(amount: int) -> None:
    if not settings.evaluation_run_id:
        return
    if not isinstance(amount, int) or isinstance(amount, bool) or amount <= 0:
        raise CapacityExceeded("evaluation_budget_invalid")
    from .main import _get_redis_client
    try:
        allowed = _get_redis_client().eval(_CHARGE, 1, ledger_key(), amount,
                                          settings.evaluation_max_calls, settings.evaluation_max_units)
    except redis.RedisError:
        raise CapacityExceeded("evaluation_ledger_unavailable") from None
    if int(allowed) != 1:
        raise CapacityExceeded("evaluation_budget_exhausted")


def status() -> dict | None:
    if not settings.evaluation_run_id:
        return None
    from .main import _get_redis_client
    try:
        values = _get_redis_client().hgetall(ledger_key())
        return {"runId": settings.evaluation_run_id,
                "providerAttempts": int(values.get("calls", 0)),
                "budgetUnits": int(values.get("budgetUnits", 0)),
                "maxProviderAttempts": min(int(values.get("maxCalls", settings.evaluation_max_calls)), settings.evaluation_max_calls),
                "maxBudgetUnits": min(int(values.get("maxBudgetUnits", settings.evaluation_max_units)), settings.evaluation_max_units),
                "unit": "conservative_request_bytes_plus_reserved_output", "available": True}
    except redis.RedisError:
        return {"runId": settings.evaluation_run_id, "available": False}
