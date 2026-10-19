"""Redacted Agent audit events and rolling aggregate metrics.

Entries contain operational metadata only. Raw prompts, replies, JWTs,
passwords, order contents and user identifiers must never be recorded.
"""
from __future__ import annotations

import json
import logging
from collections import deque
import math
import os
import queue
import re
import threading
from pathlib import Path
from typing import Any, Dict

_LOG_DIR = Path(__file__).resolve().parent.parent / "metrics"
_LOG_FILE = _LOG_DIR / "chat_log.jsonl"
_BACKUP_FILE = _LOG_DIR / "chat_log.1.jsonl"
_MAX_BYTES = max(64 * 1024, int(os.getenv("METRICS_MAX_BYTES", str(5 * 1024 * 1024))))
_LOCK = threading.RLock()
_EVENTS: deque[dict[str, Any]] = deque(maxlen=10000)
_TOTAL_CHATS = 0
_TOTAL_FAILURES = 0
_AUDIT_WRITE_FAILURES = 0
_AUDIT_QUEUE_DROPPED = 0
logger = logging.getLogger(__name__)
_ALLOWED_KEYS = frozenset({
    "traceId", "model", "rounds", "graphIterations", "toolCalls", "toolOk",
    "toolEvents", "stageTimings", "latencyMs", "success", "errorCategory", "routing",
    "outcome", "toolFailureCount", "inputTokens", "outputTokens", "modelCalls",
})
_STAGE_RE = re.compile(r"^(?:llm_decision|llm_answer|faq_retrieval|faq_fast_path|tool:[a-z_]{1,80})$")
_ROUTING = frozenset({"agent", "faq_fast_path", "cart_router", "campus"})
_OUTCOMES = frozenset({"completed", "degraded", "failed", "needs_input"})


def _safe_stage_timings(value: Any) -> list[dict[str, Any]]:
    """Retain fixed stage labels and finite durations only; payloads are discarded."""
    if not isinstance(value, list):
        return []
    safe: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        stage = str(item.get("stage", ""))
        try:
            latency = float(item.get("latencyMs"))
        except (TypeError, ValueError):
            continue
        if _STAGE_RE.fullmatch(stage) and math.isfinite(latency) and 0 <= latency <= 120_000:
            safe.append({"stage": stage, "latencyMs": round(latency, 4)})
    return safe


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
    safe["stageTimings"] = _safe_stage_timings(safe.get("stageTimings"))
    if safe.get("routing") not in _ROUTING:
        safe.pop("routing", None)
    if safe.get("outcome") not in _OUTCOMES:
        safe.pop("outcome", None)
    return safe


def _write_line(line: str) -> None:
    """Only the audit worker touches the filesystem, including rotation."""
    _LOG_DIR.mkdir(exist_ok=True)
    if _LOG_FILE.exists() and _LOG_FILE.stat().st_size + len(line.encode("utf-8")) > _MAX_BYTES:
        _BACKUP_FILE.unlink(missing_ok=True)
        _LOG_FILE.replace(_BACKUP_FILE)
    with _LOG_FILE.open("a", encoding="utf-8") as output:
        output.write(line)


class _AuditWriter:
    def __init__(self, capacity: int):
        self.pending: queue.Queue[str] = queue.Queue(maxsize=capacity)
        self._stop = threading.Event()
        self._lifecycle = threading.Lock()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        with self._lifecycle:
            if self._thread is None and not self._stop.is_set():
                self._thread = threading.Thread(target=self._run, name="agent-audit-writer", daemon=True)
                try:
                    self._thread.start()
                except RuntimeError:
                    self._thread = None
                    raise

    def submit(self, line: str) -> None:
        global _AUDIT_QUEUE_DROPPED
        try:
            self.start()
            with self._lifecycle:
                if self._stop.is_set():
                    raise queue.Full
                self.pending.put_nowait(line)
        except (queue.Full, RuntimeError):
            # Do not log synchronously here: stderr may share the same slow disk.
            with _LOCK:
                _AUDIT_QUEUE_DROPPED += 1

    def _run(self) -> None:
        global _AUDIT_WRITE_FAILURES
        while not self._stop.is_set() or not self.pending.empty():
            try:
                line = self.pending.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                _write_line(line)
            except OSError:
                with _LOCK:
                    _AUDIT_WRITE_FAILURES += 1
                logger.warning("Agent audit write unavailable; operational counters retained")
            finally:
                self.pending.task_done()

    def request_stop(self) -> None:
        with self._lifecycle:
            self._stop.set()

    def shutdown(self, timeout: float = 2.0) -> None:
        self.request_stop()
        if self._thread is not None:
            self._thread.join(timeout=max(0, min(timeout, 2.0)))


_WRITER = _AuditWriter(max(1, int(os.getenv("METRICS_QUEUE_CAPACITY", "1000"))))
_WRITER_LOCK = threading.Lock()


