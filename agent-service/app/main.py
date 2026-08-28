"""FastAPI 入口：/health、/chat、/stats。"""
from __future__ import annotations

import logging
import secrets
import time
from collections import defaultdict, deque
from typing import List

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from .config import settings
from .agent.graph import graph
from .agent.llm import is_available
from .metrics import record as metrics_record, stats as metrics_stats
from .schemas import (
    ChatRequest,
    ChatResponse,
    Citation,
    HealthResponse,
    ToolCallInfo,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="AI-Order-Assistant Agent", version="2.0.0")

# 允许网关同源/开发调试跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.cors_origins),
    allow_methods=["*"],
    allow_headers=["*"],
)

_rate_windows: dict[str, deque[float]] = defaultdict(deque)

def _verify_internal_request(internal_key: str | None, user_id: str | None) -> None:
    """验证网关身份，并按已认证用户而非网关 IP 限流。"""
    if not settings.internal_api_key:
        logger.error("AGENT_INTERNAL_API_KEY is not configured")
        raise HTTPException(status_code=503, detail="Agent 服务内部认证未配置")
    if internal_key is None or not secrets.compare_digest(internal_key, settings.internal_api_key):
        raise HTTPException(status_code=401, detail="Agent 服务认证失败")
    if user_id is None or not user_id.isdecimal() or int(user_id) <= 0:
        raise HTTPException(status_code=400, detail="缺少有效的网关用户标识")
    now = time.monotonic(); window = _rate_windows["user:" + user_id]
    while window and window[0] <= now - 60: window.popleft()
    if len(window) >= settings.rate_limit_per_minute:
        raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
    window.append(now)


@app.get("/health", response_model=HealthResponse)
def health():
    return HealthResponse(status="ok")


@app.get("/", include_in_schema=False)
def root():
    """Agent 服务根路径，返回基本信息（前端页面在 Java 端）。"""
    return {
        "service": "ai-order-assistant-agent",
        "status": "ok",
        "docs": "/docs",
        "chat_page": "http://localhost:9090/chat/",
    }


@app.get("/stats")
def stats():
    """对话指标聚合：对话次数/轮数/工具调用成功率/响应延迟。"""
    return metrics_stats()


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest, x_agent_internal_key: str | None = Header(default=None),
         x_agent_user_id: str | None = Header(default=None)):
    _verify_internal_request(x_agent_internal_key, x_agent_user_id)
    if int(x_agent_user_id) != req.userId:
        raise HTTPException(status_code=401, detail="网关用户标识不匹配")
    if not is_available():
        raise HTTPException(status_code=500, detail="未配置 LLM_API_KEY，请复制 .env.example 为 .env 并填写后重启")

    if len(req.message) > 2000:
        raise HTTPException(status_code=400, detail="消息过长")

    state = {
        "userId": req.userId,
        "jwtToken": req.jwtToken or "",
        "requestId": req.requestId or "",
        "user_message": req.message,
        "history": [{"role": m.role, "content": m.content} for m in req.history],
        "messages": [],
        "pending_tool_calls": [],
        "reply": "",
        "citations": [],
        "toolCalls": [],
        "pendingConfirmation": None,
        "iterations": 0,
    }

    rounds = len(req.history) + 1
    start = time.perf_counter()
    try:
        result = graph.invoke(state)
    except Exception as e:  # LangGraph 运行时异常，兜底
        logger.exception("Agent 执行失败")
        elapsed = round((time.perf_counter() - start) * 1000, 1)
        metrics_record({"rounds": rounds, "toolCalls": 0, "toolOk": 0, "latencyMs": elapsed, "success": False})
        raise HTTPException(status_code=500, detail=f"Agent 执行失败: {e}")
    elapsed = round((time.perf_counter() - start) * 1000, 1)

    # 记录指标
    tc_list = result.get("toolCalls") or []
    metrics_record({
        "rounds": rounds,
        "toolCalls": len(tc_list),
        "toolOk": sum(1 for t in tc_list if t.get("status") == "ok"),
        "latencyMs": elapsed,
        "success": True,
    })

    reply = result.get("reply") or "抱歉，我没有理解你的意思，换个说法试试？"
    # 若触发了工具但 LLM 最终没生成文字，则给兜底文案
    if not result.get("reply") and result.get("toolCalls"):
        reply = "已为你完成相关操作。"

    citations = [
        Citation(title=c.get("title", ""), content=c.get("content", ""))
        for c in (result.get("citations") or [])
    ]
    tool_calls = [
        ToolCallInfo(tool=t.get("tool", ""), status=t.get("status", ""))
        for t in tc_list
    ]

    return ChatResponse(reply=reply, citations=citations, toolCalls=tool_calls,
                        pendingConfirmation=result.get("pendingConfirmation"))
