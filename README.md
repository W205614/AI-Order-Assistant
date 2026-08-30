# AI 点餐助手 🍜🤖

一个可实际运行的 AI 点餐项目：用户通过自然语言完成菜单查询、个性化推荐、购物车修改和订单操作；LLM 只负责理解与编排，价格、权限、过敏原校验、订单创建和状态流转全部由 Java 后端控制。

项目采用 **Spring Boot + MySQL + FastAPI + LangGraph**，支持 OpenAI 兼容接口（已使用 DeepSeek 做过真实端到端回归）。

## 核心能力

- 自然语言点餐：看菜单、推荐、选菜、查单、取消、催单和 FAQ。
- 安全确认下单：AI 只能创建 5 分钟有效的订单草稿；用户必须点击页面上的“确认下单”按钮，才会创建真实订单。
- 多轮购物车：支持继续加菜、删菜、改数量、改备注和放弃购物车；每个用户只保留一个活动草稿，旧确认按钮自动失效。
- 个性化偏好：用户可明确保存过敏原、忌口、饮食目标和单餐预算；临时要求不会被静默写入长期偏好。
- 过敏原硬拦截：菜单展示过敏原，草稿、草稿修改和直接下单入口都会在后端强制校验，不能依赖模型绕过。
- 菜名消歧：精确匹配优先；模糊结果不唯一时要求用户明确选择，不会擅自下单。
- 事务与幂等：确认单加行锁，下单使用 `Idempotency-Key`，用户订单序号通过数据库原子分配。
- 版本化迁移：Flyway 记录结构版本；已有非空 MySQL 库会先建立基线再迁移，保留历史用户、订单、库存与草稿。
- 可扩展查询：用户端与管理端订单列表使用稳定分页（默认 20、最大 100），明细批量加载避免 N+1 查询。
- 权限隔离：用户与管理员使用不同 JWT；订单、草稿和偏好均按用户隔离。
- 严格状态机：已下单 → 制作中 → 配送中 → 已送达，不允许跳步或回退；支持取消和超时状态。
- 管理端：订单筛选与自动刷新、菜品增删改/上下架、过敏原维护。
- 可观测与防护：端到端 `traceId`、Agent 内部共享密钥、按用户限流、脱敏审计事件与 P50/P95 延迟指标；Docker 使用 Redis 原子滑动窗口，本地默认内存实现。
- 工程化：Docker Compose、GitHub Actions、MySQL Testcontainers 集成测试、Java/Python 自动化测试、手动真实模型评测与隔离 k6 负载脚本。

> 当前项目没有接入真实支付。取消订单只改变订单状态，不会发生扣款、退款或向商家发送真实催单通知。

## 系统架构

```text
浏览器（用户端 / 管理端）
          │ JWT
          ▼
Spring Boot 网关 :9090
  ├─ 用户、菜单、偏好、草稿、订单、管理端 API
  ├─ MySQL：事务、价格、权限、过敏原、状态机
  └─ /chat 携带用户身份和内部密钥转发
          │
          ▼
FastAPI + LangGraph Agent :8800
  ├─ LLM Function Calling
  ├─ 菜单 / 偏好 / 购物车 / 订单工具
  └─ 本地 FAQ 检索
          │ 携带原用户 JWT 回调
          └────────────────────► Spring Boot API
```

关键边界：LLM 不直接写数据库，也不能直接确认订单；所有业务工具都回调 Java API，后端重新读取菜单价格并执行最终校验。

## 技术栈

- Java：JDK 21、Spring Boot 3.2、Spring JDBC、MySQL 8、BCrypt、HS256 JWT。
- Agent：Python 3.13、FastAPI、LangGraph、OpenAI SDK、httpx。
- RAG：本地关键词与 bigram 评分，无需向量数据库或 Embedding Key。
- 前端：原生 HTML/CSS/JavaScript，无构建依赖。
- 测试：JUnit 5、Mockito、Testcontainers MySQL、Python `unittest`、真实 LLM 工具调用评测、k6。

## 项目结构

