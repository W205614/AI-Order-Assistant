"""集中读取 .env 配置。"""
from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


@dataclass
class Settings:
    # Java 点餐后端地址（AI-Order-Assistant 网关 :9090）
    java_base_url: str = os.getenv("JAVA_BASE_URL", "http://localhost:9090")

    # LLM（OpenAI 兼容 API）
    llm_base_url: str = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1")
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_model: str = os.getenv("LLM_MODEL", "gpt-4o-mini")
    llm_temperature: float = float(os.getenv("LLM_TEMPERATURE", "0.3"))

    # Agent 行为
    max_iterations: int = int(os.getenv("AGENT_MAX_ITERATIONS", "5"))
    faq_threshold: float = float(os.getenv("FAQ_THRESHOLD", "0.35"))

    # 调用 Java 后端超时（秒）
    java_timeout: float = float(os.getenv("JAVA_TIMEOUT", "30"))

    # 仅允许 Java 网关调用 Agent；生产环境必须设置。
    internal_api_key: str = os.getenv("AGENT_INTERNAL_API_KEY", "")
    cors_origins: tuple[str, ...] = tuple(x.strip() for x in os.getenv("CORS_ORIGINS", "http://localhost:9090").split(",") if x.strip())
    rate_limit_per_minute: int = int(os.getenv("AGENT_RATE_LIMIT_PER_MINUTE", "30"))


settings = Settings()
