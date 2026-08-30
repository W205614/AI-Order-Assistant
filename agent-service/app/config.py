"""集中读取 .env 配置。"""
from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


def _bounded_number(name: str, default: str, cast, minimum: float, maximum: float):
    raw = os.getenv(name, default)
    try:
        value = cast(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} 必须是有效数字，当前值为 {raw!r}") from exc
    if value < minimum or value > maximum:
        raise ValueError(f"{name} 必须在 {minimum}-{maximum} 之间，当前值为 {value}")
    return value


def _choice(name: str, default: str, allowed: set[str]) -> str:
    value = os.getenv(name, default).strip().lower()
    if value not in allowed:
        options = ", ".join(sorted(allowed))
        raise ValueError(f"{name} 必须是以下值之一：{options}")
    return value


@dataclass
class Settings:
    # Java 点餐后端地址（AI-Order-Assistant 网关 :9090）
    java_base_url: str = os.getenv("JAVA_BASE_URL", "http://localhost:9090")

    # LLM（OpenAI 兼容 API）
    llm_base_url: str = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1")
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_model: str = os.getenv("LLM_MODEL", "gpt-4o-mini")
    llm_temperature: float = _bounded_number("LLM_TEMPERATURE", "0.3", float, 0, 2)
    llm_timeout: float = _bounded_number("LLM_TIMEOUT_SECONDS", "45", float, 1, 120)
    llm_max_retries: int = _bounded_number("LLM_MAX_RETRIES", "1", int, 0, 3)

    # Agent 行为
    max_iterations: int = _bounded_number("AGENT_MAX_ITERATIONS", "5", int, 1, 10)
    faq_threshold: float = _bounded_number("FAQ_THRESHOLD", "0.35", float, 0, 1)

    # 调用 Java 后端超时（秒）
    java_timeout: float = _bounded_number("JAVA_TIMEOUT", "30", float, 1, 120)

    # 仅允许 Java 网关调用 Agent；生产环境必须设置。
    internal_api_key: str = os.getenv("AGENT_INTERNAL_API_KEY", "")
    cors_origins: tuple[str, ...] = tuple(x.strip() for x in os.getenv("CORS_ORIGINS", "http://localhost:9090").split(",") if x.strip())
    rate_limit_per_minute: int = _bounded_number("AGENT_RATE_LIMIT_PER_MINUTE", "30", int, 1, 10000)
    rate_limit_backend: str = _choice("RATE_LIMIT_BACKEND", "memory", {"memory", "redis"})
    redis_url: str = os.getenv("REDIS_URL", "").strip()
    rate_limit_key_prefix: str = os.getenv("AGENT_RATE_LIMIT_KEY_PREFIX", "ai-order-agent:rate").strip()

    def __post_init__(self) -> None:
        if self.rate_limit_backend == "redis" and not self.redis_url:
            raise ValueError("RATE_LIMIT_BACKEND=redis 时必须设置 REDIS_URL")
        if not self.rate_limit_key_prefix or len(self.rate_limit_key_prefix) > 120:
            raise ValueError("AGENT_RATE_LIMIT_KEY_PREFIX 不能为空且不能超过 120 个字符")


settings = Settings()
