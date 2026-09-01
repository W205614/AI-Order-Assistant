"""A narrow deterministic router for explicit cart commands.

It deliberately handles only exact menu names plus unambiguous cart verbs.
Everything else stays on the existing LLM/tool path.  This keeps draft writes
reproducible without pretending to solve general natural-language ordering.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any


_INJECTION = re.compile(
    r"(?i)(ignore\s+(all\s+)?(previous|above)|system\s*prompt|developer\s*message|reveal.{0,30}prompt|"
    r"忽略.{0,12}(规则|指令)|系统提示词|开发者消息|泄露.{0,12}(提示词|密钥))"
)
_DIRECT_ORDER = re.compile(r"(?:^|[，。；;\s])(?:我要|我想要|给我|帮我点|点一?份|来一?份)")
_ADD = re.compile(r"(?:再加|再来|加一?份|多来一?份)")
_REMOVE = re.compile(r"(?:不要|删除|删掉|去掉)")
_CANCEL = re.compile(r"(?:算了|不点了|不要了|放弃(?:购物车|订单)?)")
_REMARK = re.compile(r"(?:备注|改备注)\s*([\u4e00-\u9fffA-Za-z0-9\s，,、.-]{1,80})")
_QUANTITY = re.compile(r"(?:改成|改为|换成)\s*([0-9一二两三四五六七八九十]+)\s*(?:份|个)?")
_CHINESE_NUMBER = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


@dataclass(frozen=True)
class CartRoute:
    tool: str
    arguments: dict[str, Any]
    event: str


def is_cart_candidate(message: str) -> bool:
    """Avoid a menu lookup for ordinary chat, recommendations and FAQ."""
    text = message.strip()
    return bool(text and not _INJECTION.search(text) and (
        _DIRECT_ORDER.search(text) or _ADD.search(text) or _REMOVE.search(text)
        or _CANCEL.search(text) or _REMARK.search(text) or _QUANTITY.search(text)
    ))


def build_cart_route(message: str, draft: dict[str, Any] | None, menu: list[dict[str, Any]]) -> CartRoute | None:
    """Build a safe draft operation from exact dish names, or fail closed."""
    text = message.strip()
    if not is_cart_candidate(text):
        return None
    available = [dish for dish in menu if dish.get("status") != 0 and int(dish.get("stock") or 0) > 0]
    matched = _matched_items(text, available)

    if draft is None:
        if not _DIRECT_ORDER.search(text) or _ADD.search(text) or not matched:
            return None
        return CartRoute("create_order_draft", {"items": matched}, "cart_router_draft_created")

    draft_id = str(draft.get("id") or draft.get("draftId") or "")
    current = _normalized_items(draft.get("items") or [])
    if not draft_id or not current:
        return None
    if _CANCEL.search(text) and not matched:
        return CartRoute("cancel_order_draft", {"draft_id": draft_id}, "cart_router_draft_cancelled")
    if _ADD.search(text) and matched:
        return CartRoute(
            "update_order_draft",
            {"draft_id": draft_id, "items": _merge_items(current, matched), "remark": draft.get("remark")},
            "cart_router_draft_updated",
        )
    if _REMOVE.search(text) and matched:
        removed_names = {str(item.get("dishName")) for item in matched}
        remaining = [item for item in current if str(item.get("dishName")) not in removed_names]
        if not remaining:
            return CartRoute("cancel_order_draft", {"draft_id": draft_id}, "cart_router_draft_cancelled")
        return CartRoute(
            "update_order_draft",
            {"draft_id": draft_id, "items": remaining, "remark": draft.get("remark")},
            "cart_router_draft_updated",
        )
    quantity = _single_quantity(text)
    if quantity is not None and len(current) == 1:
        changed = [dict(current[0], quantity=quantity)]
        return CartRoute(
            "update_order_draft",
            {"draft_id": draft_id, "items": changed, "remark": draft.get("remark")},
            "cart_router_draft_updated",
        )
    remark = _REMARK.search(text)
    if remark:
        return CartRoute(
            "update_order_draft",
            {"draft_id": draft_id, "items": current, "remark": remark.group(1).strip()},
            "cart_router_draft_updated",
        )
    return None


def _matched_items(text: str, menu: list[dict[str, Any]]) -> list[dict[str, Any]]:
    found: list[tuple[int, dict[str, Any]]] = []
    for dish in menu:
        name = str(dish.get("name") or "")
        dish_id = dish.get("id")
        if not name or not dish_id:
            continue
        position = text.find(name)
        if position < 0:
            continue
        quantity = _quantity_before_name(text, name)
        if quantity is None:
            return []
        found.append((position, {"dishId": int(dish_id), "dishName": name, "quantity": quantity}))
    return [item for _position, item in sorted(found, key=lambda value: value[0])]


def _quantity_before_name(text: str, name: str) -> int | None:
    prefix = text[:text.find(name)]
    match = re.search(r"([0-9一二两三四五六七八九十]+)\s*(?:份|个)?\s*$", prefix)
    if not match:
        return 1
    return _parse_quantity(match.group(1))


def _single_quantity(text: str) -> int | None:
    match = _QUANTITY.search(text)
    return _parse_quantity(match.group(1)) if match else None


def _parse_quantity(raw: str) -> int | None:
    value = int(raw) if raw.isdigit() else _CHINESE_NUMBER.get(raw)
    return value if value is not None and 1 <= value <= 99 else None


def _normalized_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in items:
        dish_id = item.get("dishId")
        name = item.get("dishName")
        quantity = item.get("quantity")
        if dish_id and name and isinstance(quantity, int) and quantity > 0:
            result.append({"dishId": int(dish_id), "dishName": str(name), "quantity": quantity})
    return result


def _merge_items(current: list[dict[str, Any]], additions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged = [dict(item) for item in current]
    by_id = {item["dishId"]: item for item in merged}
    for item in additions:
        existing = by_id.get(item["dishId"])
        if existing:
            existing["quantity"] += item["quantity"]
        else:
            new_item = dict(item)
            merged.append(new_item)
            by_id[new_item["dishId"]] = new_item
    return merged
