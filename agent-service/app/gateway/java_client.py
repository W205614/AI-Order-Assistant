"""Java 点餐后端 HTTP 客户端。

解析统一 Result 信封 {code, msg, data}：code=1 成功返回 data，否则抛 JavaApiError。
用户接口需要携带 JWT（header: Authorization）。
"""
from __future__ import annotations

from threading import Lock
from typing import Any, Dict, Optional

import httpx

from ..config import settings


_HTTP_CLIENT: httpx.Client | None = None
_HTTP_CLIENT_LOCK = Lock()


def _http_client() -> httpx.Client:
    """进程内复用连接，避免每一次工具调用都重新建立 TCP 连接。"""
    global _HTTP_CLIENT
    if _HTTP_CLIENT is None:
        with _HTTP_CLIENT_LOCK:
            if _HTTP_CLIENT is None:
                _HTTP_CLIENT = httpx.Client(
                    limits=httpx.Limits(
                        max_connections=50,
                        max_keepalive_connections=20,
                        keepalive_expiry=30.0,
                    )
                )
    return _HTTP_CLIENT


def close_http_client() -> None:
    """供应用退出钩子调用，释放长连接池。"""
    global _HTTP_CLIENT
    with _HTTP_CLIENT_LOCK:
        if _HTTP_CLIENT is not None:
            _HTTP_CLIENT.close()
            _HTTP_CLIENT = None


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
        return self._request("GET", path, token=token, params=params)

    def post(self, path: str, token: Optional[str] = None, json: Optional[Dict] = None,
             idempotency_key: Optional[str] = None) -> Any:
        return self._request("POST", path, token=token, json=json, idempotency_key=idempotency_key)

    def put(self, path: str, token: Optional[str] = None, json: Optional[Dict] = None) -> Any:
        return self._request("PUT", path, token=token, json=json)

    def delete(self, path: str, token: Optional[str] = None) -> Any:
        return self._request("DELETE", path, token=token)

    def _request(
        self,
        method: str,
        path: str,
        token: Optional[str] = None,
        params: Optional[Dict] = None,
        json: Optional[Dict] = None,
        idempotency_key: Optional[str] = None,
    ) -> Any:
        try:
            resp = _http_client().request(
                method,
                f"{self.base_url}{path}",
                params=params,
                json=json,
                headers=self._headers(token, idempotency_key),
                timeout=self.timeout,
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
