"""对运行中的完整系统执行真实模型工具调用回归。

环境变量：EVAL_BASE_URL、EVAL_USERNAME、EVAL_PASSWORD。
默认使用本地 demo 账号；用例生成的待确认草稿会自动取消。
"""
from __future__ import annotations

import json
import os
import sys
import uuid
from pathlib import Path

import httpx


BASE_URL = os.getenv("EVAL_BASE_URL", "http://localhost:9090").rstrip("/")
USERNAME = os.getenv("EVAL_USERNAME", "demo")
PASSWORD = os.getenv("EVAL_PASSWORD", "123456")


def unwrap(response: httpx.Response):
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 1:
        raise RuntimeError(body.get("msg") or str(body))
    return body.get("data")


def main() -> int:
    cases = json.loads((Path(__file__).parent / "cases.json").read_text(encoding="utf-8"))
    with httpx.Client(base_url=BASE_URL, timeout=90) as client:
        login = unwrap(client.post("/auth/login", json={"username": USERNAME, "password": PASSWORD}))
        headers = {"Authorization": f"Bearer {login['token']}"}
        failures = []
        for case in cases:
            request_headers = {**headers, "X-Request-Id": f"eval-{uuid.uuid4().hex}"}
            data = unwrap(client.post("/chat", headers=request_headers,
                                      json={"message": case["message"], "history": []}))
            called = {item["tool"] for item in data.get("toolCalls", []) if item.get("status") == "ok"}
            missing = set(case.get("expectedTools", [])) - called
            forbidden = set(case.get("forbiddenTools", [])) & called
            confirmation = data.get("pendingConfirmation")
            confirmation_mismatch = case.get("expectConfirmation") is True and not confirmation
            if missing or forbidden or confirmation_mismatch:
                failures.append((case["id"], missing, forbidden, confirmation_mismatch))
                print(f"FAIL {case['id']}: called={sorted(called)}")
            else:
                print(f"PASS {case['id']}: called={sorted(called)}")
            if confirmation and confirmation.get("draftId"):
                client.delete(f"/order/drafts/{confirmation['draftId']}", headers=headers)
    if failures:
        print(f"{len(failures)} evaluation case(s) failed")
        return 1
    print(f"All {len(cases)} live evaluation cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
