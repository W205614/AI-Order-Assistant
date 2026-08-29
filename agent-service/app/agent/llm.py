"""OpenAI-compatible LLM client with bounded, safe retries."""
from __future__ import annotations

import time
from threading import Lock
from typing import Any, Dict, List, Optional

from openai import OpenAI

from ..config import settings


class LLMError(Exception):
    """A safe, classified model failure suitable for metrics and user fallback."""

    def __init__(self, category: str):
        self.category = category
        super().__init__(category)


_OPENAI_CLIENT: OpenAI | None = None
_OPENAI_CLIENT_LOCK = Lock()


def _client() -> OpenAI:
    global _OPENAI_CLIENT
    if not settings.llm_api_key:
        raise LLMError("model_not_configured")
    if _OPENAI_CLIENT is None:
        with _OPENAI_CLIENT_LOCK:
            if _OPENAI_CLIENT is None:
                # SDK retries are disabled so all retry policy is explicit and testable here.
                _OPENAI_CLIENT = OpenAI(
                    base_url=settings.llm_base_url,
                    api_key=settings.llm_api_key,
                    timeout=settings.llm_timeout,
                    max_retries=0,
                )
    return _OPENAI_CLIENT


def close_llm_client() -> None:
    global _OPENAI_CLIENT
    with _OPENAI_CLIENT_LOCK:
        if _OPENAI_CLIENT is not None:
            _OPENAI_CLIENT.close()
            _OPENAI_CLIENT = None


def _failure_category(error: Exception) -> tuple[str, bool]:
    """Classify provider failures without retaining provider messages or payloads."""
    status = getattr(error, "status_code", None)
    if isinstance(error, TimeoutError):
        return "model_timeout", True
    if status == 429:
        return "model_rate_limited", True
    if isinstance(status, int) and status >= 500:
        return "model_server_error", True
    if isinstance(error, ConnectionError):
        return "model_connection_error", True
    return "model_request_error", False


def chat_with_tools(
    messages: List[Dict[str, Any]],
    tools: Optional[List[Dict[str, Any]]] = None,
) -> Any:
    """Call the model; only safe, read-only model calls receive bounded retries."""
    client = _client()
    request: Dict[str, Any] = {
        "model": settings.llm_model,
        "messages": messages,
        "temperature": settings.llm_temperature,
    }
    if tools:
        request["tools"] = tools
        request["tool_choice"] = "auto"

    last_category = "model_request_error"
    for attempt in range(settings.llm_max_retries + 1):
        try:
            response = client.chat.completions.create(**request)
            if not response.choices:
                raise LLMError("model_empty_response")
            return response.choices[0].message
        except LLMError:
            raise
        except Exception as error:
            last_category, retryable = _failure_category(error)
            if not retryable or attempt >= settings.llm_max_retries:
                raise LLMError(last_category) from None
            # Backoff applies before repeating only the provider request; tools are not retried.
            time.sleep(0.2 * (2 ** attempt))
    raise LLMError(last_category)


def is_available() -> bool:
    return bool(settings.llm_api_key)
