# agent-service

FastAPI / LangGraph 点餐 Agent，只能通过 Java 执行业务操作。商户、用户和截止时间由 Java 固定；模型不能指定其他商户、直接确认订单或直接取消已下单订单。

## 能力

- 精确菜名与加减数量的确定性购物车 Router；静态 FAQ 使用关键词与标题 bigram 检索。
- 模型推荐、读取偏好、明确授权后保存偏好、创建和修改待确认草稿。
- 查询本人在当前商户的订单，准备待本人点击的取消确认，记录有冷却时间的催单。
- 模拟支付、退款和手动履约说明与 Java 状态一致；不承诺真实资金到账、外部客服或骑手通知。
- 工具参数白名单、商户与身份校验、过敏原硬校验、全程预算和资源限制。

## 启动

项目根目录优先使用 `start.ps1 -Docker -Build`。单独开发：

```powershell
cd agent-service
Copy-Item .env.example .env
python -m pip install -r requirements.txt -r requirements.lock.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8800
```

必须设置至少 32 位的 `AGENT_INTERNAL_API_KEY`，并与网关一致。`JAVA_BASE_URL` 默认 `http://localhost:9090`。模型配置 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` 是可选的；无模型仍可使用静态 FAQ、确定性 Router 和页面普通点餐。

默认单进程并发 8，每商户 2，全程 35 秒，输出上限 1024 token。每请求保守预算 48000 单位，每商户每日预留 2000000 单位；配置与取值约束见 `app/config.py`。内存并发限额不能直接用于多 worker/多副本。Compose 使用 Redis 共享额度和限流，Redis 故障时拒绝需要额度的请求，不无限放行。

`modelUsage` 仅在供应商返回 usage 时提供累计 `inputTokens`、`outputTokens`、`modelCalls`。保守预算、提供商 token 和真实账单是三种不同口径；缺失 usage 不表示免费或零消耗。

## 内部接口

| 路径 | 行为 |
|---|---|
| `GET /health` | 进程存活 |
| `GET /ready` | Redis 等必需依赖就绪 |
| `GET /stats`、`GET /metrics` | 共享密钥保护的脱敏统计与 Prometheus 指标 |
| `POST /chat` | 共享密钥、用户、商户、截止时间一致性校验 |

聊天需要 `X-Agent-Internal-Key`、`X-Agent-User-Id`、`X-Agent-Merchant-Id`、`X-Agent-Deadline-Epoch-Ms`；用户 JWT 只在可信服务间传递，不返回浏览器。响应仍为非流式，`executionEvents` 是固定 UI 里程碑，不是模型推理过程。

## 工具边界

`list_menu`、`query_orders`、`get_order_detail` 只查询当前商户。`get_food_preferences` / `update_food_preferences` 操作用户长期偏好；临时约束、历史和草稿按用户与商户隔离。`update_order_draft` 必须携带读取到的版本，冲突重新读取。`cancel_order` 仅生成待确认操作，顾客点击后由 Java 校验状态并执行取消、库存释放与模拟退款。`remind_order` 记录持久事件并提醒商户页面，每六十秒最多一次。

工具执行前和 Java 回调入口均检查截止时间。菜单、FAQ、历史和用户消息都是不可信文本，不赋予工具权限。页面选菜与交易不依赖 Agent 可用性。

## 验证

Windows 可使用 `run-tests.bat`，固定 `ai-order-agent` Conda 环境；或使用已安装锁定依赖的虚拟环境：

```powershell
python -m unittest discover -s tests -v
python -m compileall -q app tests evals
python evals/run_faq_eval.py --iterations 100
```

FAQ 固定集含 39 条样本、11 类 FAQ 和 6 条无答案问题。结果与本机内存检索延迟分别保存，不能用作网关吞吐、模型回答准确率或首 token 延迟。当前版本的实际结果见根目录验收报告。

Java 和 Agent 已启动、测试账号和商户已准备后，可以运行 `evals/run_live_eval.py --runs 3`。该评测使用 Cookie / CSRF 与 `EVAL_MERCHANT_ID`，需显式配置供应商，可能产生费用；历史评测不能替代当前版本回归。报告不保存提问、回答、JWT 或订单内容。真实模型故障演练、普通业务压测和模型评测应分别报告。
