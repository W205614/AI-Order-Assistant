"""LangGraph 编排：LLM 决策 → 工具执行 → 循环 → 回复。"""
from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict, List

from langgraph.graph import END, START, StateGraph

from ..config import settings
from . import prompts
from .llm import LLMError, chat_with_tools
from .state import AgentState
from .tools import TOOL_SCHEMAS, ToolContext, execute_tool


# 这些工具不会修改订单草稿、偏好或引用上下文，且彼此没有执行顺序要求。
# 仅当同一轮全部为此集合时并发，避免把读写混合调用变成竞态条件。
_PARALLEL_SAFE_READ_TOOLS = frozenset({
    "get_food_preferences",
    "list_menu",
    "query_orders",
    "get_order_detail",
})


def _build_messages(state: AgentState) -> List[Dict[str, Any]]:
    messages: List[Dict[str, Any]] = []
    # 最近 10 轮历史
    for h in (state.get("history") or [])[-10:]:
        if h.get("role") in ("user", "assistant") and h.get("content"):
            messages.append({"role": h["role"], "content": h["content"]})
    messages.append({"role": "user", "content": state["user_message"]})
    return messages


def agent_node(state: AgentState) -> Dict[str, Any]:
    """LLM 决策：返回回复或工具调用。"""
    messages = state.get("messages") or _build_messages(state)
    selected_menu_context = state.get("selectedMenuContext")
    system_messages: List[Dict[str, str]] = [{"role": "system", "content": prompts.system_prompt()}]
    if selected_menu_context:
        system_messages.append({"role": "system", "content": selected_menu_context})
    try:
        msg = chat_with_tools(
            system_messages + messages,
            [] if selected_menu_context else TOOL_SCHEMAS,
        )
    except LLMError as e:
        return {
            "reply": "AI 服务暂时不可用，请稍后重试。",
            "messages": messages,
            "pending_tool_calls": [],
            "errorCategory": e.category,
        }

    assistant_msg: Dict[str, Any] = {"role": "assistant", "content": msg.content or ""}
    # 菜单多选的草稿已由确定性节点创建，本轮禁止模型再次发起订单工具调用。
    tool_calls = [] if selected_menu_context else (getattr(msg, "tool_calls", None) or [])
    pending: List[Dict[str, str]] = []
    if tool_calls:
        llm_tool_calls = []
        for tc in tool_calls:
            fn = tc.function
            pending.append({"id": tc.id, "name": fn.name, "arguments": fn.arguments})
            llm_tool_calls.append({
                "id": tc.id, "type": "function",
                "function": {"name": fn.name, "arguments": fn.arguments},
            })
        assistant_msg["tool_calls"] = llm_tool_calls
    messages.append(assistant_msg)

    return {
        "messages": messages,
        "pending_tool_calls": pending,
        "reply": msg.content or "",
        "iterations": state.get("iterations", 0) + 1,
    }


def selected_menu_node(state: AgentState) -> Dict[str, Any]:
    """菜单多选的确定性交易节点：创建草稿，再交给 LLM 生成自然语言反馈。"""
    selected_items = state.get("selectedItems") or []
    if not selected_items:
        return {"selectedMenuFailed": False}

    ctx = ToolContext(jwt_token=state.get("jwtToken") or "", request_id=state.get("requestId") or "")
    result = execute_tool(ctx, "create_order_draft", json.dumps({"items": selected_items}, ensure_ascii=False))
    tool_calls_done: List[Dict[str, str]] = list(state.get("toolCalls") or [])
    tool_calls_done.append({"tool": "create_order_draft", "status": "ok" if result["ok"] else "error"})
    if not result["ok"] or not ctx.pending_confirmation:
        error = result.get("error") or {}
        message = error.get("message") if isinstance(error, dict) else None
        return {
            "reply": message or "无法生成确认单，请检查菜品后重试。",
            "toolCalls": tool_calls_done,
            "selectedMenuFailed": True,
            "pendingConfirmation": None,
        }

    return {
        "toolCalls": tool_calls_done,
        "pendingConfirmation": ctx.pending_confirmation,
        "selectedMenuFailed": False,
        "selectedMenuContext": (
            "系统已根据用户在菜单面板中明确勾选的菜品创建了待确认购物车。"
            f"草稿摘要：{result.get('data', '')}。"
            "请用简洁友好的中文说明已生成确认单，可提示用户核对金额、过敏原和数量后点击页面“确认下单”。"
            "不要再次创建、修改、取消草稿，不要声称已经下单；本轮不需要调用任何工具。"
        ),
    }


def tools_node(state: AgentState) -> Dict[str, Any]:
    """执行待处理工具调用，把结果回填到消息序列。"""
    ctx = ToolContext(jwt_token=state.get("jwtToken") or "", request_id=state.get("requestId") or "")
    messages = list(state.get("messages") or [])
    tool_calls_done: List[Dict[str, str]] = list(state.get("toolCalls") or [])

    calls = state.get("pending_tool_calls", [])
    results = _execute_tool_calls(ctx, calls)
    for call, result in zip(calls, results):
        messages.append({
            "role": "tool",
            "tool_call_id": call["id"],
            "content": json.dumps(result, ensure_ascii=False),
        })
        tool_event = {"tool": call["name"], "status": "ok" if result["ok"] else "error"}
        if not result["ok"]:
            error = result.get("error") or {}
            tool_event["errorCategory"] = error.get("category") or error.get("code") or "tool_error"
        tool_calls_done.append(tool_event)

    citations: List[Dict[str, str]] = list(state.get("citations") or [])
    citations.extend(ctx.citations)

    return {
        "messages": messages,
        "pending_tool_calls": [],
        "toolCalls": tool_calls_done,
        "citations": citations,
        "pendingConfirmation": ctx.pending_confirmation,
    }


def _execute_tool_calls(ctx: ToolContext, calls: List[Dict[str, str]]) -> List[Dict[str, Any]]:
    """并发无副作用的查询工具；其它调用保持原有的顺序语义。"""
    if len(calls) > 1 and all(call["name"] in _PARALLEL_SAFE_READ_TOOLS for call in calls):
        with ThreadPoolExecutor(max_workers=min(len(calls), 4), thread_name_prefix="agent-tool") as executor:
            return list(executor.map(lambda call: execute_tool(ctx, call["name"], call["arguments"]), calls))
    return [execute_tool(ctx, call["name"], call["arguments"]) for call in calls]


def should_continue(state: AgentState) -> str:
    if state.get("pending_tool_calls") and state.get("iterations", 0) < settings.max_iterations:
        return "tools"
    return "end"


def should_run_agent(state: AgentState) -> str:
    return "end" if state.get("selectedMenuFailed") else "agent"


def build_graph():
    builder = StateGraph(AgentState)
    builder.add_node("selected_menu", selected_menu_node)
    builder.add_node("agent", agent_node)
    builder.add_node("tools", tools_node)
    builder.add_edge(START, "selected_menu")
    builder.add_conditional_edges(
        "selected_menu", should_run_agent, {"agent": "agent", "end": END}
    )
    builder.add_conditional_edges(
        "agent", should_continue, {"tools": "tools", "end": END}
    )
    builder.add_edge("tools", "agent")
    return builder.compile()


graph = build_graph()