```text
AI-Order-Assistant/
├── java-gateway/
│   ├── src/main/java/com/ai/assistant/
│   │   ├── controller/       # 用户、偏好、订单、聊天、管理端 API
│   │   ├── service/          # 订单、草稿、过敏安全、用户偏好
│   │   ├── security/         # JWT、拦截器、用户上下文
│   │   └── config/           # 数据迁移、异常处理、密钥校验
│   ├── src/main/resources/
│   │   ├── db/migration/    # Flyway 版本化迁移
│   │   ├── application.properties # 安全的 Flyway/初始化默认值
│   │   ├── application.example.yml
│   │   └── static/           # /chat 与 /admin
│   └── src/test/             # Java 回归测试
├── agent-service/
│   ├── app/                  # FastAPI、LangGraph、工具、RAG、指标
│   ├── tests/                # 确定性 Agent/运行时测试
│   └── evals/                # 真实模型评测集与执行器
├── load/                      # k6 负载脚本（结果不入库）
├── scripts/                   # Compose 冒烟脚本
├── docker-compose.yml
└── .github/workflows/         # CI 与手动真实模型评测
```

## 本地启动

### 1. 前置条件

- JDK 21 与 Maven
- MySQL 8
- Python 3.13（conda 或 venv 均可）
- 一个 OpenAI 兼容的 LLM API Key

### 2. 配置 Java 网关

复制模板；生成的 `application.yml` 已被 `.gitignore` 排除：

```bash
cd java-gateway
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

配置以下环境变量，或在本地 `application.yml` 中替换对应占位符：

| 环境变量 | 说明 |
|---|---|
| `DB_URL` | MySQL JDBC 地址，未设置时使用本机默认地址 |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号和密码 |
| `AI_INTERNAL_API_KEY` | 网关调用 Agent 的共享密钥，至少 32 位 |
| `JWT_USER_SECRET` | 用户 JWT 密钥，至少 32 位 |
| `JWT_ADMIN_SECRET` | 管理员 JWT 密钥，至少 32 位，且不能与用户密钥相同 |

密钥缺失、过短或用户/管理员密钥相同时，网关会拒绝启动。

### 3. 配置并启动 Agent

```bash
cd agent-service
conda env create -f environment.yml
cp .env.example .env
# 编辑 .env：填写 LLM_API_KEY、LLM_BASE_URL、LLM_MODEL
# AGENT_INTERNAL_API_KEY 必须与 Java 的 AI_INTERNAL_API_KEY 完全一致
./run-agent.sh
```

Windows 使用 `run-agent.bat`。也可以执行：

```bash
pip install -r requirements.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8800
```

### 4. 启动 Java

```bash
cd java-gateway
mvn spring-boot:run
```

首次启动会由 Flyway 创建或升级数据库结构。迁移不会删除旧订单、库存、草稿或用户数据；若库在引入 Flyway 前已非空，会先记录基线 `0`，再执行后续迁移。

### 5. 页面入口

| 页面 | 地址 | 登录 |
|---|---|---|
| 用户端 | http://localhost:9090/chat/ | 自行注册；仅开启演示种子时为 `demo / 123456` |
| 管理端 | http://localhost:9090/admin/ | 仅开启演示种子时为 `admin / admin123` |
| Agent 健康检查 | http://localhost:8800/health | 无 |

演示菜单和账号默认不创建。仅在本地/Compose 演示环境设置 `DEMO_SEED_ENABLED=true` 才会创建缺失的演示数据；生产环境保持默认 `false`。

## 界面与演示体验

- 页面继续由 Java 网关以原生静态资源托管，不引入前端框架、CDN、外部字体或菜品图片；共享视觉 Token 位于 `static/assets/ui.css`。
- 首页直接展示已实现的 AI 对话、用户确认下单和订单状态追踪能力。用户端在桌面宽度下将菜单和偏好展示为对话旁的上下文面板，在平板和手机宽度下保持单列点餐流程。
- 菜单卡片会展示分类、价格、过敏原标记、售罄和已选状态；待确认卡片明确提示“需由用户确认”，真实订单仍只由原有确认接口创建。
- 管理端统一展示订单概览、筛选、状态标签和菜品编辑入口；加载、空数据、错误提示、键盘焦点和减少动画偏好均由界面层处理，不改变业务接口。

## Docker Compose

```bash
cp .env.example .env
# 填写数据库密码、LLM Key、内部密钥以及两个不同的 JWT 密钥
docker compose up --build
```

访问 http://localhost:9090/。MySQL 数据保存在 `mysql-data` volume。Compose 会将 Agent 健康检查仅绑定到本机回环地址 `http://localhost:8800/health`；用户端和管理端统一经由网关的 `9090` 端口访问。

