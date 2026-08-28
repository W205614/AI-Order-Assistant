import json
import unittest
from pathlib import Path
from unittest.mock import patch

from app.agent import prompts, tools
from app.agent.tools import ToolContext
from app.gateway.java_client import JavaApiError


class FakeJavaClient:
    def __init__(self):
        self.preference = {
            "allergens": "花生",
            "dislikes": "香菜",
            "dietaryGoal": "减脂",
            "budget": 30,
        }
        self.put_body = None

    def get(self, path, **_kwargs):
        if path == "/user/preferences":
            return dict(self.preference)
        if path == "/dish/list":
            return [{
                "name": "宫保鸡丁饭", "price": 18, "category": "主食",
                "description": "微辣", "status": 1, "allergens": "花生",
            }]
        raise AssertionError(path)

    def put(self, path, json=None, **_kwargs):
        self.put_body = json
        self.preference = dict(json or {})
        return dict(self.preference)


class AgentToolRulesTest(unittest.TestCase):
    def test_menu_exposes_allergen_labels(self):
        fake = FakeJavaClient()
        with patch("app.agent.tools._client", return_value=fake):
            result = tools.execute_tool(ToolContext("Bearer token"), "list_menu", "{}")
        self.assertIn("过敏原：花生", result)
        self.assertIn("宫保鸡丁饭", result)

    def test_preference_update_merges_unspecified_fields(self):
        fake = FakeJavaClient()
        with patch("app.agent.tools._client", return_value=fake):
            result = tools.execute_tool(
                ToolContext("Bearer token"), "update_food_preferences",
                json.dumps({"budget": 25}, ensure_ascii=False),
            )
        self.assertEqual("花生", fake.put_body["allergens"])
        self.assertEqual("香菜", fake.put_body["dislikes"])
        self.assertEqual(25, fake.put_body["budget"])
        self.assertIn("¥25.00", result)

    def test_backend_rejection_is_visible_to_model(self):
        with patch("app.agent.tools._client") as client_factory:
            client_factory.return_value.post.side_effect = JavaApiError("过敏原冲突")
            result = tools.execute_tool(
                ToolContext("Bearer token"), "create_order_draft",
                json.dumps({"items": [{"dishName": "宫保鸡丁饭", "quantity": 1}]}, ensure_ascii=False),
            )
        self.assertEqual("后端调用失败：过敏原冲突", result)

    def test_prompt_requires_explicit_consent_and_safe_recommendation(self):
        text = prompts.system_prompt()
        self.assertIn("不得静默永久保存", text)
        self.assertIn("绝不推荐标注了用户过敏原的菜品", text)
        self.assertIn("绝不能直接下单", text)

    def test_registered_tool_names_are_unique(self):
        names = [item["function"]["name"] for item in tools.TOOL_SCHEMAS]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(set(names), set(tools._TOOL_HANDLERS))


class EvaluationDatasetTest(unittest.TestCase):
    def test_live_cases_reference_registered_tools(self):
        path = Path(__file__).parents[1] / "evals" / "cases.json"
        cases = json.loads(path.read_text(encoding="utf-8"))
        registered = set(tools._TOOL_HANDLERS)
        self.assertGreaterEqual(len(cases), 8)
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        for case in cases:
            self.assertTrue(case["message"].strip())
            self.assertTrue(set(case.get("expectedTools", [])) <= registered)
            self.assertTrue(set(case.get("forbiddenTools", [])) <= registered)


if __name__ == "__main__":
    unittest.main()
