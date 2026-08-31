"""Agent 工具：Function Schema + 执行函数。

执行函数回调 Java 点餐后端（菜单/下单/查单/取消/催单），返回给 LLM 可读文本。
"""
from __future__ import annotations

import json
import re
import time
import uuid
from threading import Lock
from typing import Any, Callable, Dict, List

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from ..config import settings
from ..gateway.java_client import JavaApiError
from ..rag.faq_store import search_faq

ORDER_STATUS = {
    1: "已下单", 2: "制作中", 3: "配送中", 4: "已送达", 5: "已取消", 6: "已超时",
}


class ToolContext:
    def __init__(self, jwt_token: str = "", request_id: str = ""):
        self.jwt_token = jwt_token  # 用户 JWT，回调 Java 时携带
        self.request_id = request_id
        self.pending_confirmation: Dict[str, Any] | None = None
        self.citations: List[Dict[str, str]] = []  # search_faq 命中时填充
        self.stage_timings: List[Dict[str, Any]] = []
        self._stage_timing_lock = Lock()

    def record_stage_timing(self, stage: str, elapsed_ms: float) -> None:
        """Keep only code-defined stage names and durations; never retain payloads."""
        with self._stage_timing_lock:
            self.stage_timings.append({"stage": stage, "latencyMs": round(max(elapsed_ms, 0), 4)})


