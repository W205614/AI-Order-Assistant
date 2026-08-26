"""FastAPI 入口：/health、/chat。"""
from __future__ import annotations

import logging
from typing import List

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from .agent.graph import graph
from .agent.llm import is_available
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
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


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


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    if not is_available():
        raise HTTPException(status_code=500, detail="未配置 LLM_API_KEY，请复制 .env.example 为 .env 并填写后重启")

    if len(req.message) > 2000:
        raise HTTPException(status_code=400, detail="消息过长")

    state = {
        "userId": req.userId,
        "jwtToken": req.jwtToken or "",
        "user_message": req.message,
        "history": [{"role": m.role, "content": m.content} for m in req.history],
        "messages": [],
        "pending_tool_calls": [],
        "reply": "",
        "citations": [],
        "toolCalls": [],
        "iterations": 0,
    }

    try:
        result = graph.invoke(state)
    except Exception as e:  # LangGraph 运行时异常，兜底
        logger.exception("Agent 执行失败")
        raise HTTPException(status_code=500, detail=f"Agent 执行失败: {e}")

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
        for t in (result.get("toolCalls") or [])
    ]

    return ChatResponse(reply=reply, citations=citations, toolCalls=tool_calls)
