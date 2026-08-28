"""Java 点餐后端 HTTP 客户端。

解析统一 Result 信封 {code, msg, data}：code=1 成功返回 data，否则抛 JavaApiError。
用户接口需要携带 JWT（header: Authorization）。
"""
from __future__ import annotations

from typing import Any, Dict, Optional

import httpx

from ..config import settings


class JavaApiError(Exception):
    """Java 后端返回失败或网络异常。"""

    def __init__(self, msg: str):
        self.msg = msg
        super().__init__(msg)


class JavaClient:
    def __init__(self, base_url: Optional[str] = None, timeout: float = 30.0):
        self.base_url = (base_url or settings.java_base_url).rstrip("/")
        self.timeout = timeout

    def _headers(self, token: Optional[str] = None, idempotency_key: Optional[str] = None) -> Dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = token
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        return headers

    def get(self, path: str, token: Optional[str] = None, params: Optional[Dict] = None) -> Any:
        try:
            resp = httpx.get(
                f"{self.base_url}{path}", params=params,
                headers=self._headers(token), timeout=self.timeout,
            )
        except httpx.HTTPError as e:
            raise JavaApiError(f"无法连接 Java 后端（{self.base_url}）: {e}")
        return self._parse(resp)

    def post(self, path: str, token: Optional[str] = None, json: Optional[Dict] = None,
             idempotency_key: Optional[str] = None) -> Any:
        try:
            resp = httpx.post(
                f"{self.base_url}{path}", json=json,
                headers=self._headers(token, idempotency_key), timeout=self.timeout,
            )
        except httpx.HTTPError as e:
            raise JavaApiError(f"无法连接 Java 后端（{self.base_url}）: {e}")
        return self._parse(resp)

    def _parse(self, resp: httpx.Response) -> Any:
        try:
            body = resp.json()
        except Exception:
            raise JavaApiError(f"Java 后端返回非 JSON 数据（HTTP {resp.status_code}）")
        code = body.get("code")
        if code == 1:
            return body.get("data")
        raise JavaApiError(body.get("msg") or f"Java 后端返回失败（code={code}）")
