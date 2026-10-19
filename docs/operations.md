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
| `BACKUP_EXTRA_DIRECTORY` | 可选的额外备份专用目录；留空不复制，不填写云凭据 |

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

默认截止时间预算 35 秒，网关 HTTP 超时配置 40 秒，单 Agent 进程最多同时 8 个请求，单店每商户最多 2 个，校园助手每顾客最多 2 个。并发容量或聊天频率超限返回 `429 / AI_CAPACITY`。原单店 `/chat` 与校园 `/campus-assistant/chat` 的连接失败（含连接超时、拒绝连接和解析失败）均返回 `503 / AI_UNAVAILABLE`，读取超时返回 `504 / AI_TIMEOUT`。Agent 明确返回 504 时也映射为 `AI_TIMEOUT`。模型及工具执行前检查截止时间，Java 内部回调也核验截止时间；预算和网络超时不等于对任意执行都提供精确的墙钟硬截止。

默认输出最多 1024 token。每次模型调用的序列化请求 UTF-8 字节数加预留输出 token，不得超过 24000 个预算单位；同一请求累计不得超过 48000 单位。单店每商户、校园助手平台共用的日额度分别按 UTC 日期桶预留，默认每日上限各为 2000000 单位。输入字节与输出 token 合计是保守估算，不能作为实际计费 token。首次模型调用预留整轮请求额度，请求结束按本轮实际记录的估算消耗结算并返还未使用的预留；Redis 结算失败时保守不返还，并记录失败指标。提供商故障及模型调用中的预算/每日额度耗尽会转换为 `LLMError`，通常返回 HTTP 200、`outcome=degraded`；未转换为 `LLMError` 而冒泡到 Agent HTTP 入口的 `BudgetExceeded` 返回 504。部署通过 Redis 记录限流与每日额度；进程内并发准入仍仅适用于单进程。

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

手动脚本和自动备份共用主备份目录的 `.backup-lock`，避免重叠写入状态或轮换文件；锁被占用即失败，不覆盖另一轮状态。进程被强杀或主机断电可能留下锁。此时先确认自动备份服务和手动备份均已停止，确认没有活动备份后才移除该空锁目录，再启动备份。不要在备份运行时移除锁。每轮使用独立临时 SQL 文件，校验完成后才发布正式文件。

成功或失败均原子更新 `backups/backup-status.json`：`schemaVersion=1`、`status=ok/failed`、`lastAttemptAt`、`lastSuccessAt`（UTC Unix 秒）、`backupFile`、`sha256`、`extraCopyVerified`。失败不推进上次成功时间；无成功记录时为零。额外副本启用时，复制后重新计算 SHA-256，通过后才将本轮标为成功；额外复制失败仍保留已验证的主备份并返回失败。若存储本身不可写，状态也可能无法更新，须结合备份新鲜度和容器状态判断。

在本地 `.env` 设置 `BACKUP_EXTRA_DIRECTORY=D:/order-backup-copy`，或手动使用 `backup-db.ps1 -ExtraBackupDirectory <专用目录>`。目录应只保存本项目备份；两处均执行保留最近七份且保留无法校验旧文件的规则。自动备份将此宿主目录挂载为 `/extra-backups`，未配置时不复制。Windows Docker Desktop 需要可访问该路径；网络存储需先由宿主机挂载并配置权限，脚本不管理挂载或凭据。第二个同机目录不能抵御整机损坏，只有确认副本实际位于独立故障域并完成恢复验证后，才算异机保护；离线挂载还需人工确认，不能只凭本轮文件复制成功认定远端存储可用。

上线前由负责人确定可接受的数据丢失窗口 RPO 和恢复时间 RTO，并记录实际恢复耗时。每日全量备份间隔不是承诺的 RPO，失败可能拉长缺口；[2026-10-19 稳定性加固](verification/2026-10-19/稳定性加固.md)已执行升级备份的独立恢复与选定指纹比对，仍不构成 RTO 承诺。小规模单机运行时，升级前备份并验证、保留对应旧制品，按计划执行独立恢复验证和磁盘容量检查。停机维护应提前安排窗口，不能据此承诺企业级可用性或零丢失。

备份可能包含个人信息，限制宿主机访问并另存异机副本。同机备份不能覆盖整机损坏。配置、备份及本地验收日志在 Git 忽略范围内。

清理前记录项目标签、容器挂载及镜像引用；归档必要旧镜像并校验内容和 SHA-256。仅按核实后的明确名称或 ID 删除本项目临时容器、测试卷和旧应用镜像；仍被其他项目引用或归属不明的镜像保留。不要全局 prune、强制删除镜像或删除业务卷。2026-10-11 的归档与清理清单见对应验收报告。

## 监控

Prometheus 仅绑定 `127.0.0.1:19091`，内部鉴权密钥在运行时注入。监控覆盖网关连接池、HTTP、Agent 指标和基础告警。采集与告警定义分别见 [prometheus.yml](../deploy/prometheus.yml) 和 [alerts.yml](../deploy/alerts.yml)。

备份另写 `backups/health/backup.prom`，仅含 `ai_order_backup_last_success_timestamp_seconds` 和 `ai_order_backup_last_attempt_success`。Linux 下 SQL 和主状态保持私有，`health` 目录为 0755、指标为 0644；Windows 下由宿主目录 ACL 控制访问。备份容器只将健康文件写到 `/health`，Agent 的 `BACKUP_HEALTH_DIRECTORY` 仅挂载 `health` 元数据目录并只读访问，不挂载业务 SQL。Agent 汇出备份状态和该文件系统剩余空间；当前主备份与 health 位于同一宿主文件系统，若人为改为分盘挂载，空间指标就不再代表备份盘，需同时调整监控。

默认规则覆盖备份超过 30 小时未成功、最近尝试失败、状态缺失持续 5 分钟、备份盘剩余不足 5 GiB，以及 `agent_audit_write_failures_total`、`agent_quota_settlement_failures_total` 的新增失败。这些阈值需要按备份体积和维护窗口调整；规则触发不代表已发送通知。

只读巡检可直接运行或交给 Windows 任务计划：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-operations.ps1
# 仅显式指定时写JSON报告；可按自己的备份周期调整30小时阈值
.\scripts\check-operations.ps1 -MaxBackupAgeHours 30 -ReportPath .\.local\operations-check.json
```

巡检只读取 Compose 状态和 `backup-status.json`：MySQL、Redis、gateway、agent 须 running 且 healthy，backup、Prometheus 须 running，最新备份须成功且未超过阈值。缺服务、异常状态、Docker 不可用、备份状态损坏/缺失或过期均返回退出码 1；全部通过为 0。标准输出始终为不含密钥或 SQL 的 JSON，字段为 `schemaVersion`、`checkedAt`、`project`、`passed`、`services`、`backup`、`issues`；默认不写文件，不重启或更改服务。

外部通知默认未启用，脚本和服务不会自动向外发送数据。需要通知时，由负责人在任务计划或自己的通知程序中读取退出码及 JSON 的 `passed/issues`，决定发送到已配置的渠道；接收地址、凭据、联系人和通知维护职责由该程序管理。当前没有配置 Alertmanager 或常驻通知服务；不能把告警规则存在描述成已经收到告警。
