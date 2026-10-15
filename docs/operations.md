# 部署、验证与运维指南

功能、架构及页面预览见 [项目 README](../README.md)。本文保留配置、隔离验证和恢复操作，具体验收数据以各版本报告为准。

## 启动与配置

根目录只保留一个默认 Compose 入口，其他文件按场景覆盖；所有命令均须先指定根文件，挂载路径相对根目录解析。

| 文件 | 使用场景 |
|---|---|
| `docker-compose.yml` | 默认六服务，包含备份与监控 |
| `deploy/compose/isolated.yml` | 隔离测试四服务，19090 端口，清空模型密钥 |
| `deploy/compose/public.yml` | 默认部署增加 Caddy，关闭网关直连并启用 HTTPS Cookie |
| `deploy/compose/model-eval.yml` | 在隔离配置后显式启用真实提供商，仅用于付费验收 |

`python scripts/check-compose.py` 仅验证上述配置与隔离边界，不启动容器或调用模型。隔离环境默认不启动备份和监控；即使显式启用 `operations` profile，备份也使用该测试项目的专属卷，监控不发布宿主端口。

Windows 默认使用 `.\start.ps1 -Build`。脚本创建或补全本地 `.env`，保留已有密钥，备份已有数据库，并配置仅限 `ai_order_assistant.*` 的应用账号。升级前应确认 Docker 可用且备份成功。

Linux 或 CI 可复制 `.env.example`，填写随机密钥后启动：

```bash
docker compose -p ai-order-assistant -f docker-compose.yml up --build -d --wait
```

全新数据库由 MySQL 容器创建应用账号；已有卷升级时须先备份，再参考 [provision-db-user.ps1](../scripts/provision-db-user.ps1) 创建/授权账号。不要对来源未知的旧结构直接开启 Flyway baseline。

| 配置 | 用途 |
|---|---|
| `MYSQL_ROOT_PASSWORD`、`DB_APP_PASSWORD` | 数据库管理与业务账号；不要复用公开测试值 |
| `JWT_USER_SECRET`、`JWT_ADMIN_SECRET`、`AGENT_INTERNAL_API_KEY` | 顾客/管理会话签名与内部调用认证 |
| `PLATFORM_ADMIN_USERNAME`、`PLATFORM_ADMIN_PASSWORD` | 平台管理账号，启动脚本生成本地密码 |
| `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` | 可选模型提供商；不配置密钥仍可普通点餐 |
| `DEMO_SEED_ENABLED` | 本地演示种子，公网须关闭 |
| `COOKIE_SECURE`、`APP_DOMAIN` | HTTPS Cookie 和公网域名 |

完整配置模板见 [.env.example](../.env.example) 和 [Agent 配置](../agent-service/app/config.py)。修改 `.env` 后重新运行 `.\start.ps1`；应用代码变化使用 `-Build` 重建。

使用相同项目名及配置文件查看服务、日志或停止服务：

```powershell
docker compose -p ai-order-assistant -f docker-compose.yml ps
docker compose -p ai-order-assistant -f docker-compose.yml logs --tail 100 gateway agent
docker compose -p ai-order-assistant -f docker-compose.yml stop
```

## 公网与认证

公网必须有可解析域名、开放 80/443、关闭演示种子、更换原演示账号弱密码并启用 HTTPS：

```powershell
# 在本地 .env 设置 APP_DOMAIN、DEMO_SEED_ENABLED=false、COOKIE_SECURE=true
docker compose -p ai-order-assistant -f docker-compose.yml -f deploy/compose/public.yml up -d --wait
```

Caddy 自动申请证书，并在默认六个服务之外增加代理服务。公网配置移除网关的直接端口映射；Agent、MySQL、Redis 不单独暴露公网端口，Prometheus 仍仅绑定本机。实际域名的 TLS 与证书签发须另行验证。

安全启动检查阻止 Secure 模式下启用演示种子或保留已启用的演示弱密码。应用容器使用非 root 和只读文件系统；数据库应用账号限业务 schema，但仍含 Flyway 所需的 DDL 权限，扩大部署规模前应拆分迁移与运行账号。

JWT 校验 HS256、签发方、受众、`exp/iat/nbf/jti`、账号状态和 token 版本。退出持久失效当前凭证，改密码及停用账户失效该账户旧凭证。Cookie 写请求和登录注册均受 CSRF 保护，登录注册还受数据库限流保护。

依赖与镜像扫描见 [security.yml](../.github/workflows/security.yml) 和 [scan-images.ps1](../scripts/scan-images.ps1)。门槛要求修复已有修复版本的 HIGH/CRITICAL；未发布修复的漏洞仍需审阅，扫描通过不表示不存在漏洞。

## AI 预算与降级

默认截止时间预算 35 秒，网关 HTTP 超时配置 40 秒，单 Agent 进程最多同时 8 个请求，每商户最多 2 个。并发容量或聊天频率超限返回 `429 / AI_CAPACITY`。原单店 `/chat` 的连接失败（含连接超时）返回 `503 / AI_UNAVAILABLE`，读取超时返回 `504 / AI_TIMEOUT`；校园 `/campus-assistant/chat` 将连接和读取超时均映射为 504，其他连接 I/O 故障映射为 503。Agent 明确返回 504 时也映射为 `AI_TIMEOUT`。模型及工具执行前检查截止时间，Java 内部回调也核验截止时间；预算和网络超时不等于对任意执行都提供精确的墙钟硬截止。