Docker Desktop 已启动且根目录 `.env` 已配置时，可运行可重复的 Compose 冒烟测试：

```powershell
.\scripts\smoke-compose.ps1
```

该脚本会校验 Agent 与网关健康状态，并用演示账号覆盖登录、草稿创建、确认下单和重复确认幂等；结束时停止本项目容器，但保留命名数据卷。

Compose 中 Agent 使用 `RATE_LIMIT_BACKEND=redis`、`REDIS_URL=redis://redis:6379/0`，以 Redis 服务器时间执行按认证用户、每分钟 30 次的原子滑动窗口。Redis 不可用时请求会失败关闭为通用 503，不会静默回退到单实例内存限流。非 Docker 本地启动默认使用 `RATE_LIMIT_BACKEND=memory`；可通过 `REDIS_URL` 与 `AGENT_RATE_LIMIT_KEY_PREFIX` 显式切换。

Windows 上可直接运行 `start.bat`，或在 PowerShell 执行 `./start.ps1`：默认复用已有的 MySQL、`agent-service/.env`、Java `application.yml`，前台启动 Agent 与 Java 网关（用户端前端由网关托管）。服务就绪后脚本会保持运行；按 `Ctrl+C` 会同时停止这两个由脚本启动的进程。日志写入 `logs/`。

如确需让本地服务脱离终端运行，使用 `./start.ps1 -Detached`。

若上一次服务异常退出或更新代码后需要替换旧进程，使用 `./start.ps1 -Restart`；该选项会尝试结束监听本项目 Agent 端口 `8800` 和网关端口 `9090` 的进程。若旧 Agent 属于其他受保护会话而无法结束，脚本会自动使用一个空闲的 `8801-8899` 端口，并让新网关指向它；网关端口 `9090` 无法释放时仍会明确报错。

如需以 Docker 运行全部依赖，显式执行 `./start.ps1 -Docker -Build`。Docker 模式会优先复用 `agent-service/.env` 自动生成根目录 Compose 配置与本地密钥；`-Foreground` 用于在当前窗口查看容器日志。

## 推荐使用流程

1. 在“饮食偏好”中设置过敏原、忌口、饮食目标和预算。
2. 对 AI 说“结合我的偏好推荐三道菜”，或在菜单中多选菜品后点击“发送所选”。
3. 菜单多选会由 Agent 的确定性草稿节点创建购物车，再由 AI 生成确认说明；自然语言点餐则由 AI 生成草稿。核对确认卡片中的菜品、数量和金额。
4. 继续用自然语言加菜、删菜或改数量；页面只允许确认最新版本。
5. 点击“确认下单”创建订单。
6. 在“我的订单”查看、催单或取消；管理端推进制作和配送状态。

## 下单安全与一致性

- 草稿不会写入 `orders`，只有 `/order/drafts/{draftId}/confirm` 会创建订单。
- 确认时再次读取最新菜品名称、价格和上下架状态，并在同一事务中原子扣减库存，防止使用过期报价或并发超卖。
- 草稿确认使用数据库行锁；重复点击或网络重试由幂等键和唯一约束兜底。
- 用户订单号由 `user_order_sequence` 原子分配，不使用 `MAX + 1`。
- 新草稿会使旧草稿失效；草稿过期、放弃或已确认后不能再次修改/确认。
- 用户保存的过敏原在所有下单入口统一校验。
- 菜单多选会将结构化菜品和数量交给 Agent：确定性节点创建草稿保证“发送所选”后稳定出现确认按钮，LLM 只负责生成说明和提醒，不控制交易动作。

## 自动化测试与评测

Java 全量测试：

```bash
cd java-gateway
mvn test
```

Agent 确定性测试：

```bash
cd agent-service
run-tests.bat
```

启动 Java 和 Agent 后，执行真实 LLM 工具调用评测：

```bash
cd agent-service
conda run -n ai-order-agent python evals/run_live_eval.py --runs 3
```

评测包含 26 个数据驱动场景，覆盖菜单与偏好、订单草稿、多轮购物车、草稿持久化、提示注入、越权尝试和“文本不能直接确认下单”等关键路径。每轮断言工具序列、待确认草稿、草稿内容、偏好变更和“未误创建真实订单”；测试产生的草稿会自动取消、偏好会恢复。默认以 2.1 秒间隔发送同一评测用户的请求，避免干扰生产限流。结果仅记录场景 ID、工具状态、耗时和失败分类，写入被忽略的 JSONL 文件，不保存原始对话、JWT 或订单内容。

