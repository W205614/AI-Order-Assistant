"""Exercise the real SDK at the HTTP boundary without sending paid requests."""

import json
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import threading
import unittest
from unittest.mock import patch

import httpx
import httpx2
from openai import OpenAI

from app.agent import llm


@contextmanager
def loopback_provider(*, drop_third=False, retries=0):
    requests = []
    secret = "contract-secret-must-not-appear-in-logs"

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            requests.append({"body": body, "connection": self.client_address[1]})
            if drop_third and len(requests) == 3:
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            payload = json.dumps({
                "id": "loopback-response", "object": "chat.completion", "created": 0,
                "model": "gpt-6-luna", "choices": [{
                    "index": 0, "finish_reason": "tool_calls", "message": {
                        "role": "assistant", "content": None, "tool_calls": [{
                            "id": "call_menu_" + str(len(requests)), "type": "function",
                            "function": {"name": "get_menu", "arguments": '{"merchantId":1}'},
                        }],
                    },
                }], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            self.wfile.flush()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with patch.object(llm, "_OPENAI_CLIENT", None), patch.multiple(
            llm.settings, llm_api_key=secret, llm_base_url=f"http://127.0.0.1:{server.server_port}/v1",
            llm_model="gpt-6-luna", llm_timeout=2, llm_max_retries=retries, evaluation_run_id="",
        ), patch.object(llm, "charge_provider_attempt", wraps=llm.charge_provider_attempt) as charge:
            client = llm._client()
            try:
                yield client, requests, charge, secret
            finally:
                client.close()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(2)


class LlmProviderContractTest(unittest.TestCase):
    def three_tool_turns(self):
        messages = [{"role": "user", "content": "私密菜单查询"}]
        tools = [{"type": "function", "function": {
            "name": "get_menu", "parameters": {"type": "object", "properties": {
                "merchantId": {"type": "integer"},
            }},
        }}]
        responses = []
        for _ in range(3):
            message = llm.chat_with_tools(messages, tools)
            responses.append(message)
            messages.append(message.model_dump(include={"role", "content", "tool_calls"}, exclude_none=True))
            messages.append({"role": "tool", "tool_call_id": message.tool_calls[0].id,
                             "content": json.dumps({"items": [{"name": "鸡肉饭", "price": 12}]}, ensure_ascii=False)})
        return responses

    def test_default_transport_keeps_one_connection_across_three_tool_turns(self):
        with loopback_provider() as (client, requests, charge, _):
            self.assertIsInstance(client._client, httpx2.Client)
            self.assertEqual(0, client.max_retries)
            responses = self.three_tool_turns()
        self.assertEqual(3, len(responses))
        self.assertEqual([1, 3, 5], [len(r["body"]["messages"]) for r in requests])
        self.assertEqual(1, len({r["connection"] for r in requests}))
        self.assertEqual(3, charge.call_count)

    def test_third_turn_disconnect_has_safe_connection_diagnostics(self):
        with loopback_provider(drop_third=True) as (_, requests, charge, secret), self.assertLogs(
            "app.agent.llm", level="WARNING"
        ) as logged:
            with self.assertRaises(llm.LLMError) as raised:
                self.three_tool_turns()
        self.assertEqual("model_connection_error", raised.exception.category)
        self.assertEqual(3, len(requests))
        self.assertEqual(3, charge.call_count)
        text = "\n".join(logged.output)
        for private in (secret, "127.0.0.1", "私密菜单查询", "merchantId", "Authorization"):
            self.assertNotIn(private, text)
        causes = json.loads(logged.records[0].getMessage().split(" causes=", 1)[1])
        self.assertLessEqual(len(causes), 6)
        self.assertIn("RemoteProtocolError", {cause["type"] for cause in causes})
        self.assertIn("peer_closed_before_response", {cause.get("reason") for cause in causes})
        for cause in causes:
            self.assertLessEqual(set(cause), {"type", "errno", "reason"})

    def test_existing_single_retry_charges_every_dispatch_after_third_turn_disconnect(self):
        with loopback_provider(drop_third=True, retries=1) as (client, requests, charge, _), self.assertLogs(
            "app.agent.llm", level="WARNING"
        ):
            self.assertEqual(0, client.max_retries)
            self.assertEqual(3, len(self.three_tool_turns()))
        self.assertEqual(4, len(requests))
        self.assertEqual(4, charge.call_count)
        self.assertTrue(all(call.args[0] > 0 for call in charge.call_args_list))
        self.assertEqual(requests[2]["body"], requests[3]["body"])
        self.assertEqual([1, 3, 5, 5], [len(r["body"]["messages"]) for r in requests])

    def call_model(self, model, *, tools=None):
        requests = []

        def respond(request):
            body = json.loads(request.content)
            requests.append(body)
            message = {"role": "assistant", "content": "菜单查询完成"}
            if tools:
                message = {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [{
                        "id": "call_menu",
                        "type": "function",
                        "function": {"name": "get_menu", "arguments": '{"merchantId":1}'},
                    }],
                }
            return httpx.Response(200, json={
                "id": "contract-response",
                "object": "chat.completion",
                "created": 0,
                "model": model,
                "choices": [{
                    "index": 0,
                    "finish_reason": "tool_calls" if tools else "stop",
                    "message": message,
                }],
                "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18},
            })

        with OpenAI(
            api_key="contract-key",
            base_url="https://provider.invalid/v1",
            max_retries=0,
            http_client=httpx.Client(transport=httpx.MockTransport(respond)),
        ) as client, patch.object(llm, "_OPENAI_CLIENT", client), patch.object(
            llm.settings, "llm_api_key", "contract-key"
        ), patch.object(llm.settings, "llm_model", model), patch.object(
            llm.settings, "evaluation_run_id", ""
        ):
            message = llm.chat_with_tools([{"role": "user", "content": "查询菜单"}], tools)
        self.assertEqual(1, len(requests))
        return requests[0], message

    def test_gpt_luna_serializes_text_and_function_calls(self):
        tools = [{
            "type": "function",
            "function": {
                "name": "get_menu",
                "parameters": {"type": "object", "properties": {
                    "merchantId": {"type": "integer"},
                }},
            },
        }]
        for requested_tools in (None, tools):
            with self.subTest(tool_call=bool(requested_tools)):
                request, message = self.call_model("gpt-6-luna", tools=requested_tools)
                self.assertEqual("none", request["reasoning_effort"])
                self.assertEqual(llm.settings.max_output_tokens, request["max_completion_tokens"])
                self.assertNotIn("max_tokens", request)
                if requested_tools:
                    self.assertEqual(tools, request["tools"])
                    self.assertEqual("auto", request["tool_choice"])
                    self.assertEqual("get_menu", message.tool_calls[0].function.name)
                    self.assertEqual({"merchantId": 1}, json.loads(message.tool_calls[0].function.arguments))
                else:
                    self.assertNotIn("tools", request)
                    self.assertEqual("菜单查询完成", message.content)

    def test_other_provider_keeps_existing_token_contract(self):
        request, message = self.call_model("deepseek-v4-flash")
        self.assertEqual(llm.settings.max_output_tokens, request["max_tokens"])
        self.assertNotIn("max_completion_tokens", request)
        self.assertNotIn("reasoning_effort", request)
        self.assertEqual("菜单查询完成", message.content)
