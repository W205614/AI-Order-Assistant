"""Run repeatable, live LLM regression scenarios against the complete system.

The runner deliberately keeps model credentials out of CI. It writes a
redacted JSONL result per scenario: IDs, tool names, timings and assertion
outcomes are retained; prompts, tokens and user data are not.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx


BASE_URL = os.getenv("EVAL_BASE_URL", "http://localhost:9090").rstrip("/")
USERNAME = os.getenv("EVAL_USERNAME", "demo")
PASSWORD = os.getenv("EVAL_PASSWORD", "123456")
DEFAULT_RUNS = int(os.getenv("EVAL_RUNS", "1"))
DEFAULT_DELAY_SECONDS = float(os.getenv("EVAL_REQUEST_DELAY_SECONDS", "2.1"))
DEFAULT_MODEL_LABEL = os.getenv("EVAL_MODEL_LABEL") or os.getenv("LLM_MODEL") or "not_recorded"
CASES_PATH = Path(__file__).parent / "cases.json"


def unwrap(response: httpx.Response) -> Any:
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 1:
        raise RuntimeError(body.get("msg") or str(body))
    return body.get("data")


def load_cases() -> list[dict[str, Any]]:
    cases = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    if not isinstance(cases, list) or not cases:
        raise ValueError("评测用例必须是非空数组")
    return cases


def _default_results_file() -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return Path(__file__).parent / "results" / f"live-eval-{stamp}.jsonl"


def _write_result(path: Path, entry: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as output:
        output.write(json.dumps(entry, ensure_ascii=False) + "\n")


def _percentile(values: list[float], percentile: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, -(-len(ordered) * percentile // 100) - 1))
    return round(ordered[index], 1)


def summarize(results: list[dict[str, Any]], model: str, runs: int) -> dict[str, Any]:
    """Create a redacted, comparable quality report from JSONL-safe fields."""
    per_case: dict[str, list[dict[str, Any]]] = {}
    failures: dict[str, int] = {}
    for result in results:
        case_id = str(result.get("caseId") or "unknown")
        per_case.setdefault(case_id, []).append(result)
        for failure in result.get("failures") or []:
            category = str(failure).split(":", 1)[-1][:120]
            failures[category] = failures.get(category, 0) + 1
    latencies = [float(item["latencyMs"]) for item in results if isinstance(item.get("latencyMs"), (int, float))]
    return {
        "schemaVersion": 1,
        "model": model,
        "runsPerCase": runs,
        "totalCases": len(results),
        "passedCases": sum(bool(item.get("success")) for item in results),
        "successRate": round(sum(bool(item.get("success")) for item in results) / max(len(results), 1) * 100, 1),
        "latencyMs": {"p50": _percentile(latencies, 50), "p95": _percentile(latencies, 95),
                      "max": round(max(latencies), 1) if latencies else None},
        "caseSuccess": {
            case_id: {
                "runs": len(case_results),
                "passed": sum(bool(item.get("success")) for item in case_results),
                "successRate": round(sum(bool(item.get("success")) for item in case_results) / len(case_results) * 100, 1),
            }
            for case_id, case_results in sorted(per_case.items())
        },
        "failuresByAssertion": dict(sorted(failures.items())),
    }


def _tool_names(data: dict[str, Any], status: str | None = None) -> set[str]:
    calls = data.get("toolCalls") or []
    return {
        str(call.get("tool")) for call in calls
        if call.get("tool") and (status is None or call.get("status") == status)
    }


def _failed_tool_names(data: dict[str, Any]) -> set[str]:
    """Return attempted tools whose callback did not complete successfully."""
    return _tool_names(data) - _tool_names(data, "ok")


def _execution_event_names(data: dict[str, Any]) -> set[str]:
    return {
        str(event.get("event")) for event in (data.get("executionEvents") or [])
        if isinstance(event, dict) and event.get("event")
    }


def _assert_turn(
    client: httpx.Client,
    headers: dict[str, str],
    turn: dict[str, Any],
    data: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    successful_tools = _tool_names(data, "ok")
    invoked_tools = _tool_names(data)
    failed_tools = _failed_tool_names(data)
    expected = set(turn.get("expectedTools", []))
    forbidden = set(turn.get("forbiddenTools", []))
    allowed_failed = set(turn.get("allowedFailedTools", []))
    expected_events = set(turn.get("expectedExecutionEvents", []))
    missing = sorted(expected - successful_tools)
    missing_allowed_failures = sorted(allowed_failed - failed_tools)
    # A rejected callback is evidence that deterministic backend validation
    # blocked an unsafe request, not that a write succeeded. Keep the attempt
    # in the result, but only allow it for scenarios that declare it explicitly.
    unexpected = sorted((forbidden & invoked_tools) - (allowed_failed & failed_tools))
    if missing:
        failures.append("missing_tools:" + ",".join(missing))
    if missing_allowed_failures:
        failures.append("expected_failed_tools_missing:" + ",".join(missing_allowed_failures))
    if unexpected:
        failures.append("forbidden_tools:" + ",".join(unexpected))
    missing_events = sorted(expected_events - _execution_event_names(data))
    if missing_events:
        failures.append("missing_execution_events:" + ",".join(missing_events))

    confirmation = data.get("pendingConfirmation")
    expected_confirmation = turn.get("confirmation", "optional")
    if expected_confirmation == "required" and not confirmation:
        failures.append("confirmation_missing")
    if expected_confirmation == "forbidden" and confirmation:
        failures.append("confirmation_unexpected")
    if expected_confirmation == "cancelled" and (
            not confirmation or confirmation.get("status") != "cancelled"):
        failures.append("cancellation_confirmation_missing")

    expected_items = turn.get("draftItems")
    if expected_items is not None and confirmation:
        actual_items = confirmation.get("items") or []
        actual = {(item.get("dishName"), item.get("quantity")) for item in actual_items}
        wanted = {(item["dishName"], item["quantity"]) for item in expected_items}
        if actual != wanted:
            failures.append("draft_items_mismatch")

    # Prove the returned confirmation was persisted in the Java service.
    if confirmation and confirmation.get("draftId") and confirmation.get("status") != "cancelled":
        pending = unwrap(client.get("/order/drafts/pending", headers=headers))
        if confirmation["draftId"] not in {item.get("id") for item in pending}:
            failures.append("draft_not_persisted")

    preference = turn.get("preference")
    if preference:
        actual_preference = unwrap(client.get("/user/preferences", headers=headers))
        for key, value in preference.items():
            if actual_preference.get(key) != value:
                failures.append("preference_mismatch:" + key)
    return failures


def _cleanup_drafts(client: httpx.Client, headers: dict[str, str]) -> None:
    for draft in unwrap(client.get("/order/drafts/pending", headers=headers)):
        draft_id = draft.get("id")
        if draft_id:
            unwrap(client.delete(f"/order/drafts/{draft_id}", headers=headers))


def _order_total(payload: Any) -> int:
    """The Java list endpoint is paged; use its total so old orders cannot hide a mistaken write."""
    if not isinstance(payload, dict):
        raise ValueError("订单列表响应格式错误")
    items = payload.get("items")
    if not isinstance(items, list):
        raise ValueError("订单列表缺少 items")
    try:
        total = int(payload.get("total"))
    except (TypeError, ValueError) as exc:
        raise ValueError("订单列表缺少 total") from exc
    if total < len(items):
        raise ValueError("订单列表 total 小于当前页数据")
    return total


def run_case(client: httpx.Client, headers: dict[str, str], case: dict[str, Any], delay_seconds: float) -> dict[str, Any]:
    trace_id = f"eval-{uuid.uuid4().hex}"
    started = time.perf_counter()
    history: list[dict[str, str]] = []
    turn_results: list[dict[str, Any]] = []
    failures: list[str] = []
    before_orders = _order_total(unwrap(client.get("/order/list", headers=headers)))
    original_preference = unwrap(client.get("/user/preferences", headers=headers))
    error_category: str | None = None
    try:
        for index, turn in enumerate(case["turns"], start=1):
            request_headers = {**headers, "X-Request-Id": f"{trace_id}-{index}"}
            response = unwrap(client.post(
                "/chat", headers=request_headers,
                json={"message": turn["message"], "history": history},
            ))
            turn_failures = _assert_turn(client, headers, turn, response)
            failures.extend(f"turn_{index}:{item}" for item in turn_failures)
            turn_results.append({
                "turn": index,
                "tools": sorted(_tool_names(response)),
                "successfulTools": sorted(_tool_names(response, "ok")),
                "failedTools": sorted(_failed_tool_names(response)),
                "executionEvents": sorted(_execution_event_names(response)),
                "hasConfirmation": bool(response.get("pendingConfirmation")),
                "failures": turn_failures,
            })
            history.extend([
                {"role": "user", "content": turn["message"]},
                {"role": "assistant", "content": response.get("reply") or ""},
            ])
            if delay_seconds:
                time.sleep(delay_seconds)
        after_orders = _order_total(unwrap(client.get("/order/list", headers=headers)))
        if after_orders != before_orders:
            failures.append("unexpected_order_created")
    except httpx.TimeoutException:
        error_category = "http_timeout"
        failures.append(error_category)
    except httpx.HTTPError:
        error_category = "http_error"
        failures.append(error_category)
    except (RuntimeError, ValueError):
        error_category = "runner_or_backend_error"
        failures.append(error_category)
    finally:
        try:
            _cleanup_drafts(client, headers)
            unwrap(client.put("/user/preferences", headers=headers, json=original_preference))
        except (httpx.HTTPError, RuntimeError, ValueError):
            failures.append("cleanup_failed")

    return {
        "caseId": case["id"],
        "traceId": trace_id,
        "success": not failures,
        "failureCategory": error_category,
        "failures": failures,
        "turns": turn_results,
        "latencyMs": round((time.perf_counter() - started) * 1000, 1),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=DEFAULT_RUNS, help="每条场景重复次数")
    parser.add_argument("--request-delay-seconds", type=float, default=DEFAULT_DELAY_SECONDS,
                        help="同一评测用户相邻请求的最小间隔，避免触发生产限流")
    parser.add_argument("--results-file", type=Path, default=None, help="脱敏 JSONL 结果路径")
    parser.add_argument("--summary-file", type=Path, default=None, help="脱敏汇总 JSON 路径")
    parser.add_argument("--model-label", default=DEFAULT_MODEL_LABEL,
                        help="写入脱敏汇总的模型标识；默认使用 EVAL_MODEL_LABEL 或 LLM_MODEL")
    args = parser.parse_args(argv)
    if args.runs < 1:
        parser.error("--runs 必须大于 0")
    if args.request_delay_seconds < 0:
        parser.error("--request-delay-seconds 不能为负数")
    results_file = args.results_file or Path(os.getenv("EVAL_RESULTS_FILE", _default_results_file()))
    summary_file = args.summary_file or results_file.with_suffix(".summary.json")
    cases = load_cases()
    failures = 0
    results: list[dict[str, Any]] = []
    with httpx.Client(base_url=BASE_URL, timeout=90) as client:
        login = unwrap(client.post("/auth/login", json={"username": USERNAME, "password": PASSWORD}))
        headers = {"Authorization": f"Bearer {login['token']}"}
        for run in range(1, args.runs + 1):
            for case in cases:
                result = run_case(client, headers, case, args.request_delay_seconds)
                result["run"] = run
                _write_result(results_file, result)
                results.append(result)
                label = "PASS" if result["success"] else "FAIL"
                print(f"{label} run={run} case={case['id']} latencyMs={result['latencyMs']}")
                failures += int(not result["success"])
    summary_file.parent.mkdir(parents=True, exist_ok=True)
    summary_file.write_text(json.dumps(summarize(results, args.model_label, args.runs),
                                       ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Results: {results_file} | summary={summary_file} | cases={len(cases) * args.runs} | failures={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
