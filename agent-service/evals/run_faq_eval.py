"""Run deterministic, offline quality and latency checks for the local FAQ retriever.

The benchmark never calls an LLM or a remote service.  It measures only this
repository's small in-memory FAQ lookup, so its timing must not be presented as
end-to-end chat latency.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any, Iterable

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.rag.faq_data import FAQ_ITEMS
from app.rag.faq_store import DEFAULT_SEARCH_MODE, SearchMode, search_faq

CASES_PATH = Path(__file__).with_name("faq_cases.json")
MODES: tuple[SearchMode, ...] = ("keyword_only", "keyword_plus_bigram")


def _percentile(values: list[float], percentile: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, -(-len(ordered) * percentile // 100) - 1))
    return round(ordered[index], 4)


def load_cases(path: Path = CASES_PATH) -> list[dict[str, str | None]]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list) or not raw:
        raise ValueError("FAQ 评测集必须是非空数组")
    known_titles = {str(item["title"]) for item in FAQ_ITEMS}
    cases: list[dict[str, str | None]] = []
    case_ids: set[str] = set()
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError("FAQ 评测项必须是对象")
        case_id, query, expected = item.get("id"), item.get("query"), item.get("expectedTitle")
        if not isinstance(case_id, str) or not case_id or case_id in case_ids:
            raise ValueError("FAQ 评测项 id 必须唯一且非空")
        if not isinstance(query, str) or not query.strip():
            raise ValueError(f"FAQ 评测项 {case_id} 的 query 必须非空")
        if expected is not None and (not isinstance(expected, str) or expected not in known_titles):
            raise ValueError(f"FAQ 评测项 {case_id} 的 expectedTitle 不存在于 FAQ")
        case_ids.add(case_id)
        cases.append({"id": case_id, "query": query, "expectedTitle": expected})
    return cases


def _top_title(query: str, threshold: float, mode: SearchMode) -> str | None:
    hits = search_faq(query, threshold=threshold, mode=mode)
    return str(hits[0][0]["title"]) if hits else None


def evaluate(cases: Iterable[dict[str, str | None]], threshold: float, mode: SearchMode, iterations: int) -> dict[str, Any]:
    """Evaluate Top-1 retrieval and repeat only retrieval for timing samples."""
    materialized = list(cases)
    if iterations < 1:
        raise ValueError("iterations 必须大于 0")

    answerable = returned = correct = true_rejections = 0
    for case in materialized:
        expected = case["expectedTitle"]
        predicted = _top_title(str(case["query"]), threshold, mode)
        answerable += int(expected is not None)
        returned += int(predicted is not None)
        correct += int(expected is not None and predicted == expected)
        true_rejections += int(expected is None and predicted is None)

    timings: list[float] = []
    for _ in range(iterations):
        for case in materialized:
            started = time.perf_counter()
            _top_title(str(case["query"]), threshold, mode)
            timings.append((time.perf_counter() - started) * 1000)

    total = len(materialized)
    unknown = total - answerable
    return {
        "mode": mode,
        "threshold": threshold,
        "cases": total,
        "answerableCases": answerable,
        "unknownCases": unknown,
        "top1Accuracy": round((correct + true_rejections) / total * 100, 2),
        "precisionAt1": round(correct / returned * 100, 2) if returned else None,
        "recallAt1": round(correct / answerable * 100, 2) if answerable else None,
        "unknownRejectionRate": round(true_rejections / unknown * 100, 2) if unknown else None,
        "retrievalLatencyMs": {
            "samples": len(timings),
            "p50": _percentile(timings, 50),
            "p95": _percentile(timings, 95),
            "max": round(max(timings), 4) if timings else None,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threshold", type=float, default=0.35, help="命中阈值，范围 0-1")
    parser.add_argument("--iterations", type=int, default=100, help="每条样本的重复检索次数")
    parser.add_argument("--dataset", type=Path, default=CASES_PATH, help="标注 FAQ 评测集路径")
    args = parser.parse_args(argv)
    if not 0 <= args.threshold <= 1:
        parser.error("--threshold 必须在 0-1 之间")
    if args.iterations < 1:
        parser.error("--iterations 必须大于 0")
    cases = load_cases(args.dataset)
    report = {
        "retriever": "local_keyword_and_bigram",
        "productionMode": DEFAULT_SEARCH_MODE,
        "dataset": args.dataset.name,
        "definitions": {
            "top1Accuracy": "答案命中正确 FAQ，或无答案问题被正确拒答，占全部样本比例。",
            "precisionAt1": "正确 Top-1 命中占所有返回结果比例。",
            "recallAt1": "正确 Top-1 命中占所有有答案样本比例。",
            "unknownRejectionRate": "无答案问题未返回 FAQ 的比例。",
            "retrievalLatencyMs": "仅进程内 FAQ 检索微基准，不含网关、LLM、网络或首 token。",
        },
        "results": [evaluate(cases, args.threshold, mode, args.iterations) for mode in MODES],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
