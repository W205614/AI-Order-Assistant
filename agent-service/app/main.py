"""FastAPI 入口：/health、/chat、/stats。"""
from __future__ import annotations

import logging
import secrets
import threading
import time
from collections import deque
from typing import List

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import redis

from .config import settings
from .agent.graph import graph
from .agent.llm import close_llm_client, is_available
from .gateway.java_client import close_http_client
from .metrics import record as metrics_record, stats as metrics_stats
from .rag.faq_router import match_static_faq
from .schemas import (
    ChatRequest,
    ChatResponse,
    Citation,
    ExecutionEvent,
    HealthResponse,
    ToolCallInfo,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="AI-Order-Assistant Agent", version="2.0.0")


@app.on_event("shutdown")
def close_outbound_connections() -> None:
    """关闭 Agent 进程内复用的 HTTP 连接池。"""
    close_http_client()
    close_llm_client()

# 允许网关同源/开发调试跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.cors_origins),
    allow_methods=["*"],
    allow_headers=["*"],
)

_rate_windows: dict[str, deque[float]] = {}
_rate_lock = threading.Lock()
_rate_checks = 0
_redis_client: redis.Redis | None = None
_redis_client_url = ""

_REDIS_SLIDING_WINDOW = """
local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local window_ms = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms - window_ms)
if redis.call('ZCARD', KEYS[1]) >= limit then return 0 end
redis.call('ZADD', KEYS[1], now_ms, ARGV[3])
redis.call('PEXPIRE', KEYS[1], window_ms)
return 1
"""


class _RateLimitExceeded(Exception):
    pass


class _RateLimitBackendUnavailable(Exception):
    pass


def _get_redis_client() -> redis.Redis:
    global _redis_client, _redis_client_url
    if _redis_client is None or _redis_client_url != settings.redis_url:
        _redis_client = redis.Redis.from_url(
            settings.redis_url, decode_responses=True, socket_connect_timeout=1.0, socket_timeout=1.0,
        )
        _redis_client_url = settings.redis_url
    return _redis_client


def _allow_in_memory(key: str) -> None:
    now = time.monotonic()
    global _rate_checks
    with _rate_lock:
        window = _rate_windows.setdefault(key, deque())
        while window and window[0] <= now - 60:
            window.popleft()
        if len(window) >= settings.rate_limit_per_minute:
            raise _RateLimitExceeded()
        window.append(now)
        _rate_checks += 1
        # 周期性清理长期不活跃用户，避免用户标识集合无界增长。
        if _rate_checks % 100 == 0:
            cutoff = now - 60
            stale = [item_key for item_key, values in _rate_windows.items()
                     if not values or values[-1] <= cutoff]
            for item_key in stale:
                _rate_windows.pop(item_key, None)


def _allow_with_redis(key: str) -> None:
    try:
        allowed = _get_redis_client().eval(
            _REDIS_SLIDING_WINDOW, 1, key, 60_000, settings.rate_limit_per_minute, secrets.token_urlsafe(18),
        )
    except redis.RedisError as exc:
        raise _RateLimitBackendUnavailable() from exc
    if int(allowed) != 1:
        raise _RateLimitExceeded()


def _apply_rate_limit(user_id: str) -> None:
    key = f"{settings.rate_limit_key_prefix}:user:{user_id}"
    if settings.rate_limit_backend == "redis":
        _allow_with_redis(key)
    else:
        _allow_in_memory(key)


def _record_rate_limit_failure(category: str, request_id: str) -> None:
    metrics_record({
        "traceId": request_id, "model": settings.llm_model, "rounds": 0,
        "graphIterations": 0, "toolCalls": 0, "toolOk": 0, "toolEvents": [],
        "latencyMs": 0, "success": False, "errorCategory": category,
    })

def _verify_internal_request(internal_key: str | None, user_id: str | None, request_id: str = "") -> None:
    """验证网关身份，并按已认证用户而非网关 IP 限流。"""
    if not settings.internal_api_key:
        logger.error("AGENT_INTERNAL_API_KEY is not configured")
        raise HTTPException(status_code=503, detail="Agent 服务内部认证未配置")
    if internal_key is None or not secrets.compare_digest(internal_key, settings.internal_api_key):
        raise HTTPException(status_code=401, detail="Agent 服务认证失败")
    if user_id is None or not user_id.isdecimal() or int(user_id) <= 0:
        raise HTTPException(status_code=400, detail="缺少有效的网关用户标识")
    try:
        _apply_rate_limit(user_id)
    except _RateLimitExceeded:
        _record_rate_limit_failure("agent_rate_limited", request_id)
        raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
    except _RateLimitBackendUnavailable:
        logger.warning("Rate limit backend unavailable")
        _record_rate_limit_failure("rate_limit_backend_unavailable", request_id)
        raise HTTPException(status_code=503, detail="服务暂时不可用，请稍后重试")


