"""请求/响应 Pydantic 模型。"""
from __future__ import annotations

from typing import List
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(max_length=2000)


class SelectedMenuItem(BaseModel):
    """菜单面板传来的结构化选择；下单前仍会由工具层再次安全校验。"""
    model_config = {"extra": "forbid", "str_strip_whitespace": True}

    dishName: str = Field(min_length=1, max_length=100)
    quantity: int = Field(ge=1, le=99)


class ChatRequest(BaseModel):
    model_config = {"extra": "forbid"}
    userId: int = Field(gt=0, description="Java 认证用户")
    merchantId: int = Field(gt=0, description="Java 固定的商户")
    deadlineEpochMs: int = Field(gt=0, description="Java 签发的截止时间")
    jwtToken: str = Field(default="", description="用户 JWT，回调 Java 时携带")
    requestId: str = Field(default="", description="聊天请求幂等标识")
    message: str = Field(..., min_length=1, max_length=2000)
    history: List[ChatMessage] = Field(default_factory=list, max_length=10)
    selectedItems: List[SelectedMenuItem] = Field(default_factory=list, max_length=20)


class Citation(BaseModel):
    title: str
    content: str


class ToolCallInfo(BaseModel):
    tool: str
    status: str


class ExecutionEvent(BaseModel):
    """A fixed, UI-safe milestone; it never contains model reasoning or user data."""
    event: str


class ChatResponse(BaseModel):
    traceId: str
    reply: str
    outcome: str = "completed"
    citations: List[Citation] = Field(default_factory=list)
    toolCalls: List[ToolCallInfo] = Field(default_factory=list)
    executionEvents: List[ExecutionEvent] = Field(default_factory=list)
    pendingConfirmation: Optional[Dict[str, Any]] = None


class HealthResponse(BaseModel):
    status: str