class _StrictArgs(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


_SAFE_FREE_TEXT = re.compile(r"^[\u4e00-\u9fffA-Za-z0-9\s，。、“”‘’（）()、,.!！?？:：;；#&+\-_/]{0,255}$")
_INSTRUCTION_LIKE_TEXT = re.compile(
    r"(?i)(ignore\s+(all\s+)?(previous|above)|system\s*prompt|developer\s*message|reveal.{0,30}prompt|"
    r"忽略.{0,12}(规则|指令)|系统提示词|开发者消息|泄露.{0,12}(提示词|密钥))"
)


def _safe_free_text(value: str, field: str, max_length: int = 255) -> str:
    if len(value) > max_length or not _SAFE_FREE_TEXT.fullmatch(value):
        raise ValueError(f"{field}包含不允许的字符或长度超限")
    if _INSTRUCTION_LIKE_TEXT.search(value):
        raise ValueError(f"{field}包含疑似指令注入内容")
    return value


class _OrderItemArgs(_StrictArgs):
    dishId: int | None = Field(default=None, gt=0)
    dishName: str | None = Field(default=None, min_length=1, max_length=100)
    quantity: int = Field(default=1, ge=1, le=99)

    @field_validator("dishName")
    @classmethod
    def dish_name_is_safe(cls, value: str | None) -> str | None:
        return _safe_free_text(value, "菜品名称", 100) if value is not None else value


class _CreateDraftArgs(_StrictArgs):
    items: List[_OrderItemArgs] = Field(min_length=1, max_length=20)
    remark: str | None = Field(default=None, max_length=255)

    @field_validator("remark")
    @classmethod
    def remark_is_safe(cls, value: str | None) -> str | None:
        return _safe_free_text(value, "备注") if value is not None else value


class _UpdateDraftArgs(_CreateDraftArgs):
    draft_id: str

    @field_validator("draft_id")
    @classmethod
    def draft_id_is_uuid(cls, value: str) -> str:
        try:
            return str(uuid.UUID(value))
        except (ValueError, AttributeError) as exc:
            raise ValueError("draft_id 格式无效") from exc


class _DraftIdArgs(_StrictArgs):
    draft_id: str

    @field_validator("draft_id")
    @classmethod
    def draft_id_is_uuid(cls, value: str) -> str:
        try:
            return str(uuid.UUID(value))
        except (ValueError, AttributeError) as exc:
            raise ValueError("draft_id 格式无效") from exc


class _OrderIdArgs(_StrictArgs):
    order_id: int = Field(gt=0)


class _QueryOrderArgs(_StrictArgs):
    status: int | None = Field(default=None, ge=1, le=6)
    start_date: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    end_date: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")


class _PreferencesArgs(_StrictArgs):
    allergens: str | None = Field(default=None, max_length=255)
    dislikes: str | None = Field(default=None, max_length=255)
    dietaryGoal: str | None = Field(default=None, max_length=255)
    budget: float | None = Field(default=None, gt=0, le=9999)

    @field_validator("allergens", "dislikes", "dietaryGoal")
    @classmethod
    def preference_is_safe(cls, value: str | None) -> str | None:
        return _safe_free_text(value, "偏好") if value is not None else value


class _FaqArgs(_StrictArgs):
    question: str = Field(min_length=1, max_length=500)

    @field_validator("question")
    @classmethod
    def question_is_safe(cls, value: str) -> str:
        return _safe_free_text(value, "问题", 500)


class _ListMenuArgs(_StrictArgs):
    category: str | None = Field(default=None, max_length=50)
    keyword: str | None = Field(default=None, max_length=100)
    available_only: bool | None = None
    page: int = Field(default=1, ge=1)
    size: int = Field(default=30, ge=1, le=50)

    @field_validator("category", "keyword")
    @classmethod
    def menu_filter_is_safe(cls, value: str | None) -> str | None:
        return _safe_free_text(value, "菜单过滤条件", 100) if value is not None else value


_ARG_MODELS: Dict[str, type[BaseModel]] = {
    "get_food_preferences": _StrictArgs, "update_food_preferences": _PreferencesArgs,
    "list_menu": _ListMenuArgs, "create_order_draft": _CreateDraftArgs,
    "get_current_order_draft": _StrictArgs, "update_order_draft": _UpdateDraftArgs,
    "cancel_order_draft": _DraftIdArgs, "query_orders": _QueryOrderArgs,
    "get_order_detail": _OrderIdArgs, "cancel_order": _OrderIdArgs,
    "remind_order": _OrderIdArgs, "search_faq": _FaqArgs,
}


def _money(v) -> str:
    try:
        return f"¥{float(v):.2f}"
    except (TypeError, ValueError):
        return f"¥{v}"


def _client(ctx: ToolContext) -> "JavaClient":
    from ..gateway.java_client import JavaClient
    return JavaClient(timeout=settings.java_timeout, request_id=ctx.request_id)


def _fmt_order(o: Dict[str, Any]) -> str:
    status = ORDER_STATUS.get(o.get("status"), str(o.get("status")))
    items = "、".join(f"{i.get('dishName')}x{i.get('quantity')}" for i in (o.get("items") or []))
    seq = o.get("userSeq") or o.get("id")
    parts = [f"订单 #{seq} | {status} | 合计 {_money(o.get('totalAmount'))}",
             f"菜品：{items}", f"下单时间 {o.get('createTime')}"]
    if o.get("deliverAt"):
        parts.append(f"预计送达 {o.get('deliverAt')}")
    if o.get("deliverTime"):
        parts.append(f"实际送达 {o.get('deliverTime')}")
    return " | ".join(parts)


# ---------- 工具实现 ----------

def _list_menu(ctx: ToolContext, args: Dict[str, Any]) -> str:
    params = {"page": args.get("page", 1), "size": args.get("size", 30)}
    for source, target in (("category", "category"), ("keyword", "keyword"), ("available_only", "availableOnly")):
        if args.get(source) is not None:
            params[target] = args[source]
    data = _client(ctx).get("/dish/list", token=ctx.jwt_token, params=params) or {}
    items = data.get("items", []) if isinstance(data, dict) else data
    if not items:
        return "当前菜单为空。"
    lines = [f"【菜单 第{data.get('page', 1) if isinstance(data, dict) else 1}页】"]
    for d in items:
        stock = d.get("stock")
        sold_out = d.get("status") == 0 or (stock is not None and int(stock) <= 0)
        base = f"· {d.get('name')} {_money(d.get('price'))}（{d.get('category')}）"
        allergen_note = f"；过敏原：{d.get('allergens')}" if d.get("allergens") else ""
        if sold_out:
            lines.append(base + allergen_note + " - ⚠️ 已售罄/下架，不可下单")
        else:
            lines.append(base + (f" - {d.get('description')}" if d.get("description") else "") + allergen_note)
    return "\n".join(lines)


def _get_food_preferences(ctx: ToolContext, args: Dict[str, Any]) -> str:
    data = _client(ctx).get("/user/preferences", token=ctx.jwt_token) or {}
    return ("用户已保存的饮食偏好："
            f"过敏原={data.get('allergens') or '未设置'}；"
            f"不喜欢的食材={data.get('dislikes') or '未设置'}；"
            f"饮食目标={data.get('dietaryGoal') or '未设置'}；"
            f"单餐预算={_money(data.get('budget')) if data.get('budget') is not None else '未设置'}。")


def _update_food_preferences(ctx: ToolContext, args: Dict[str, Any]) -> str:
    # 增量合并，避免用户只更新一个字段时意外清空其他已保存偏好。
    current = _client(ctx).get("/user/preferences", token=ctx.jwt_token) or {}
    field_names = ("allergens", "dislikes", "dietaryGoal", "budget")
    body = {name: args[name] if name in args else current.get(name) for name in field_names}
    data = _client(ctx).put("/user/preferences", token=ctx.jwt_token, json=body) or {}
    return ("饮食偏好已保存："
            f"过敏原={data.get('allergens') or '未设置'}；"
            f"不喜欢的食材={data.get('dislikes') or '未设置'}；"
            f"饮食目标={data.get('dietaryGoal') or '未设置'}；"
            f"单餐预算={_money(data.get('budget')) if data.get('budget') is not None else '未设置'}。")


def _create_order_draft(ctx: ToolContext, args: Dict[str, Any]) -> str:
    items = args.get("items") or []
    if not items:
        return "错误：下单请求里没有菜品。请先用 list_menu 让用户选择菜品再下单。"
    body = {"items": items, "remark": args.get("remark")}
    data = _client(ctx).post("/order/drafts", token=ctx.jwt_token, json=body)
    total = _money(data.get("totalAmount"))
    names = "、".join(f"{i.get('dishName')}x{i.get('quantity')}" for i in (data.get("items") or []))
    ctx.pending_confirmation = {"draftId": data.get("id"), "items": data.get("items") or [], "totalAmount": data.get("totalAmount")}
    return f"已生成确认单：{names}，合计 {total}。请提示用户点击页面上的“确认下单”按钮；不得声称已下单。"


def _get_current_order_draft(ctx: ToolContext, args: Dict[str, Any]) -> str:
    drafts = _client(ctx).get("/order/drafts/pending", token=ctx.jwt_token) or []
    if not drafts:
        return "当前没有待确认购物车。"
    data = drafts[0]
    ctx.pending_confirmation = _draft_meta(data)
    names = "、".join(f"{i.get('dishName')}x{i.get('quantity')}" for i in (data.get("items") or []))
    return (f"当前购物车 draft_id={data.get('id')}：{names}，合计 {_money(data.get('totalAmount'))}。"
            "如需修改，必须把修改后的完整菜品列表传给 update_order_draft。")


def _update_order_draft(ctx: ToolContext, args: Dict[str, Any]) -> str:
    draft_id = str(args.get("draft_id") or "").strip()
    items = args.get("items") or []
    if not draft_id or not items:
        return "错误：修改购物车需要 draft_id 和修改后的完整菜品列表。"
    data = _client(ctx).put(
        f"/order/drafts/{draft_id}", token=ctx.jwt_token,
        json={"items": items, "remark": args.get("remark")},
    )
    ctx.pending_confirmation = _draft_meta(data)
    names = "、".join(f"{i.get('dishName')}x{i.get('quantity')}" for i in (data.get("items") or []))
    return (f"购物车已更新：{names}，合计 {_money(data.get('totalAmount'))}。"
            "请提示用户检查最新内容并点击页面上的“确认下单”按钮。")


def _cancel_order_draft(ctx: ToolContext, args: Dict[str, Any]) -> str:
    draft_id = str(args.get("draft_id") or "").strip()
    if not draft_id:
        return "错误：放弃购物车需要 draft_id；请先调用 get_current_order_draft。"
    _client(ctx).delete(f"/order/drafts/{draft_id}", token=ctx.jwt_token)
    ctx.pending_confirmation = {"draftId": draft_id, "status": "cancelled"}
    return "当前待确认购物车已放弃，不会创建订单。"


def _draft_meta(data: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "draftId": data.get("id"), "items": data.get("items") or [],
        "totalAmount": data.get("totalAmount"), "remark": data.get("remark"),
        "expiresAt": data.get("expiresAt"), "status": "pending",
    }


def _query_orders(ctx: ToolContext, args: Dict[str, Any]) -> str:
    params = {}
    if args.get("status") is not None:
        params["status"] = int(args["status"])
    if args.get("start_date"):
        params["startDate"] = args["start_date"]
    if args.get("end_date"):
        params["endDate"] = args["end_date"]
    data = _client(ctx).get("/order/list", token=ctx.jwt_token, params=params) or {}
    orders = data.get("items") if isinstance(data, dict) else []
    if not orders:
        return "该条件下没有订单。"
    lines = [_fmt_order(o) for o in orders]
    total = int(data.get("total", len(orders))) if isinstance(data, dict) else len(orders)
    suffix = ""
    if total > len(orders):
        suffix = f"\n共 {total} 笔，当前仅展示最近 {len(orders)} 笔；如需更早订单，请提供日期范围。"
    return "订单列表：\n" + "\n".join(lines) + suffix


def _get_order_detail(ctx: ToolContext, args: Dict[str, Any]) -> str:
    order_id = int(args["order_id"])
    data = _client(ctx).get(f"/order/{order_id}", token=ctx.jwt_token)
    lines = [_fmt_order(data)]
    lines.append("明细：")
    for i in (data.get("items") or []):
        lines.append(f"  · {i.get('dishName')} x{i.get('quantity')} {_money(i.get('amount'))}")
    if data.get("remark"):
        lines.append(f"备注：{data.get('remark')}")
    return "\n".join(lines)


def _cancel_order(ctx: ToolContext, args: Dict[str, Any]) -> str:
    order_id = int(args["order_id"])
    data = _client(ctx).post(f"/order/{order_id}/cancel", token=ctx.jwt_token)
    return (f"订单 #{order_id} 已成功取消。"
            "当前系统未接入真实支付，不得向用户承诺退款、原路退回或到账时间。")


def _remind_order(ctx: ToolContext, args: Dict[str, Any]) -> str:
    order_id = int(args["order_id"])
    data = _client(ctx).post(f"/order/{order_id}/remind", token=ctx.jwt_token)
    count = data.get("remindCount")
    return (f"已记录订单 #{order_id} 的催单（第 {count} 次）。"
            "当前演示系统只记录催单次数，不会向真实商家发送消息。")


def _search_faq(ctx: ToolContext, args: Dict[str, Any]) -> str:
    question = str(args.get("question", "")).strip()
    started = time.perf_counter()
    try:
        hits = search_faq(question, settings.faq_threshold)
    finally:
        ctx.record_stage_timing("faq_retrieval", (time.perf_counter() - started) * 1000)
    if not hits:
        return "知识库中没有找到匹配的常见问题。"
    for item, _score in hits[:2]:
        ctx.citations.append({"title": item["title"], "content": item["answer"]})
    parts = [f"【{item['title']}】\n{item['answer']}" for item, _score in hits[:2]]
    return "以下为知识库相关内容（可据此回答用户）：\n" + "\n\n".join(parts)


# ---------- 工具注册表 ----------

TOOL_SCHEMAS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "get_food_preferences",
            "description": "读取用户主动保存的过敏原、忌口、饮食目标和单餐预算。做个性化推荐前必须调用；用户询问已保存偏好时也调用。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_food_preferences",
            "description": "增量保存饮食偏好。只有用户明确说“记住/保存/设置/修改/清除我的偏好”时才能调用；不得从普通点餐或闲聊中静默推断并保存。空字符串表示清除相应文本字段。",
            "parameters": {
                "type": "object",
                "properties": {
                    "allergens": {"type": "string", "description": "逗号分隔的过敏原，如 花生,鸡蛋"},
                    "dislikes": {"type": "string", "description": "逗号分隔的不喜欢食材"},
                    "dietaryGoal": {"type": "string", "description": "饮食目标，如 减脂、增肌、清淡"},
                    "budget": {"type": "number", "description": "单餐预算（元），需大于0且不超过9999"},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_menu",
            "description": "分页查看菜单。下单前应调用本工具确认菜品在菜单里；用户要推荐时也先调用本工具。",
            "parameters": {"type": "object", "properties": {
                "category": {"type": "string", "maxLength": 50},
                "keyword": {"type": "string", "maxLength": 100},
                "available_only": {"type": "boolean"},
                "page": {"type": "integer", "minimum": 1},
                "size": {"type": "integer", "minimum": 1, "maximum": 50}
            }, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_current_order_draft",
            "description": "读取用户当前唯一的待确认购物车。用户说再加、删除、改数量、改备注、查看购物车或放弃时，必须先调用本工具取得 draft_id 和完整菜品列表。",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_order_draft",
            "description": "修改当前待确认购物车。items 必须是修改后的完整菜品列表，不是增量列表；调用前先用 get_current_order_draft 取得 draft_id 和现有菜品。",
            "parameters": {
                "type": "object",
                "properties": {
                    "draft_id": {"type": "string", "description": "get_current_order_draft 返回的草稿ID"},
                    "items": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "dishName": {"type": "string"},
                                "quantity": {"type": "integer"},
                            },
                            "required": ["dishName", "quantity"],
                        },
                    },
                    "remark": {"type": "string", "description": "修改后的整单备注，可选"},
                },
                "required": ["draft_id", "items"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "cancel_order_draft",
            "description": "放弃当前待确认购物车，不会取消已经创建的真实订单。调用前先用 get_current_order_draft 取得 draft_id。",
            "parameters": {
                "type": "object",
                "properties": {"draft_id": {"type": "string"}},
                "required": ["draft_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_order_draft",
            "description": "生成待用户确认的订单草稿。调用后必须让用户在页面点击确认按钮；此工具不会下单。",
            "parameters": {
                "type": "object",
                "properties": {
                    "items": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "dishId": {"type": "integer", "description": "菜品 id，可选"},
                                "dishName": {"type": "string", "description": "菜名（来自菜单）"},
                                "quantity": {"type": "integer", "description": "数量，默认1"},
                            },
                        },
                    },
                    "remark": {"type": "string", "description": "备注，可选"},
                },
                "required": ["items"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "query_orders",
            "description": "查询订单列表。可按状态(status: 1已下单 2制作中 3配送中 4已送达 5已取消 6已超时)和日期范围(start_date/end_date，格式 yyyy-MM-dd，如查今天/昨天的订单)筛选。",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {"type": "integer", "description": "订单状态，可选"},
                    "start_date": {"type": "string", "description": "开始日期 yyyy-MM-dd，可选"},
                    "end_date": {"type": "string", "description": "结束日期 yyyy-MM-dd，可选"},
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_order_detail",
            "description": "查询某笔订单的详情（含菜品明细、备注）。order_id 为用户看到的订单号（每个用户从 1 开始，如他的第 2 单就是 2）。",
            "parameters": {
                "type": "object",
                "properties": {"order_id": {"type": "integer", "description": "用户自己的订单号（从1开始）"}},
                "required": ["order_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "cancel_order",
            "description": "取消某笔订单。order_id 为用户看到的订单号（每个用户从 1 开始）。仅未结束的订单可取消。",
            "parameters": {
                "type": "object",
                "properties": {"order_id": {"type": "integer", "description": "用户自己的订单号（从1开始）"}},
                "required": ["order_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "remind_order",
            "description": "记录某笔订单的一次催单。当前演示系统不会向真实商家发送通知；order_id 为用户看到的订单号（每个用户从 1 开始）。",
            "parameters": {
                "type": "object",
                "properties": {"order_id": {"type": "integer", "description": "用户自己的订单号（从1开始）"}},
                "required": ["order_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_faq",
            "description": "检索常见问题知识库（退款/配送/催单/取消等）。用户在问此类问题时调用。",
            "parameters": {
                "type": "object",
                "properties": {"question": {"type": "string", "description": "用户的问题原文"}},
                "required": ["question"],
            },
        },
    },
]

_TOOL_HANDLERS: Dict[str, Callable[[ToolContext, Dict[str, Any]], str]] = {
    "get_food_preferences": _get_food_preferences,
    "update_food_preferences": _update_food_preferences,
    "list_menu": _list_menu,
    "create_order_draft": _create_order_draft,
    "get_current_order_draft": _get_current_order_draft,
    "update_order_draft": _update_order_draft,
    "cancel_order_draft": _cancel_order_draft,
    "query_orders": _query_orders,
    "get_order_detail": _get_order_detail,
    "cancel_order": _cancel_order,
    "remind_order": _remind_order,
    "search_faq": _search_faq,
}


def execute_tool(ctx: ToolContext, name: str, args_json: str) -> Dict[str, Any]:
    """执行工具并返回结构化结果；参数错误绝不触发下游业务 API。"""
    handler = _TOOL_HANDLERS.get(name)
    if handler is None:
        return {"ok": False, "error": {"code": "UNKNOWN_TOOL", "message": f"未知工具 {name}"}}
    started = time.perf_counter()
    try:
        raw_args = json.loads(args_json) if args_json else {}
        if not isinstance(raw_args, dict):
            return {"ok": False, "error": {"code": "INVALID_ARGUMENT", "message": "工具参数必须是对象"}}
        args = _ARG_MODELS[name].model_validate(raw_args).model_dump(exclude_none=True)
        return {"ok": True, "data": handler(ctx, args)}
    except json.JSONDecodeError:
        return {"ok": False, "error": {"code": "INVALID_JSON", "message": "工具参数不是有效 JSON"}}
    except ValidationError as e:
        return {"ok": False, "error": {"code": "VALIDATION_ERROR", "message": e.errors()[0]["msg"]}}
    except JavaApiError as e:
        return {"ok": False, "error": {"code": "BACKEND_ERROR", "category": e.category, "message": e.msg}}
    except Exception as e:  # 兜底，不回传堆栈
        return {"ok": False, "error": {"code": "TOOL_ERROR", "message": f"工具执行出错：{type(e).__name__}"}}
    finally:
        ctx.record_stage_timing(f"tool:{name}", (time.perf_counter() - started) * 1000)
