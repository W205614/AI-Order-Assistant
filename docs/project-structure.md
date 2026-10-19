# 项目结构与阅读顺序

先按 [README](../README.md#快速开始) 启动并完成一笔自提订单，再沿着页面、接口、业务服务阅读源码。日常启动只需根目录的 `start.ps1` 或 `docker-compose.yml`。

## 目录职责

| 目录 | 放什么 | 初次阅读重点 |
|---|---|---|
| `java-gateway/` | Java 应用、静态页面、迁移、Java 测试 | 业务规则与数据写入的最终执行方 |
| `agent-service/` | FastAPI、Agent 编排、工具、FAQ、Python 测试与评测 | 理解需求、调用 Java，不直接修改业务数据库 |
| `deploy/` | 场景覆盖配置、Caddy、Prometheus、告警规则、自动备份 | 完整部署配置在根目录，按需叠加覆盖文件 |
| `scripts/` | 初始化、备份恢复、巡检、安全扫描与系统验证 | 按 [脚本索引](../scripts/README.md) 选择，不必全部执行 |
| `load/` | k6 普通业务与 AI 压测场景 | 需要性能验证时再读；不是启动依赖 |
| `docs/` | 业务说明、运维、阅读指南、截图与历史入口 | 先读当前说明，按需追溯历史 |
| `.github/` | CI、安全扫描和手动模型评测 | 最终提交的自动化检查 |

前端是 Java 提供的原生 HTML/CSS/JavaScript，位于 `java-gateway/src/main/resources/static/`，无需单独安装前端依赖。CI 中的 Node 用于脚本检查和浏览器验证。

## Java：从页面到交易

`java-gateway/src/main/` 按以下职责组织：

| 路径 | 职责 |
|---|---|
| `java/com/ai/assistant/AssistantGatewayApplication.java` | Spring Boot 启动入口 |
| `java/com/ai/assistant/controller/` | HTTP 接口与页面路由 |
| `java/com/ai/assistant/service/` | 购物车、报价、结算、履约、退款和安全规则 |
| `java/com/ai/assistant/security/` | 登录、JWT、身份与商户上下文 |
| `java/com/ai/assistant/client/` | 调用 Python Agent |
| `java/com/ai/assistant/config/` | 初始化、部署校验与框架配置 |
| `java/com/ai/assistant/dto/`、`vo/`、`model/`、`properties/` | 请求、响应、数据对象与配置绑定 |
| `resources/static/` | 顾客、商家、平台、骑手与原单店页面 |
| `java/db/migration/`、`resources/db/migration/` | Flyway 的 Java 与 SQL 迁移，两个目录共同组成迁移历史 |
| `resources/application.properties` | 应用默认配置，与环境变量配合使用 |

建议先跟踪校园页的一次加购、报价和下单：

1. [campus/index.html](../java-gateway/src/main/resources/static/campus/index.html) 和 [campus-customer.js](../java-gateway/src/main/resources/static/assets/campus-customer.js)：找到按钮及请求，公共请求封装在 [api.js](../java-gateway/src/main/resources/static/assets/api.js)。
2. [CampusCommerceController.java](../java-gateway/src/main/java/com/ai/assistant/controller/CampusCommerceController.java)：按接口路径找到业务入口。
3. [CommerceCartService.java](../java-gateway/src/main/java/com/ai/assistant/service/CommerceCartService.java) → [CommerceQuoteService.java](../java-gateway/src/main/java/com/ai/assistant/service/CommerceQuoteService.java) → [CommerceCheckoutService.java](../java-gateway/src/main/java/com/ai/assistant/service/CommerceCheckoutService.java)：分别理解购物车、报价和确认结算。
4. 再看同目录的 `CommerceFulfillmentService`、`CommerceAftersaleService` 和 `CommerceCouponService`，理解配送、自提、退款和优惠。
5. 对照 [CampusCommerceMySqlIntegrationTest.java](../java-gateway/src/test/java/com/ai/assistant/service/CampusCommerceMySqlIntegrationTest.java) 阅读交易边界，再查迁移中的表结构。

`/chat/` 仍是可用的原单店入口，其 `ChatController`、`OrderController`、`DraftService` 等有独立职责，不能因为校园流程存在就当作废弃代码删除。已有 Flyway 迁移同样需要保留，不能重排或合并成一份新初始化脚本。

## Python：从对话到业务工具

先读 [Agent 说明](../agent-service/README.md)，再按以下顺序看源码：

1. [app/main.py](../agent-service/app/main.py)：FastAPI 入口、内部认证、请求准入与两个聊天协议。
2. [app/agent/campus.py](../agent-service/app/agent/campus.py)：校园对话与跨店工具编排；对照 Java 的 [CampusAssistantController.java](../java-gateway/src/main/java/com/ai/assistant/controller/CampusAssistantController.java)。
3. [app/gateway/java_client.py](../agent-service/app/gateway/java_client.py)：工具如何回调 Java；权限、价格、库存和交易仍由 Java 校验。
4. [app/agent/graph.py](../agent-service/app/agent/graph.py)、`tools.py`、`cart_router.py` 和 `app/rag/`：原单店编排、工具、确定性购物车路由与 FAQ。
5. `app/config.py`、`budget.py`、`metrics.py`、`operations.py`：配置、额度、日志指标和运行状态。

普通页面操作通过 Java 完成；AI 对话经 Java 调用 Python，Python 按需回调 Java 工具。AI 可以准备购物车或草稿，确认下单和模拟支付仍需顾客在页面操作。模型调用不应放进数据库事务内。

`tests/` 是确定性回归；`evals/` 是 FAQ 和真实模型评测，真实提供商调用需显式配置且可能收费。`requirements.txt` 声明依赖，`requirements.lock.txt` 锁定版本，两份需要一起使用；`environment.yml` 与 `run-*.bat/sh` 是可选 Conda 开发入口。

## 配置与本地产物

| 路径 | 用途与处理方式 |
|---|---|
| `.env.example`、`agent-service/.env.example` | 可提交的配置模板；后者用于单独开发 Agent |
| `.env`、`agent-service/.env` | 本机私密配置，已忽略，不提交 |
| `backups/` | 数据库备份、校验文件和备份健康状态；不是缓存，不能随目录清理删除 |
| `.local/` | 私有夹具、临时报告和调试材料；先核实用途，不作为源码入口 |
| `logs/`、`agent-service/metrics/` | 本机日志与审计产物，已忽略 |
| `java-gateway/target/`、`__pycache__/` | 构建与解释器产物，已忽略，可在停止相关本机任务后重建 |
| `load/results/`、`agent-service/evals/results/`、`security-reports/` | 压测、评测与扫描输出，已忽略 |

提交业务变化时，只运行改动直接影响的检查，完整回归查看该提交的 GitHub Actions。[运维指南](operations.md) 说明隔离环境、备份恢复和验证命令。[历史验收入口](history.md) 保留旧报告的固定提交链接，历史成功结果不能代替当前版本检查。