其中，大数量请求可以显式记录为“工具已被后端拒绝”：评测保留该失败工具事件，同时继续断言不存在待确认草稿或真实订单。草稿取消会返回 `status=cancelled` 供前端清除确认卡片；“确认下单”文本则只验证它不能创建、修改或取消草稿，真实订单仍只能由页面确认按钮产生。

Java 测试包含真实 MySQL 的 Testcontainers 集成用例：草稿确认幂等、用户隔离、过敏原拦截、严格状态机和并发库存竞争。无 Docker 守护进程的本机会自动跳过该类测试；GitHub Actions Linux Runner 会执行它们。

在 Docker Desktop 可用的本地环境，已按上述命令完成 Compose 冷启动与冒烟验证，并额外验证真实模型菜单查询、前端“发送所选”到“确认下单”的闭环，以及订单 SSE 状态推送。上述验证用于功能正确性，不构成性能指标。

普通 GitHub Actions 会运行 Java 测试、Python 编译和 Agent 确定性测试，并用 Node 内置语法检查用户端/管理端内联脚本、用非秘密占位环境校验两套 Compose 配置。真实模型评测由 `Live Agent Evaluation` 手动工作流运行，需在仓库 Secret 中配置 `LLM_API_KEY`、`AGENT_INTERNAL_API_KEY`、两个 JWT 密钥和 MySQL 密码；执行结果以脱敏 Artifact 导出，不会在 push/PR 中消耗模型额度。

## 请求追踪与审计

- 浏览器可选传入 `X-Request-Id`；网关会校验或生成该值，并在聊天响应的 `traceId` 字段返回。
- 相同 trace ID 会随网关 → Agent → Agent 工具回调 Java 的链路传递，便于定位一次业务操作。
- Agent 仅记录模型名、图迭代次数、工具名/状态、阶段耗时及错误分类；不会记录消息正文、模型回复、JWT、密码、用户 ID 或订单明细。
- `GET /stats` 是 Agent 的内部运维接口，必须携带 `X-Agent-Internal-Key`；提供工具成功率、请求成功率、P50/P95/最大延迟及错误分类汇总。

## 可靠性与错误处理

- LLM 调用具有显式超时与有限退避重试；仅模型网络超时、限流和 5xx 会重试。
- 创建/修改/取消草稿等有副作用工具从不自动重试；真实下单继续由后端事务与 `Idempotency-Key` 保证幂等。
- 模型、Java 网络、Java 超时和业务拒绝会被分类为审计指标；客户端只收到安全的可恢复提示，不会看到供应商错误或内部堆栈。

## 响应性能设计

- Java 网关到 Agent、Agent 到 Java 后端及 Agent 到 LLM 均复用进程内 HTTP 连接，避免每次对话重新建立 TCP/TLS 连接。
- 同一轮同时发出的菜单、偏好和订单等独立只读查询会并行执行；订单草稿、偏好更新、取消等有副作用的工具始终串行，保障状态一致性。
- 端到端耗时仍主要受远端 LLM 推理与网络质量影响。需要进一步改善首字节体验时，可在不改变上述一致性边界的前提下接入 SSE 流式输出。

### 可复现负载测试

使用隔离 Compose 与 `load/k6-order-flow.js` 运行：

```powershell
.\scripts\run-isolated-k6.ps1 -ReadVus 3 -ReadDuration 30s
# 仅在可丢弃环境验证草稿和重复确认幂等：
.\scripts\run-isolated-k6.ps1 -ReadVus 1 -ReadDuration 10s -WriteVus 1 -RunWrites -ConfirmOrders
```

脚本启动项目名 `ai-order-perf` 的独立 MySQL/Redis 卷以及 `19090`/`18800` 端口，绝不复用默认 Compose 的演示数据。默认只压测只读聊天；写入与确认均需显式开关。结果 JSON 写入被忽略的 `load/results/`，脚本结束时只销毁隔离项目的容器和卷。仓库不包含任何未复现的性能结论。

## 订单状态提醒

