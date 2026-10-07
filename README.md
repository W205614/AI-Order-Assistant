# 多商户 AI 点餐助手

个人可维护的多商户演示系统：平台开通商户，每商户一店，顾客选店后点餐，商家手动制作和配送。支付与退款均为模拟记录，不连接真实资金。

Java 控制权限、报价、库存、订单状态和审计，Python 负责自然语言理解与工具编排。未配置模型、模型断网或 Agent 停止时，页面选菜、确认、模拟支付和订单操作仍可使用。

## 快速启动

需要 Docker Desktop（Linux 容器）和支持 `!reset` / `!override` 的 Docker Compose v2。Windows 在项目根目录运行：

```powershell
.\start.ps1 -Docker -Build
```

启动脚本生成本地 `.env`，保留已有密钥，先备份已有数据库，再创建仅限业务数据库的应用账号，启动网关、Agent、MySQL、Redis、每日备份和 Prometheus 六个服务，等待应用健康检查。平台管理员账号和随机密码位于本地 `.env` 的 `PLATFORM_ADMIN_USERNAME` / `PLATFORM_ADMIN_PASSWORD`，不要提交或分享该文件。

- 顾客：[http://localhost:9090/chat/](http://localhost:9090/chat/)
- 商户：[http://localhost:9090/admin/](http://localhost:9090/admin/)
- 平台：[http://localhost:9090/platform/](http://localhost:9090/platform/)

本地演示种子默认开启：顾客 `demo / 123456`，原演示商户老板 `admin / admin123`。这些弱密码仅用于本机演示。平台管理员与老板是不同账号。平台可开通新商户及老板；老板可增设、停用和重置店员账号。

无需模型密钥即可启动。需要自然语言推荐时，在 `.env` 设置 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` 后重新运行启动脚本。精确菜名的购物车 Router 与静态 FAQ 不需要模型。FAQ 是关键词检索，不能称作向量 RAG。

Linux 或 CI 可复制 `.env.example` 并填写随机密钥，然后运行 `docker compose -f docker-compose.yml -f docker-compose.operations.yml up --build -d --wait`。已有数据库升级前先备份，再创建 `ai_order_app` 数据库账号。账号创建逻辑见 `scripts/provision-db-user.ps1`；仅授予 `ai_order_assistant.*` 权限。全新数据库由 MySQL 容器自动创建该账号；从已有卷升级时须执行授权脚本。

## 使用流程

顾客登录后先选店，从菜单添加菜品，点击“去结算”，保存草稿并填写收货信息。核对报价后确认，十分钟内点击“模拟支付”；涨价会显示最新报价，需再次确认。订单进入制作前可取消，已模拟支付的订单显示模拟退款。

新店由平台开通。老板登录商户后台，设置营业时间和配送区域，添加菜单与库存，并创建店员账号。店员依次推进制作、配送和完成；老板可查看统计与操作记录。前端使用中文支付和角色提示，手机布局与跨商户临时约束也经过浏览器验证。

## 功能与边界

| 范围 | 实现 |
|---|---|
| 商户 | 平台开通/停用；老板设置营业时间、接单开关、配送区域白名单；每商户一店 |
| 权限 | CUSTOMER、OWNER、STAFF、PLATFORM_ADMIN；员工仅处理本店订单，老板管理本店菜单/库存/人员 |
| 数据隔离 | 菜品、草稿、订单、流水、事件、临时约束及缓存按商户隔离；后端重新核验资源归属 |
| 草稿 | 用户每店最多一份活动草稿，5 分钟有效；更新和确认提交 `expectedVersion` |
| 报价 | 菜品版本或价格变化返回 409 及最新草稿，需再次点击确认；过敏原变化重新执行硬校验 |
| 幂等 | 确认键绑定用户、商户、草稿、版本与收货信息；跨请求复用返回冲突 |
| 模拟交易 | 确认预留库存 → 10 分钟待模拟支付 → 商家待处理 → 制作 → 配送 → 完成 |
| 补偿 | 制作前可取消；未支付超时关闭；取消/超时仅回补一次，已模拟支付则写入模拟退款 |
| 库存 | 独立增减接口、库存版本、幂等键和流水；菜单编辑不能覆盖并发库存扣减 |
| 安全点餐 | 长期偏好归用户；临时过敏约束归用户与商户；未核验或冲突菜品禁止下单 |
| 通知 | 订单事件持久化，SSE 心跳、连接限制及重连补读；实时队列有上限，页面列表为状态依据 |
| 经营 | 订单量、完成模拟金额、取消量；催单冷却 60 秒；持久操作审计 |

状态值：`0` 待模拟支付，`1` 商家待处理，`2` 制作中，`3` 配送中，`4` 完成，`5` 取消，`6` 支付超时。完成金额仅统计 `SIMULATED_PAID` 且状态 `4` 的订单。旧订单的金额未计为模拟支付收入。

## 结构

```text
浏览器：原生 JavaScript 模块 / HttpOnly 会话 Cookie
   │ 商户选择、CSRF、普通业务接口
   ▼
Java 21 / Spring Boot 4.0.8 / Spring Security / JDBC
   ├─ AuthService、MerchantService：身份与商户权限
   ├─ MenuService、DraftService：菜单、版本与安全校验
   ├─ OrderTransactionService、InventoryService：交易与补偿
   ├─ OrderQueryService、AuditService、OrderStatusEventBroker
   ├─ MySQL / Flyway：订单、库存、幂等、审计、事件
   └─ /chat → 固定身份、商户和截止时间 → Python Agent
Python 3.13 / FastAPI / LangGraph
   ├─ 确定性 Router / 静态 FAQ
   ├─ 模型工具白名单 / 不可信文本边界
   └─ 回调 Java：商户不可由模型修改；确认与取消需用户点击
Redis：聊天限流、模型额度与商户菜单元数据缓存；单独开发可使用进程内缓存
```

`OrderService` 保留兼容门面，业务实现按服务拆分，无需更换 ORM。业务仍为单 Java 应用和单 Agent，便于个人开发。商户是业务隔离单位，不是独立数据库。

## 认证与公网配置

浏览器会话使用 `HttpOnly` Cookie，不向页面返回 JWT，不把凭证存入 Web Storage。JWT 校验 HS256、签发方、受众、`exp/iat/nbf/jti`，并检查账号状态和 token 版本。退出持久失效当前凭证；改密码、停用账户失效该账户的旧凭证。Cookie 写请求需要 `X-XSRF-TOKEN`，登录与注册也受 CSRF 及数据库限流保护。

公网必须设置域名、关闭演示种子、修改原演示弱密码、启用 HTTPS：

```powershell
# 在本地 .env 设置 APP_DOMAIN、DEMO_SEED_ENABLED=false、COOKIE_SECURE=true
docker compose -f docker-compose.yml -f docker-compose.public.yml up -d --wait
```

Caddy 自动申请证书，需真实可解析域名和开放 80/443；网关、Agent、MySQL、Redis不单独暴露公网端口。安全启动检查阻止 Secure 模式下启用演示种子或保留已启用的演示弱密码。容器使用非 root，应用文件系统只读。数据库应用账号仅限业务 schema，但为了 Flyway 迁移仍含 schema DDL 权限；生产规模扩大后应再拆迁移账号与运行账号。

公网 TLS 和证书签发必须在实际域名环境另行验证。本机验收不能证明公网配置已上线。扫描见 `scripts/scan-images.ps1` 和 `.github/workflows/security.yml`，修复已有修复版本的 HIGH/CRITICAL；未发布修复的漏洞需审阅报告，不能把扫描通过等同于没有漏洞。

## AI 预算与降级

请求全程预算 35 秒，网关等待 40 秒。单 Agent 进程最多同时 8 个请求，每商户最多 2 个。超额返回 429；超时返回 504；Agent 不可用返回 503；业务点餐仍可使用。每次模型调用及工具执行前检查截止时间，内部 Java 回调也核验截止时间。

默认输出上限 1024 token，每请求预算 48000 个保守预算单位、输入最多 24000 UTF-8 字节，每商户每天保守预留 2000000 单位。输入字节与输出 token 的合计是偏保守的估算，不能当作提供商实际计费 token。多轮累计计入同一请求额度；首次模型调用预留整轮商户额度，失败也不返还。配置见 `agent-service/app/config.py`。内存并发限制只适用于单进程；增加 workers/副本前需迁移为分布式准入机制。

模型与菜单/FAQ/历史文本都不能改变可信商户上下文。取消真实订单工具仅返回待确认操作；页面点击后调用 Java。提供商返回 usage 时，响应提供累计输入/输出 token 和调用数；保守预算与实际计费 token 分开记录，缺少账单时不推算金额。普通业务压测不调用模型。

## 验证

```powershell
node scripts/check-static-js.mjs
cd java-gateway; mvn test; cd ..
cd agent-service; python -m unittest discover -s tests -v; python -m compileall -q app tests evals; cd ..
.\scripts\smoke-compose.ps1 -LeaveRunning
.\scripts\run-isolated-k6.ps1 -Vus 50 -Duration 10m
```

Python 请使用安装了 `agent-service/requirements.txt` 的虚拟环境。Java 集成测试必须有 Docker，真实 MySQL 测试不允许跳过。压力脚本自行启动 `ai-order-perf` 隔离环境，准备 50 个独立账号，业务场景包括菜单、草稿、重复确认、模拟支付、重复取消与列表查询；不会读写本地演示数据库。

压测门槛：查询 P95 <500ms、交易写 P95 <1s、非预期错误率 <1%，所有业务一致性断言通过。脚本固定场景标签，避免订单 ID 产生高基数指标。压测报告须记录机器、提交、数据规模及模型配置；达标结果仅代表被测环境和场景，不能推算最大 QPS。

AI 压测单独使用 `load/k6-ai.js`，默认测 Router；真实模型需显式设置 `REAL_MODEL=true` 并配置供应商，会产生费用。历史九月评测保留在 `docs/verification/2026-09-29`，与当前版本验收分开阅读。

本次实现与实测记录见 [2026-10-07 验收报告](docs/verification/2026-10-07/验收报告.md)。Java 49 项、Python 58 项测试通过，真实 MySQL/Flyway 集成测试 33 项零跳过；50 个新账号十分钟普通业务查询 P95 69.48ms、写接口 P95 200.22ms，仅对应报告中的环境与场景。双商户权限、实时 SSE、停用与登录限流可通过 `scripts/test-roles-and-isolation.py` 在独立验收环境复查；脚本会创建测试商户与人员。

GitHub [功能 CI](https://github.com/W205614/AI-Order-Assistant/actions/runs/37634055481) 和[依赖/镜像安全门槛](https://github.com/W205614/AI-Order-Assistant/actions/runs/37634055999)均通过，首次验证提交为 `f19bc9a`。随后 CI 暴露的一处浏览器刷新同步断言已修正；最终提交状态可在 [GitHub Actions](https://github.com/W205614/AI-Order-Assistant/actions) 查看。

真实浏览器回归脚本 `scripts/test-browser.cjs` 仅允许隔离端口 19090/19092，会创建测试商户和订单；依赖 Playwright 1.62.1 与 Chromium/Edge，GitHub CI 自动安装。可在隔离环境运行：

```powershell
node scripts/test-browser.cjs --base-url http://127.0.0.1:19092 --playwright-module <playwright模块路径> --browser-path <浏览器exe路径>
```

## 备份与运维

```powershell
.\scripts\backup-db.ps1
# 恢复到独立临时 MySQL，校验 SHA256、归属、库存与订单，不覆盖现有环境
.\scripts\restore-db.ps1 -BackupFile .\backups\orders-时间戳.sql
docker compose -f docker-compose.yml -f docker-compose.operations.yml up -d --wait
```

运维覆盖每日备份、保留最近七份、Prometheus 网关连接池/HTTP/Agent 指标和基础告警。Prometheus 仅绑定本机 `19091`，鉴权密钥运行时注入。备份卷含个人信息，须限制宿主机访问并另存异机副本；同机卷只能提供恢复演练，不能覆盖整机损坏。没有配置外部告警通知渠道。

需要精确比较源库和恢复库时，先停止写流量，备份后运行恢复脚本的 `-SourceProject` 参数。不要运行 `docker compose down -v` 删除承载真实演示数据的卷。升级前启动脚本自动备份；跨版本回滚需恢复对应旧备份到独立库验收后再切换，不能只换旧镜像直接读取新结构。

第一版后置真实支付、自动入驻、多门店、优惠券、骑手调度和平台结算。合理定位是具有企业工程基础、可部署和可验证的多商户 AI 点餐演示系统。
