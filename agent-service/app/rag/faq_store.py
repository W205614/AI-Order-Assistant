"""关键词 + bigram 重叠评分的本地 FAQ 检索。"""
from __future__ import annotations

from typing import List, Tuple

from .faq_data import FAQ_ITEMS


def _bigrams(text: str) -> set:
    chars = [c for c in text if not c.isspace()]
    return {chars[i] + chars[i + 1] for i in range(len(chars) - 1)}


def search_faq(question: str, threshold: float = 0.35) -> List[Tuple[dict, float]]:
    """返回 (FAQ条目, 得分) 列表，按得分降序。"""
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
        # 2) 标题大词重叠
        title = item["title"]
        t_bigrams = _bigrams(title)
        if q_bigrams and t_bigrams:
            overlap = len(q_bigrams & t_bigrams)
            score += overlap / len(t_bigrams) * 0.4
        if score > 0:
            scored.append((item, score))

    scored.sort(key=lambda x: x[1], reverse=True)
    return [x for x in scored if x[1] >= threshold]
