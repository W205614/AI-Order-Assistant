import unittest

from app.rag.faq_store import search_faq
from evals.run_faq_eval import evaluate, load_cases


class FaqRetrievalTest(unittest.TestCase):
    def test_keyword_only_is_a_supported_baseline(self):
        hits = search_faq("取消订单怎么操作", mode="keyword_only")
        self.assertEqual("如何取消订单", hits[0][0]["title"])

    def test_hybrid_default_preserves_expected_faq(self):
        hits = search_faq("取消订单怎么操作")
        self.assertEqual("如何取消订单", hits[0][0]["title"])

    def test_threshold_and_unknown_question_return_no_hit(self):
        self.assertTrue(search_faq("退款", threshold=0.65))
        self.assertFalse(search_faq("退款", threshold=0.68))
        self.assertFalse(search_faq("图书馆今天几点开门"))

    def test_unknown_mode_is_rejected(self):
        with self.assertRaises(ValueError):
            search_faq("退款", mode="dense_vector")  # type: ignore[arg-type]

    def test_checked_in_dataset_shows_hybrid_quality_gain(self):
        cases = load_cases()
        baseline = evaluate(cases, threshold=0.35, mode="keyword_only", iterations=1)
        hybrid = evaluate(cases, threshold=0.35, mode="keyword_plus_bigram", iterations=1)
        self.assertEqual(39, hybrid["cases"])
        self.assertEqual(100.0, hybrid["unknownRejectionRate"])
        self.assertGreater(hybrid["top1Accuracy"], baseline["top1Accuracy"])
        self.assertGreater(hybrid["recallAt1"], baseline["recallAt1"])

    def test_metric_definitions_include_true_rejections(self):
        cases = [
            {"id": "known", "query": "退款", "expectedTitle": "如何申请退款"},
            {"id": "unknown", "query": "图书馆几点开门", "expectedTitle": None},
        ]
        result = evaluate(cases, threshold=0.35, mode="keyword_plus_bigram", iterations=1)
        self.assertEqual(100.0, result["top1Accuracy"])
        self.assertEqual(100.0, result["precisionAt1"])
        self.assertEqual(100.0, result["recallAt1"])
        self.assertEqual(100.0, result["unknownRejectionRate"])


if __name__ == "__main__":
    unittest.main()
