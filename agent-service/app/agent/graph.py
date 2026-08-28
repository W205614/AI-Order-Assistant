"""LangGraph 编排：LLM 决策 → 工具执行 → 循环 → 回复。"""
from __future__ import annotations

from typing import Any, Dict, List

from langgraph.graph import END, START, StateGraph

from ..config import settings
from . import prompts
from .llm import LLMError, chat_with_tools
from .state import AgentState
from .tools import TOOL_SCHEMAS, ToolContext, execute_tool


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
    try:
        msg = chat_with_tools(
            [{"role": "system", "content": prompts.system_prompt()}] + messages,
            TOOL_SCHEMAS,
        )
    except LLMError as e:
        return {"reply": str(e), "messages": messages, "pending_tool_calls": []}

    assistant_msg: Dict[str, Any] = {"role": "assistant", "content": msg.content or ""}
    tool_calls = getattr(msg, "tool_calls", None) or []
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


def tools_node(state: AgentState) -> Dict[str, Any]:
    """执行待处理工具调用，把结果回填到消息序列。"""
    ctx = ToolContext(jwt_token=state.get("jwtToken") or "", request_id=state.get("requestId") or "")
    messages = list(state.get("messages") or [])
    tool_calls_done: List[Dict[str, str]] = list(state.get("toolCalls") or [])

    for call in state.get("pending_tool_calls", []):
        result = execute_tool(ctx, call["name"], call["arguments"])
        is_error = result.startswith("后端调用失败") or result.startswith("工具执行出错") or result.startswith("错误：")
        messages.append({
            "role": "tool",
            "tool_call_id": call["id"],
            "content": result,
        })
        tool_calls_done.append({"tool": call["name"], "status": "error" if is_error else "ok"})

    citations: List[Dict[str, str]] = list(state.get("citations") or [])
    citations.extend(ctx.citations)

    return {
        "messages": messages,
        "pending_tool_calls": [],
        "toolCalls": tool_calls_done,
        "citations": citations,
        "pendingConfirmation": ctx.pending_confirmation,
    }


def should_continue(state: AgentState) -> str:
    if state.get("pending_tool_calls") and state.get("iterations", 0) < settings.max_iterations:
        return "tools"
    return "end"


def build_graph():
    builder = StateGraph(AgentState)
    builder.add_node("agent", agent_node)
    builder.add_node("tools", tools_node)
    builder.add_edge(START, "agent")
    builder.add_conditional_edges(
        "agent", should_continue, {"tools": "tools", "end": END}
    )
    builder.add_edge("tools", "agent")
    return builder.compile()


graph = build_graph()
