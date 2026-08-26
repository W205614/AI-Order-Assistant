"""LLM 客户端：OpenAI 兼容 API，支持 Function Calling。"""
from __future__ import annotations

from typing import Any, Dict, List

from openai import OpenAI

from ..config import settings
from ..gateway.java_client import JavaApiError


class LLMError(Exception):
    """LLM 调用失败。"""


def _client() -> OpenAI:
    if not settings.llm_api_key:
        raise LLMError("未配置 LLM_API_KEY，请复制 .env.example 为 .env 并填写")
    return OpenAI(base_url=settings.llm_base_url, api_key=settings.llm_api_key)


def chat_with_tools(
    messages: List[Dict[str, Any]],
    tools: List[Dict[str, Any]],
) -> Any:
    """调用 LLM，返回 chat.completions 的 message 对象。"""
    try:
        client = _client()
        resp = client.chat.completions.create(
            model=settings.llm_model,
            messages=messages,
            tools=tools,
            tool_choice="auto",
            temperature=settings.llm_temperature,
        )
    except LLMError:
        raise
    except Exception as e:  # openai 网络/鉴权错误
        raise LLMError(f"LLM 调用失败: {e}")

    if not resp.choices:
        raise LLMError("LLM 返回为空")
    return resp.choices[0].message


def is_available() -> bool:
    return bool(settings.llm_api_key)
