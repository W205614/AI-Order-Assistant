"""LangGraph State 定义。"""
from __future__ import annotations

from typing import Any, Dict, List, TypedDict


class AgentState(TypedDict):
    # 请求上下文
    userId: int
    jwtToken: str
    requestId: str
    user_message: str
    history: List[Dict[str, str]]          # 前端带来的历史
    # Agent 运行态
    messages: List[Dict[str, Any]]         # 传给 LLM 的消息序列（含工具往返）
    pending_tool_calls: List[Dict[str, str]]  # 待执行的工具调用
    reply: str                             # 最终回复
    citations: List[Dict[str, str]]
    toolCalls: List[Dict[str, str]]
    # create_order_draft 工具产生；由 /chat 回传给前端渲染显式确认按钮。
    pendingConfirmation: Dict[str, Any] | None
    iterations: int
