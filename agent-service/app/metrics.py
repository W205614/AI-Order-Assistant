"""轻量对话指标：每次 /chat 记录一行 JSONL，提供聚合统计（/stats 用）。

指标：对话轮数（历史+当轮）、工具调用数/成功数、响应延迟、是否成功。
文件位置：agent-service/metrics/chat_log.jsonl（已 gitignore）。
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Dict

_LOG_DIR = Path(__file__).resolve().parent.parent / "metrics"
_LOG_FILE = _LOG_DIR / "chat_log.jsonl"


def record(entry: Dict) -> None:
    _LOG_DIR.mkdir(exist_ok=True)
    with open(_LOG_FILE, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def stats() -> Dict:
    """读取 JSONL 并聚合：对话次数/总轮数/工具调用/成功率/延迟。"""
    if not _LOG_FILE.exists():
        return _empty()

    total_chats = 0
    total_rounds = 0
    tool_calls = 0
    tool_ok = 0
    latencies = []
    successes = 0

    with open(_LOG_FILE, encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            total_chats += 1
            total_rounds += int(e.get("rounds", 1))
            tc = int(e.get("toolCalls", 0))
            tool_calls += tc
            tool_ok += int(e.get("toolOk", 0))
            lat = e.get("latencyMs")
            if lat is not None:
                latencies.append(float(lat))
            if e.get("success"):
                successes += 1

    return {
        "totalChats": total_chats,
        "totalRounds": total_rounds,
        "avgRounds": round(total_rounds / max(total_chats, 1), 1),
        "toolCalls": tool_calls,
        "toolSuccessRate": round(tool_ok / max(tool_calls, 1) * 100, 1),
        "latencyAvgMs": round(sum(latencies) / max(len(latencies), 1), 1) if latencies else None,
        "latencyMaxMs": round(max(latencies), 1) if latencies else None,
        "successRate": round(successes / max(total_chats, 1) * 100, 1),
    }


def _empty() -> Dict:
    return {
        "totalChats": 0, "totalRounds": 0, "avgRounds": 0,
        "toolCalls": 0, "toolSuccessRate": 0,
        "latencyAvgMs": None, "latencyMaxMs": None, "successRate": 0,
    }
