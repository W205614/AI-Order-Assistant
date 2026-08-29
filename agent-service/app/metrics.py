"""Redacted Agent audit events and rolling aggregate metrics.

Entries contain operational metadata only. Raw prompts, replies, JWTs,
passwords, order contents and user identifiers must never be recorded.
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path
from typing import Any, Dict

_LOG_DIR = Path(__file__).resolve().parent.parent / "metrics"
_LOG_FILE = _LOG_DIR / "chat_log.jsonl"
_BACKUP_FILE = _LOG_DIR / "chat_log.1.jsonl"
_MAX_BYTES = max(64 * 1024, int(os.getenv("METRICS_MAX_BYTES", str(5 * 1024 * 1024))))
_LOCK = threading.RLock()
_ALLOWED_KEYS = frozenset({
    "traceId", "model", "rounds", "graphIterations", "toolCalls", "toolOk",
    "toolEvents", "latencyMs", "success", "errorCategory",
})


def _redact(entry: Dict[str, Any]) -> Dict[str, Any]:
    """Accept only whitelisted scalar and tool-status metadata."""
    safe = {key: value for key, value in entry.items() if key in _ALLOWED_KEYS}
    tool_events = safe.get("toolEvents")
    if isinstance(tool_events, list):
        safe["toolEvents"] = [
            {
                "tool": str(item.get("tool", ""))[:80],
                "status": str(item.get("status", ""))[:16],
                **({"errorCategory": str(item.get("errorCategory"))[:80]} if item.get("errorCategory") else {}),
            }
            for item in tool_events if isinstance(item, dict)
        ]
    return safe


def record(entry: Dict[str, Any]) -> None:
    line = json.dumps(_redact(entry), ensure_ascii=False, separators=(",", ":")) + "\n"
    with _LOCK:
        _LOG_DIR.mkdir(exist_ok=True)
        if _LOG_FILE.exists() and _LOG_FILE.stat().st_size + len(line.encode("utf-8")) > _MAX_BYTES:
            _BACKUP_FILE.unlink(missing_ok=True)
            _LOG_FILE.replace(_BACKUP_FILE)
        with _LOG_FILE.open("a", encoding="utf-8") as output:
            output.write(line)


def _percentile(values: list[float], percentile: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, -(-len(ordered) * percentile // 100) - 1))
    return round(ordered[index], 1)


def stats() -> Dict[str, Any]:
    """Aggregate current and rotated audit files, ignoring corrupt lines."""
    total_chats = total_rounds = tool_calls = tool_ok = successes = 0
    latencies: list[float] = []
    errors: dict[str, int] = {}
    with _LOCK:
        paths = [path for path in (_BACKUP_FILE, _LOG_FILE) if path.exists()]
        for path in paths:
            with path.open(encoding="utf-8") as source:
                for line in source:
                    try:
                        event = json.loads(line)
                        rounds = int(event.get("rounds", 1))
                        calls = int(event.get("toolCalls", 0))
                        ok = int(event.get("toolOk", 0))
                        latency = event.get("latencyMs")
                        latency = float(latency) if latency is not None else None
                    except (json.JSONDecodeError, AttributeError, TypeError, ValueError):
                        continue
                    total_chats += 1
                    total_rounds += rounds
                    tool_calls += calls
                    tool_ok += ok
                    if latency is not None:
                        latencies.append(latency)
                    if event.get("success"):
                        successes += 1
                    category = event.get("errorCategory")
                    if category:
                        errors[str(category)] = errors.get(str(category), 0) + 1
                    for tool_event in event.get("toolEvents") or []:
                        tool_category = tool_event.get("errorCategory") if isinstance(tool_event, dict) else None
                        if tool_category:
                            errors[str(tool_category)] = errors.get(str(tool_category), 0) + 1
    return {
        "totalChats": total_chats,
        "totalRounds": total_rounds,
        "avgRounds": round(total_rounds / max(total_chats, 1), 1),
        "toolCalls": tool_calls,
        "toolSuccessRate": round(tool_ok / max(tool_calls, 1) * 100, 1),
        "latencyP50Ms": _percentile(latencies, 50),
        "latencyP95Ms": _percentile(latencies, 95),
        "latencyMaxMs": round(max(latencies), 1) if latencies else None,
        "successRate": round(successes / max(total_chats, 1) * 100, 1),
        "errorsByCategory": errors,
    }
