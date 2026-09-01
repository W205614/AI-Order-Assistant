"""Safe, zero-LLM fast path for static FAQ questions.

This router intentionally covers only FAQ entries that do not inspect or
modify a user's current order.  Order status, cancellation and reminders keep
going through the Agent so the Java backend remains the source of truth.
"""
from __future__ import annotations

import re
from typing import Any

from .faq_store import search_faq


# These answers describe static demo-system policies.  Entries requiring an
# order lookup or a state-changing tool are deliberately excluded.
FAST_PATH_TITLES = frozenset({
    "如何申请退款",
    "支付失败怎么办",
    "如何联系商家/客服",
    "什么是待接单状态",
    "支付方式有哪些",
    "配送范围",
})

_INSTRUCTION_LIKE_TEXT = re.compile(
    r"(?i)(ignore\s+(all\s+)?(previous|above)|system\s*prompt|developer\s*message|reveal.{0,30}prompt|"
    r"忽略.{0,12}(规则|指令)|系统提示词|开发者消息|泄露.{0,12}(提示词|密钥))"
)
_ORDER_ACTION_LIKE_TEXT = re.compile(
    r"(?:帮我|给我|我要|我想|请)(?:.*?)(?:取消|催单|查询|查看|查一下|看一下).{0,12}(?:订单|这单|当前|最近)|"
    r"(?:取消|催)(?:这单|当前订单|我的订单)|订单\s*(?:#|号)"
)


def match_static_faq(question: str, threshold: float) -> dict[str, Any] | None:
    """Return one safe static FAQ entry, or ``None`` to use the Agent path.

    This is not a semantic intent classifier.  It remains a high-confidence
    lexical lookup and fails closed when the message resembles a prompt
    injection or an order operation.
    """
    normalized = question.strip()
    if not normalized or len(normalized) > 255:
        return None
    if _INSTRUCTION_LIKE_TEXT.search(normalized) or _ORDER_ACTION_LIKE_TEXT.search(normalized):
        return None
    hits = search_faq(normalized, threshold=threshold)
    if not hits:
        return None
    item, _score = hits[0]
    return item if item["title"] in FAST_PATH_TITLES else None