def start_writer() -> None:
    global _WRITER
    with _WRITER_LOCK:
        if _WRITER._stop.is_set():
            # A timed-out shutdown may still be blocked inside filesystem I/O.
            # Never run a second writer against the same rotating files.
            if _WRITER._thread is not None and _WRITER._thread.is_alive():
                return
            _WRITER = _AuditWriter(_WRITER.pending.maxsize)
        _WRITER.start()


def shutdown_writer() -> None:
    """Best effort drain; a blocked filesystem cannot hold shutdown indefinitely."""
    with _WRITER_LOCK:
        writer = _WRITER
        writer.request_stop()
    # Do not hold the process lifecycle lock while waiting on a slow disk.
    writer.shutdown()


def record(entry: Dict[str, Any]) -> None:
    """Aggregate now and enqueue an immutable redacted line without filesystem I/O."""
    global _TOTAL_CHATS, _TOTAL_FAILURES
    safe = _redact(entry)
    line = json.dumps(safe, ensure_ascii=False, separators=(",", ":")) + "\n"
    with _LOCK:
        _EVENTS.append(safe)
        _TOTAL_CHATS += 1
        _TOTAL_FAILURES += int(not bool(safe.get("success")))
    with _WRITER_LOCK:
        _WRITER.submit(line)


def _percentile(values: list[float], percentile: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, -(-len(ordered) * percentile // 100) - 1))
    return round(ordered[index], 1)


def stats() -> Dict[str, Any]:
    """Process-lifetime counters and a bounded recent-event window; never read disk."""
    total_chats = total_rounds = tool_calls = tool_ok = successes = tool_failures = 0
    latencies: list[float] = []
    stage_latencies: dict[str, list[float]] = {}
    errors: dict[str, int] = {}
    routing: dict[str, int] = {}
    outcomes: dict[str, int] = {}
    with _LOCK:
        events = list(_EVENTS)
        lifetime_total = _TOTAL_CHATS
        lifetime_failures = _TOTAL_FAILURES
        write_failures = _AUDIT_WRITE_FAILURES
        queue_dropped = _AUDIT_QUEUE_DROPPED
    for event in events:
        try:
            rounds = int(event.get("rounds", 1))
            calls = int(event.get("toolCalls", 0))
            ok = int(event.get("toolOk", 0))
            failures_count = int(event.get("toolFailureCount", max(calls - ok, 0)))
            latency = event.get("latencyMs")
            latency = float(latency) if latency is not None else None
        except (json.JSONDecodeError, AttributeError, TypeError, ValueError):
            continue
        total_chats += 1
        total_rounds += rounds
        tool_calls += calls
        tool_ok += ok
        tool_failures += failures_count
        if latency is not None:
            latencies.append(latency)
        if event.get("success"):
            successes += 1
        outcome = event.get("outcome")
        if outcome in _OUTCOMES:
            outcomes[outcome] = outcomes.get(outcome, 0) + 1
        route = event.get("routing")
        if route in _ROUTING:
            routing[route] = routing.get(route, 0) + 1
        category = event.get("errorCategory")
        if category:
            errors[str(category)] = errors.get(str(category), 0) + 1
        for tool_event in event.get("toolEvents") or []:
            tool_category = tool_event.get("errorCategory") if isinstance(tool_event, dict) else None
            if tool_category:
                errors[str(tool_category)] = errors.get(str(tool_category), 0) + 1
        for timing in _safe_stage_timings(event.get("stageTimings")):
            stage_latencies.setdefault(timing["stage"], []).append(float(timing["latencyMs"]))
    return {
        "totalChats": lifetime_total,
        "totalFailures": lifetime_failures,
        "sampledChats": total_chats,
        "auditWriteFailures": write_failures,
        "auditQueueDropped": queue_dropped,
        "totalRounds": total_rounds,
        "avgRounds": round(total_rounds / max(total_chats, 1), 1),
        "toolCalls": tool_calls,
        "toolSuccessRate": round(tool_ok / max(tool_calls, 1) * 100, 1),
        "toolFailureCount": tool_failures,
        "latencyP50Ms": _percentile(latencies, 50),
        "latencyP95Ms": _percentile(latencies, 95),
        "latencyMaxMs": round(max(latencies), 1) if latencies else None,
        "runtimeCompletionRate": round(successes / max(total_chats, 1) * 100, 1),
        "runtimeOutcomes": dict(sorted(outcomes.items())),
        "errorsByCategory": errors,
        "routingCounts": dict(sorted(routing.items())),
        "stageLatencyMs": {
            stage: {
                "count": len(values),
                "p50": _percentile(values, 50),
                "p95": _percentile(values, 95),
                "max": round(max(values), 1),
            }
            for stage, values in sorted(stage_latencies.items())
        },
    }
