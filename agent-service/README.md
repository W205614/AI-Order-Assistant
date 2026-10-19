# agent-service

FastAPI / LangGraph 点餐 Agent，通过 Java 执行业务操作。原单店协议固定商户，校园协议固定目的校区、地址与履约方式，再由 Java 核验跨店服务范围；顾客身份和截止时间均来自 Java。模型没有订单确认、支付或取消执行权限。

## 能力

- 原单店对话提供精确菜名与加减数量的确定性购物车 Router；静态 FAQ 使用关键词与标题 bigram 检索。
- 模型推荐、读取偏好、明确授权后保存偏好、创建和修改待确认草稿。
- 查询本人在当前商户的订单，准备待本人点击的取消确认，记录有冷却时间的催单。
- 校园协议支持跨店搜索、预算组合、优惠查询、本人购物车准备、真实报价与结算查询；近期对话按顾客、校区、地址和履约方式隔离。
- 模拟支付、退款和手动履约说明与 Java 状态一致；不承诺真实资金到账、外部客服或骑手通知。
- 工具参数白名单、商户与身份校验、过敏原硬校验、全程预算和资源限制。

## 启动

在项目根目录使用统一部署入口：

```powershell
.\start.ps1 -Build
```

单独开发 Agent 时，在项目根目录执行：

```powershell
cd agent-service
Copy-Item .env.example .env
python -m pip install -r requirements.txt -r requirements.lock.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8800
```

Conda 是可选开发方式：[environment.yml](environment.yml) 定义 `ai-order-agent` 环境；创建后仍按上方命令安装锁定依赖。已有该环境时，可选用 Windows 的 [run-agent.bat](run-agent.bat) 或 Linux/macOS 的 [run-agent.sh](run-agent.sh) 启动开发服务。这些脚本不启动 Java、MySQL 或 Redis；完整部署见 [运维指南](../docs/operations.md)。

必须设置至少 32 位的 `AGENT_INTERNAL_API_KEY`，并与网关一致。`JAVA_BASE_URL` 默认 `http://localhost:9090`。模型配置 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` 是可选的；无模型时原单店对话仍可使用符合条件的静态 FAQ、确定性 Router，校园自然语言对话会降级；页面普通点餐和 Java 预算组合查询不依赖模型。

默认单进程总并发 8，原单店每商户 2；校园每顾客并发 2（`AGENT_CAMPUS_USER_CONCURRENT`），不同顾客不会共用两个名额。请求预算 35 秒，输出上限 1024 token。内存并发限额按进程生效，多 worker/多副本需要另行设计全局准入。

每请求最大保守预算 48000 单位。首次模型调用先预留整份预算，退出时按本轮各次模型调用的 UTF-8 请求字节数与输出预留之和结算，退回未使用部分；超时或失败的已发起调用也计入消耗。原单店日额度由 `AGENT_MERCHANT_DAILY_TOKENS` 控制（每商户默认 2000000），校园平台共享日额度独立由 `AGENT_CAMPUS_DAILY_TOKENS` 控制（默认 2000000），按 UTC 日分桶。跨午夜请求在原预留桶结算，不能用一个顾客的额度绕过平台上限。

Compose 使用单机 Redis 原子共享额度和用户频率限制；额度键保持兼容旧版本余额，每次预留附独立收据实现幂等结算，不要求迁移或清空额度。额度后端故障时在模型发起前拒绝；退出结算故障保留保守预留并增加 `agent_quota_settlement_failures_total`，不覆盖已完成的业务结果。进程异常退出也可能留下当天未退预留，需要根据告警核查，而不能直接把共享额度清零。配置取值约束见 `app/config.py`。

`modelUsage` 仅在供应商返回 usage 时提供累计 `inputTokens`、`outputTokens`、`modelCalls`。保守预算、提供商 token 和真实账单是三种不同口径；缺失 usage 不表示免费或零消耗。

两个聊天入口都按请求记录完成、降级、拒绝和异常。校园工具失败时返回 `degraded`，不会标成 `completed`。`/stats` 和 `/metrics` 从进程内数据聚合，不读取审计磁盘：`agent_chats_total`、`agent_chat_failures_total` 为本进程累计计数，重启归零；延迟、路由、工具和完成率基于最近 10000 条事件窗口，`sampledChats` 给出窗口大小。脱敏日志仍按大小轮转保存供审计。磁盘满、权限或轮转故障会增加 `agent_audit_write_failures_total`，不遮蔽业务响应，也不阻断指标抓取。

## 内部接口

| 路径 | 行为 |
|---|---|
| `GET /health` | 进程存活 |
| `GET /ready` | Redis 等必需依赖就绪 |
| `GET /stats`、`GET /metrics` | 共享密钥保护的脱敏统计与 Prometheus 指标 |
| `POST /chat` | 共享密钥、用户、商户、截止时间一致性校验 |
| `POST /campus-chat` | 固定顾客及校园履约上下文，编排受限跨店工具 |

`/chat` 需要 `X-Agent-Internal-Key`、`X-Agent-User-Id`、`X-Agent-Merchant-Id`、`X-Agent-Deadline`（毫秒时间戳）；用户 JWT 只在可信服务间传递，不返回浏览器。响应仍为非流式，`executionEvents` 是固定 UI 里程碑，不是模型推理过程。校园协议及工具授权范围见 [校园业务说明](../docs/campus-commerce.md)。

## 工具边界

`list_menu`、`query_orders`、`get_order_detail` 只查询当前商户。`get_food_preferences` / `update_food_preferences` 操作用户长期偏好；临时约束、历史和草稿按用户与商户隔离。`update_order_draft` 必须携带读取到的版本，冲突重新读取。`cancel_order` 仅生成待确认操作，顾客点击后由 Java 校验状态并执行取消、库存释放与模拟退款。`remind_order` 记录持久事件并提醒商户页面，每六十秒最多一次。

工具执行前和 Java 回调入口均检查截止时间。菜单、FAQ、历史和用户消息都是不可信文本，不赋予工具权限。页面选菜与交易不依赖 Agent 可用性。

## 验证

在 `agent-service` 目录、已安装锁定依赖的环境内运行：

```powershell
python -m unittest discover -s tests -v
python -m compileall -q app tests evals
python evals/run_faq_eval.py --iterations 100
```

Windows 也可选用 [run-tests.bat](run-tests.bat)，它固定使用 `ai-order-agent` Conda 环境。

`test_stability_accounting.py` 的普通用例只使用内存或桩，不访问模型。其 Redis 原子并发与幂等结算用例仅在设置 `AGENT_TEST_REDIS_URL` 时运行；这个地址必须指向临时、可销毁的专用 Redis，禁止使用日常部署的数据服务。用例使用独立测试键前缀，不清空整个数据库。

FAQ 固定集含 39 条样本、11 类 FAQ 和 6 条无答案问题。结果与本机内存检索延迟分别保存，不能用作网关吞吐、模型回答准确率或首 token 延迟。各版本实际结果见 [历史验收入口](../docs/history.md)。

Java 和 Agent 已启动、测试账号和商户已准备后，可以运行 `evals/run_live_eval.py --runs 3`。该评测使用 Cookie / CSRF 与 `EVAL_MERCHANT_ID`，需显式配置供应商，可能产生费用；历史评测不能替代当前版本回归。报告不保存提问、回答、JWT 或订单内容。真实模型故障演练、普通业务压测和模型评测应分别报告。
