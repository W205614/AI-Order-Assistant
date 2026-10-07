import json
import time
import unittest
from unittest.mock import Mock, patch
from pydantic import ValidationError
from fastapi import HTTPException
from app import budget, main
from app.agent.tools import ToolContext, execute_tool
from app.gateway.java_client import JavaClient
from app.schemas import ChatRequest


class BudgetBoundaryTest(unittest.TestCase):
    def test_provider_usage_is_reported_and_request_scoped(self):
        from types import SimpleNamespace
        with budget.admission(1,time.time()+35):
            budget.record_provider_usage(SimpleNamespace(prompt_tokens=100,completion_tokens=20))
            budget.record_provider_usage(SimpleNamespace(prompt_tokens=50,completion_tokens=10))
            self.assertEqual({'inputTokens':150,'outputTokens':30,'modelCalls':2},budget.provider_usage())
        self.assertIsNone(budget.provider_usage())

    def test_expired_deadline_does_not_dispatch_write(self):
        ctx = ToolContext("token", merchant_id=2, deadline=time.time()-1)
        with patch("app.agent.tools._client") as downstream:
            result = execute_tool(ctx,"create_order_draft",json.dumps({"items":[{"dishId":1,"quantity":1}]}))
        self.assertFalse(result["ok"])
        self.assertEqual("agent_deadline",result["error"]["category"])
        downstream.assert_not_called()

    def test_merchant_cannot_be_supplied_by_model_arguments(self):
        with patch("app.agent.tools._client") as downstream:
            result=execute_tool(ToolContext("token",merchant_id=2),"create_order_draft",
                                json.dumps({"merchantId":1,"items":[{"dishId":1,"quantity":1}]}))
        self.assertFalse(result["ok"])
        downstream.assert_not_called()

    def test_internal_headers_keep_fixed_tenant_and_deadline(self):
        headers=JavaClient(merchant_id=2,deadline=1234567890.123)._headers("token")
        self.assertEqual("2",headers["X-Merchant-Id"])
        self.assertEqual("1234567890123",headers["X-Agent-Deadline"])

    def test_cancellation_only_prepares_confirmation(self):
        client=Mock()
        client.get.return_value={"status":1,"totalAmount":10}
        ctx=ToolContext("token",merchant_id=2)
        with patch("app.agent.tools._client",return_value=client):
            result=execute_tool(ctx,"cancel_order",'{"order_id":7}')
        self.assertTrue(result["ok"])
        self.assertEqual("cancel_order",ctx.pending_confirmation["action"])
        client.post.assert_not_called()
        self.assertIn("尚未取消",result["data"])

    def test_admission_releases_after_failure_and_bounds_each_merchant(self):
        with patch.object(budget.settings,"max_concurrent",2), patch.object(budget.settings,"merchant_concurrent",1):
            with budget.admission(987,time.time()+35):
                with self.assertRaises(budget.CapacityExceeded):
                    with budget.admission(987,time.time()+35):
                        pass
                with budget.admission(988,time.time()+35):
                    with self.assertRaises(budget.CapacityExceeded):
                        with budget.admission(989,time.time()+35):
                            pass
            self.assertEqual(0,budget.gauges()["active"])

    def test_request_token_budget_is_hard_limit(self):
        with patch.object(budget.settings,"rate_limit_backend","memory"), patch.object(budget.settings,"request_token_budget",100):
            with budget.admission(980,time.time()+35):
                budget.reserve_tokens(60)
                with self.assertRaises(budget.BudgetExceeded):
                    budget.reserve_tokens(50)

    def test_api_requires_trusted_context_and_matching_headers(self):
        with self.assertRaises(ValidationError):
            ChatRequest(message="hello")
        req=ChatRequest(userId=1,merchantId=2,deadlineEpochMs=int((time.time()+35)*1000),message="hello")
        with patch("app.main._verify_internal_access"):
            with self.assertRaises(HTTPException) as error:
                main.chat(req,"key","1","1",str(req.deadlineEpochMs))
        self.assertEqual(401,error.exception.status_code)

    def test_version_is_mandatory_before_update_dispatch(self):
        with patch("app.agent.tools._client") as downstream:
            result=execute_tool(ToolContext("token"),"update_order_draft",
                '{"draft_id":"00000000-0000-0000-0000-000000000001","items":[{"dishId":1,"quantity":1}]}')
        self.assertFalse(result["ok"])
        downstream.assert_not_called()