def _verify_internal_access(internal_key: str | None) -> None:
    if not settings.internal_api_key:
        logger.error("AGENT_INTERNAL_API_KEY is not configured")
        raise HTTPException(status_code=503, detail="Agent 服务内部认证未配置")
    if internal_key is None or not secrets.compare_digest(internal_key, settings.internal_api_key):
        raise HTTPException(status_code=401, detail="Agent 服务认证失败")


def _try_static_faq_fast_path(req: ChatRequest) -> tuple[ChatResponse, List[dict[str, float | str]]] | None:
    """Serve only a safe, first-turn static FAQ without contacting the LLM."""
    if req.history or req.selectedItems:
        return None
    started = time.perf_counter()
    item = match_static_faq(req.message, settings.faq_fast_path_threshold)
    elapsed = round((time.perf_counter() - started) * 1000, 4)
    if item is None:
        return None
    timings: List[dict[str, float | str]] = [
        {"stage": "faq_retrieval", "latencyMs": elapsed},
        {"stage": "faq_fast_path", "latencyMs": elapsed},
    ]
    return ChatResponse(
        traceId=req.requestId,
        reply=str(item["answer"]),
        citations=[Citation(title=str(item["title"]), content=str(item["answer"]))],
        executionEvents=[ExecutionEvent(event="faq_fast_path")],
    ), timings


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
def stats(x_agent_internal_key: str | None = Header(default=None)):
    """对话指标聚合：对话次数/轮数/工具调用成功率/响应延迟。"""
    _verify_internal_access(x_agent_internal_key)
    return metrics_stats()


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest, x_agent_internal_key: str | None = Header(default=None),
         x_agent_user_id: str | None = Header(default=None)):
    _verify_internal_request(x_agent_internal_key, x_agent_user_id, req.requestId or "")
    if int(x_agent_user_id) != req.userId:
        raise HTTPException(status_code=401, detail="网关用户标识不匹配")
    if len(req.message) > 2000:
        raise HTTPException(status_code=400, detail="消息过长")

    rounds = len(req.history) + 1
    start = time.perf_counter()
    fast_path = _try_static_faq_fast_path(req)
    if fast_path is not None:
        response, stage_timings = fast_path
        metrics_record({
            "traceId": req.requestId, "model": settings.llm_model, "rounds": rounds,
            "graphIterations": 0, "toolCalls": 0, "toolOk": 0, "toolEvents": [],
            "routing": "faq_fast_path",
            "stageTimings": stage_timings,
            "latencyMs": round((time.perf_counter() - start) * 1000, 1),
            "success": True, "errorCategory": None,
        })
        return response

    if not is_available():
        raise HTTPException(status_code=500, detail="未配置 LLM_API_KEY，请复制 .env.example 为 .env 并填写后重启")

    state = {
        "userId": req.userId,
        "jwtToken": req.jwtToken or "",
        "requestId": req.requestId or "",
        "user_message": req.message,
        "history": [{"role": m.role, "content": m.content} for m in req.history],
        "selectedItems": [item.model_dump() for item in req.selectedItems],
        "messages": [],
        "pending_tool_calls": [],
        "reply": "",
        "citations": [],
        "toolCalls": [],
        "executionEvents": [],
        "pendingConfirmation": None,
        "selectedMenuContext": None,
        "selectedMenuFailed": False,
        "cartRouterHandled": False,
        "errorCategory": None,
        "iterations": 0,
        "stageTimings": [],
    }

    try:
        result = graph.invoke(state)
    except Exception as e:  # LangGraph 运行时异常，兜底
        logger.exception("Agent 执行失败")
        elapsed = round((time.perf_counter() - start) * 1000, 1)
        metrics_record({
            "traceId": req.requestId, "model": settings.llm_model, "rounds": rounds,
            "graphIterations": 0, "toolCalls": 0, "toolOk": 0, "toolEvents": [],
            "latencyMs": elapsed, "success": False, "errorCategory": "graph_execution_error",
        })
        raise HTTPException(status_code=500, detail="Agent 执行失败，请稍后重试")
    elapsed = round((time.perf_counter() - start) * 1000, 1)

    # 记录指标
    tc_list = result.get("toolCalls") or []
    error_category = result.get("errorCategory")
    metrics_record({
        "traceId": req.requestId, "model": settings.llm_model, "rounds": rounds,
        "graphIterations": result.get("iterations", 0),
        "toolCalls": len(tc_list),
        "toolOk": sum(1 for t in tc_list if t.get("status") == "ok"),
        "toolEvents": tc_list,
        "routing": "cart_router" if result.get("cartRouterHandled") else "agent",
        "stageTimings": result.get("stageTimings") or [],
        "latencyMs": elapsed,
        "success": error_category is None,
        "errorCategory": error_category,
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
    execution_events = [
        ExecutionEvent(event=str(event.get("event", "")))
        for event in (result.get("executionEvents") or [])
        if isinstance(event, dict) and event.get("event")
    ]

    return ChatResponse(traceId=req.requestId, reply=reply, citations=citations, toolCalls=tool_calls,
                        executionEvents=execution_events, pendingConfirmation=result.get("pendingConfirmation"))
