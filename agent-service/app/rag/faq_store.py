"""可评测的本地 FAQ 词法检索。"""
from __future__ import annotations

from typing import Literal, List, Tuple

from .faq_data import FAQ_ITEMS

SearchMode = Literal["keyword_only", "keyword_plus_bigram"]
DEFAULT_SEARCH_MODE: SearchMode = "keyword_plus_bigram"


def _bigrams(text: str) -> set:
    chars = [c for c in text if not c.isspace()]
    return {chars[i] + chars[i + 1] for i in range(len(chars) - 1)}


def search_faq(
    question: str,
    threshold: float = 0.35,
    mode: SearchMode = DEFAULT_SEARCH_MODE,
) -> List[Tuple[dict, float]]:
    """返回 ``(FAQ 条目, 得分)``，按得分降序。

    ``keyword_only`` 仅用于离线基线；线上默认的
    ``keyword_plus_bigram`` 保留原有关键词与标题 bigram 混合评分行为。
    """
    if mode not in ("keyword_only", "keyword_plus_bigram"):
        raise ValueError(f"不支持的 FAQ 检索模式：{mode}")
    q = question.strip()
    if not q:
        return []

    q_bigrams = _bigrams(q)
    scored = []
    for item in FAQ_ITEMS:
        score = 0.0
        # 1) 关键词直接命中（权重高）
        for kw in item["keywords"]:
            if kw in q:
                score += 0.6
        if mode == "keyword_plus_bigram":
            # 2) 标题 bigram 重叠。它只辅助排序，不声称具备语义检索能力。
            title = item["title"]
            t_bigrams = _bigrams(title)
            if q_bigrams and t_bigrams:
                overlap = len(q_bigrams & t_bigrams)
                score += overlap / len(t_bigrams) * 0.4
        if score > 0:
            scored.append((item, score))

    scored.sort(key=lambda x: x[1], reverse=True)
    return [x for x in scored if x[1] >= threshold]
