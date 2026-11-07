"""Exercise the real SDK at the HTTP boundary without sending paid requests."""

import json
import unittest
from unittest.mock import patch

import httpx
from openai import OpenAI

from app.agent import llm


class LlmProviderContractTest(unittest.TestCase):
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