- 管理端将订单更新为制作中、配送中、送达、取消或超时时，网关会通过受 JWT 保护的 SSE 连接，仅向该订单所属且当前在线的用户页面推送状态事件。
- 用户端会显示提示，并在对话区域加入状态反馈；这不是额外的 LLM 调用，不增加模型响应等待或 API 成本。
- 离线期间不依赖内存事件补发，订单状态仍以数据库为准；用户重新登录后可在“我的订单”读取最新状态。

## 主要 API

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/register`、`/auth/login` | 用户注册与登录 | 公开 |
| POST | `/admin/login` | 管理员登录 | 公开 |
| GET | `/dish/list` | 菜单、售罄状态与过敏原 | 用户 |
| GET/PUT | `/user/preferences` | 读取/保存饮食偏好 | 用户 |
| GET | `/order/drafts/pending` | 恢复当前待确认购物车 | 用户 |
| POST | `/order/drafts` | 创建草稿，不创建订单 | 用户 |
| PUT/DELETE | `/order/drafts/{draftId}` | 修改/放弃草稿 | 用户 |
| POST | `/order/drafts/{draftId}/confirm` | 显式确认并下单，需 `Idempotency-Key` | 用户 |
| POST | `/order/place` | 兼容直接下单入口，需 `Idempotency-Key` | 用户 |
| GET | `/order/list?page=1&size=20`、`/order/{seq}` | 本人分页订单列表与详情；`size` 最大 100 | 用户 |
| POST | `/order/{seq}/cancel`、`/remind` | 取消与催单 | 用户 |
| GET | `/admin/orders?page=1&size=20` | 分页订单管理；状态/日期筛选后的统计独立于当前页 | 管理员 |
| POST | `/admin/orders/{id}/status` | 严格状态流转 | 管理员 |
| GET/POST/PUT/DELETE | `/admin/dishes...` | 菜品、上下架与过敏原管理 | 管理员 |
| GET | Agent `/stats` | 脱敏运行指标，需 `X-Agent-Internal-Key` | 内部 |

## 已知边界

- 没有真实支付、退款、商家通知或配送系统集成。
- 用户体系仍是账号密码模式，没有 OAuth、找回密码和刷新令牌。
- 菜品没有图片、营养成分和动态供应链数据。
- 已知过敏原来自菜单人工标注，不能替代专业医疗建议；实际餐饮系统还需要交叉污染提示和人工复核。
- 管理端使用 5 秒轮询；如需实时协作可在网关层继续接入 SSE 推送。
- FAQ 是轻量关键词检索，复杂知识场景可升级为带评测的向量 RAG。
- 生产环境仍需接入 HTTPS、云端密钥管理、数据库备份、集中日志、告警和分布式追踪后端；当前审计为本地轮转 JSONL，不替代集中观测平台。
- 负载脚本和真实模型评测已可复现，但本仓库不提交环境相关的运行结果；性能结论只能引用具体的测试报告、模型、时间与环境。

## 安全边界与设计决策

- **LLM 是不可信编排器**：用户、历史、FAQ 与工具自由文本都只能作为数据。系统提示词要求拒绝注入式越权请求；Agent 在调用工具前还会执行严格 JSON schema、长度、字符白名单和注入特征校验，避免只靠提示词防护。
- **后端是业务裁决者**：模型只能创建草稿，浏览器的显式确认才会下单；Java 后端重新读取价格、可售状态和过敏原，不接受模型提供的金额或权限结论。
- **库存防超卖**：仅在真实订单事务中使用 `UPDATE dish SET stock=stock-? WHERE id=? AND status=1 AND stock>=?` 原子扣减。影响行数为零即回滚，库存变化后失效菜单缓存；这不是额外 `version` 字段的乐观锁，而是更适合扣库存的条件更新。
- **高并发读菜单**：Compose 环境使用 Redis 缓存菜单分页结果，TTL 为 5 分钟；菜品管理和库存成功扣减都会失效缓存，TTL 仅作异常兜底。
- **一致性与重试**：草稿确认有行锁，订单用 `Idempotency-Key` 和唯一索引去重，用户订单号由数据库原子分配。幂等重试在扣库存前会二次检查，避免重复扣减。

## 相关资料

- [LangGraph](https://github.com/langchain-ai/langgraph)
- [FastAPI](https://fastapi.tiangolo.com/)
- [Spring Boot](https://spring.io/projects/spring-boot)