默认输出最多 1024 token。每次模型调用的序列化请求 UTF-8 字节数加预留输出 token，不得超过 24000 个预算单位；同一请求累计不得超过 48000 单位。每商户按 UTC 日期桶预留，默认每日上限 2000000 单位。输入字节与输出 token 合计是保守估算，不能作为实际计费 token。首次模型调用预留整轮请求额度，失败也不返还。提供商故障及模型调用中的预算/每日额度耗尽会转换为 `LLMError`，通常返回 HTTP 200、`outcome=degraded`；未转换为 `LLMError` 而冒泡到 Agent HTTP 入口的 `BudgetExceeded` 返回 504。部署通过 Redis 记录限流与每日额度；进程内并发准入仍仅适用于单进程。

提供商返回 usage 时，响应记录累计输入/输出 token 和调用数，缺少账单时不推算费用。真实订单取消工具仅返回待确认操作，用户点击后调用 Java。菜单、FAQ、模型文本及历史记录不能修改可信身份或商户。

## 验证与压测

README 给出基础验证命令。Java 真实 MySQL/Flyway 测试需要 Docker 且不允许跳过。Python 使用 Python 3.13 虚拟环境，并安装固定依赖：

```powershell
python -m pip install -r agent-service/requirements.txt -c agent-service/requirements.lock.txt
```

下列验收会创建账号、商户和订单，应使用独立项目与数据卷；`.github/compose-ci.env` 是公开测试配置，不能用于正式部署：

```powershell
docker compose --env-file .github/compose-ci.env -p ai-order-clean -f docker-compose.yml -f deploy/compose/isolated.yml up --build -d --wait
python scripts/test-functional-concurrency.py --base-url http://127.0.0.1:19090
python scripts/test-roles-and-isolation.py --base-url http://127.0.0.1:19090
node scripts/test-browser.cjs --base-url http://127.0.0.1:19090 --playwright-module <模块路径> --browser-path <浏览器exe路径>
python scripts/test-agent-faults.py --project ai-order-clean --base-url http://127.0.0.1:19090
```

浏览器脚本仅允许隔离端口 19090/19092，依赖 Playwright 1.62.1 和 Chromium/Edge；CI 自动安装浏览器。提供商故障脚本使用模拟响应，不调用付费真实模型。`smoke-compose.ps1 -LeaveRunning` 会启动默认部署并创建测试账户和订单，仅在接受写入该环境时使用。

可选普通业务压测：

```powershell
.\scripts\run-isolated-k6.ps1 -Vus 50 -Duration 10m
```

脚本自行启动 `ai-order-perf`，准备 50 个独立账号，检查菜单、草稿、重复确认、模拟支付、重复取消和列表；不使用本地演示数据库或模型。门槛为查询 P95 <500ms、交易写 P95 <1s、非预期错误率 <1%，同时要求业务一致性断言通过。标签固定，避免订单 ID 造成指标高基数。

压测报告须记录机器、提交、数据规模和模型配置，结果不能推算最大 QPS。AI 场景见 [k6-ai.js](../load/k6-ai.js)，默认测 Router；真实模型需要显式设置 `REAL_MODEL=true` 及供应商配置，会产生费用。

历史证据按版本阅读：[2026-09-29](verification/2026-09-29/)、[2026-10-07](verification/2026-10-07/验收报告.md)、[2026-10-09](verification/2026-10-09/状态码修复验证.md)、[2026-10-11](verification/2026-10-11/重构与部署验收.md)。各轮测试数量和环境有差异，不能合并成同一版本的结论。

## 备份恢复与清理

```powershell
.\scripts\backup-db.ps1
# 恢复到独立临时 MySQL，不覆盖正式数据库
.\scripts\restore-db.ps1 -BackupFile .\backups\orders-时间戳.sql
```

默认每日备份，通常保留最近七份；未通过校验的旧备份保留供人工检查。恢复脚本验证 SHA-256、SQL 导入和订单/草稿明细归属，记录表行数、库存总数及订单/库存 CRC32 聚合指纹。只有传入 `-SourceProject` 才比较源库与恢复库指纹；此时先停止写流量，再备份并执行恢复。指纹检查不覆盖所有字段，也不能证明全库完全一致。跨版本回滚需将对应备份恢复到独立库验收后再切换，不能只换旧镜像读取新结构。

备份可能包含个人信息，限制宿主机访问并另存异机副本。同机备份不能覆盖整机损坏。配置、备份及本地验收日志在 Git 忽略范围内。

清理前记录项目标签、容器挂载及镜像引用；归档必要旧镜像并校验内容和 SHA-256。仅按核实后的明确名称或 ID 删除本项目临时容器、测试卷和旧应用镜像；仍被其他项目引用或归属不明的镜像保留。不要全局 prune、强制删除镜像或删除业务卷。2026-10-11 的归档与清理清单见对应验收报告。

## 监控

Prometheus 仅绑定 `127.0.0.1:19091`，内部鉴权密钥在运行时注入。监控覆盖网关连接池、HTTP、Agent 指标和基础告警，当前未配置外部告警通知渠道。采集与告警定义分别见 [prometheus.yml](../deploy/prometheus.yml) 和 [alerts.yml](../deploy/alerts.yml)。
