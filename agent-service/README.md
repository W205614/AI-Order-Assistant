# agent-service

AI 点餐助手的 Python Agent：FastAPI 提供内部聊天接口，LangGraph 驱动 LLM Function Calling，并携带原用户 JWT 回调 Java 网关。Agent 不直接访问数据库，也不能直接确认真实订单。

## 能力

- 菜单查询与基于偏好/预算的个性化推荐。
- 明确授权后保存过敏原、忌口、饮食目标和预算。
- 创建、读取、修改和放弃待确认购物车。
- 查询订单、查看详情、取消和记录催单。
- 本地 FAQ 词法检索：关键词 + 标题 bigram 评分，用于退款、配送、催单和取消等非交易问答。
- 网关共享密钥认证、用户身份一致性校验和按用户限流。
- 对话轮数、工具成功率、端到端与阶段 P50/P95 延迟、成功率指标；JSONL 文件自动轮转。

## 启动

```bash
cd agent-service
cp .env.example .env
pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8800
```

必须配置：

- `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`
- `JAVA_BASE_URL`，默认 `http://localhost:9090`
- `AGENT_INTERNAL_API_KEY`，至少 32 位，并与 Java 的 `AI_INTERNAL_API_KEY` 相同

可调参数包括 `AGENT_MAX_ITERATIONS`、`AGENT_RATE_LIMIT_PER_MINUTE`、`JAVA_TIMEOUT`、`FAQ_THRESHOLD`、`FAQ_FAST_PATH_THRESHOLD`、`METRICS_MAX_BYTES`。数值越界或格式错误会在启动时给出明确错误。FAQ 线上固定使用关键词 + bigram；`keyword_only` 只用于离线基线对比。首次、无菜单选择的高置信静态 FAQ 可使用快路径直接回答；订单查询、取消、催单和任意带历史的请求仍由 Agent 编排。

## 接口

- `GET /health`：健康检查。
- `GET /stats`：聚合的本地对话指标。
- `POST /chat`：仅供 Java 网关调用，要求 `X-Agent-Internal-Key` 与 `X-Agent-User-Id`。

## 工具

| 工具 | 用途 |
|---|---|
| `get_food_preferences` | 读取已保存饮食偏好 |
| `update_food_preferences` | 在用户明确要求时增量保存偏好 |
| `list_menu` | 查询价格、分类、售罄状态和过敏原 |
| `create_order_draft` | 创建待确认购物车，不下单 |
| `get_current_order_draft` | 读取当前活动购物车 |
| `update_order_draft` | 用完整菜品列表更新购物车 |
| `cancel_order_draft` | 放弃待确认购物车 |
| `query_orders` | 查询订单列表和状态 |
| `get_order_detail` | 查询本人订单详情 |
| `cancel_order` | 取消未结束订单 |
| `remind_order` | 记录一次催单，不声称已通知商家 |
| `search_faq` | 检索退款、配送等本地 FAQ |

## 测试与真实评测

```bash
run-tests.bat
```

该脚本固定使用 `ai-order-agent` Conda 环境，避免 Windows 中默认 `python` 指向 base 环境而造成依赖或行为不一致。若手动执行，先运行 `conda activate ai-order-agent`。

Java 网关与 Agent 都启动后：

```bash
conda run -n ai-order-agent python evals/run_live_eval.py
```

`evals/cases.json` 定义期望/禁止调用的工具和确认单要求。评测执行器使用真实模型，失败时返回非零退出码，并自动取消测试产生的草稿。

FAQ 检索无需启动服务或配置模型：

```bash
cd agent-service
python evals/run_faq_eval.py --iterations 100
```

`evals/faq_cases.json` 是 39 条不含真实用户内容的标注样本，覆盖 11 类 FAQ、易混淆问法和 6 条无答案问题。当前默认混合评分的离线结果为 Top-1 准确率 92.31%、Precision@1 96.77%、Recall@1 90.91%、无答案拒答率 100%；关键词基线分别为 84.62%、87.10%、81.82%、100%。命令同时输出仅限本机内存检索的 P50/P95，不能当作网关、模型或首 token 延迟。

`GET /stats` 还会聚合 `llm_decision`、`faq_retrieval`、`faq_fast_path`、`llm_answer` 和 `tool:*` 的阶段耗时，只保留阶段名与毫秒数，不保存问题、回复或凭证。聊天响应中的 `executionEvents` 仅返回固定的 UI 执行里程碑，不返回模型推理过程。当前聊天接口不是流式接口，不能报告首 token 耗时。
