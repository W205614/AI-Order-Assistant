# 部署、验证与运维指南

功能、架构及页面预览见 [项目 README](../README.md)。本文保留配置、隔离验证和恢复操作，具体验收数据以各版本报告为准。

## 启动与配置

根目录只保留一个默认 Compose 入口，其他文件按场景覆盖；所有命令均须先指定根文件，挂载路径相对根目录解析。

| 文件 | 使用场景 |
|---|---|
| `docker-compose.yml` | 默认六服务，包含备份与监控 |
| `deploy/compose/isolated.yml` | 隔离测试四服务，19090 端口，清空模型密钥 |
| `deploy/compose/demo.yml` | 接在隔离覆盖后使用，独立演示 19092 端口，由 `scripts/start-demo.ps1` 管理私有配置与 API 初始化 |
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
docker compose -p ai-order-assistant -f docker-compose.yml -f deploy/compose/public.yml up --build -d --wait
```

Caddy 自动申请证书，并在默认六个服务之外增加代理服务。公网配置移除网关的直接端口映射；Agent、MySQL、Redis 不单独暴露公网端口，Prometheus 仍仅绑定本机。实际域名的 TLS 与证书签发须另行验证。

安全启动检查阻止 Secure 模式下启用演示种子或保留已启用的演示弱密码。应用容器使用非 root 和只读文件系统；数据库应用账号限业务 schema，但仍含 Flyway 所需的 DDL 权限，扩大部署规模前应拆分迁移与运行账号。

JWT 校验 HS256、签发方、受众、`exp/iat/nbf/jti`、账号状态和 token 版本。退出持久失效当前凭证，改密码及停用账户失效该账户旧凭证。Cookie 写请求和登录注册均受 CSRF 保护，登录注册还受数据库限流保护。

默认 MySQL 与备份共用 `deploy/mysql` 构建的8.4.13运行镜像：固定官方基础摘要，通过发行版包管理器卸载未使用的 MySQL Shell，并从固定提交用修补后的Go工具链重编译gosu。四个MySQL服务/客户端命令、原入口和数据库格式保持8.4系列。公网Caddy由 `deploy/caddy` 构建，保持2.11.7及标准模块，仅升级已知安全修复依赖与同发行版系统包。两者均为多阶段构建，编译器不进入运行镜像；首次构建需要访问固定上游源码和Go模块源，不应直接复用旧的官方工具二进制规避扫描。

扫描脚本从根配置和公网配置提取实际镜像，按image ID去重，并额外扫描Java构建SBOM。每轮写入新的报告子目录与manifest，包含服务、配置镜像、实际ID/RepoDigests、原始发现及错误；缺镜像、扫描失败、报告损坏或SBOM缺失均使门禁失败。PowerShell可指定 `-ProjectName`、`-OutputDirectory`、`-EnvPath`；只有显式 `-SkipPublic` 才缩小为本地部署范围。残余不可修复告警继续保留，不代表零漏洞。

依赖与镜像扫描见 [security.yml](../.github/workflows/security.yml) 和 [scan-images.ps1](../scripts/scan-images.ps1)。门槛要求修复已有修复版本的 HIGH/CRITICAL；未发布修复的漏洞仍需审阅，扫描通过不表示不存在漏洞。

## AI 预算与降级

校园助手已有成功工具结果后耗尽时间或额度，停止后续工具，保留已完成记录并返回 `outcome=degraded`；尚无成功结果则返回 504。已提交的前一步不会随整轮超时撤销，页面会在聊天失败后同步购物车与安全状态；同步失败时显示“状态尚未确认”，旧报价失效。操作结果不明时先查询，避免重复加购。

默认截止时间预算 35 秒，网关 HTTP 超时配置 40 秒，单 Agent 进程最多同时 8 个请求，单店每商户最多 2 个，校园助手每顾客最多 2 个。并发容量或聊天频率超限返回 `429 / AI_CAPACITY`。原单店 `/chat` 与校园 `/campus-assistant/chat` 的连接失败（含连接超时、拒绝连接和解析失败）均返回 `503 / AI_UNAVAILABLE`，读取超时返回 `504 / AI_TIMEOUT`。Agent 明确返回 504 时也映射为 `AI_TIMEOUT`。模型及工具执行前检查截止时间，Java 内部回调也核验截止时间；预算和网络超时不等于对任意执行都提供精确的墙钟硬截止。

默认输出最多 1024 token。每次模型调用的序列化请求 UTF-8 字节数加预留输出 token，不得超过 24000 个预算单位；同一请求累计不得超过 48000 单位。单店每商户、校园助手平台共用的日额度分别按 UTC 日期桶预留，默认每日上限各为 2000000 单位。输入字节与输出 token 合计是保守估算，不能作为实际计费 token。首次模型调用预留整轮请求额度，请求结束按本轮实际记录的估算消耗结算并返还未使用的预留；Redis 结算失败时保守不返还，并记录失败指标。提供商故障通常返回 HTTP 200、`outcome=degraded`；校园预算耗尽按上段区分是否已有成功结果，其余冒泡到 HTTP 入口的 `BudgetExceeded` 返回 504。部署通过 Redis 记录限流与每日额度；进程内并发准入仍仅适用于单进程。

## 请求、数据库与事件资源边界

应用按实际读取字节限制请求体为 128 KiB，无长度头或分块请求同样受限，超出返回 `413 / BODY_TOO_LARGE`。公网 Caddy 使用相同上限。正常认证和 CSRF 检查仍然执行。

| 配置 | 默认及含义 |
|---|---|
| `DB_CONNECTION_TIMEOUT_MS` / `DB_CONNECT_TIMEOUT_MS` | 3000 / 3000，分别为连接池获取与驱动建立连接等待 |
| `DB_QUERY_TIMEOUT_SECONDS` | 8，普通查询和业务事务预算；内部 AI 请求取剩余整秒与此值的较小值 |
| `DB_LOCK_WAIT_SECONDS` | 5，新应用连接的 MySQL 会话行锁等待，不修改全局配置或隔离级别 |
| `DB_SOCKET_TIMEOUT_MS` | 10000，JDBC 网络读取上限 |
| `SSE_SENDER_THREADS` / `SSE_QUEUE_CAPACITY` | 4 / 200，两套事件共用有界执行通道，每连接最多一个任务在途 |
| `RECOMMEND_MAX_CONCURRENT` / `RECOMMEND_MAX_COMBINATIONS` | 2 / 64，单进程组合推荐准入与每次最大实际报价验证数 |
| `METRICS_QUEUE_CAPACITY` | 1000，脱敏日志待写数量；满时丢弃新增日志并计数，单后台线程写入 |

以上容量与时间值应为正数。事件调度器只提交任务，数据库回放和网络发送由专用线程执行；饱和时关闭连接，由客户端按持久化游标重连补取。慢写仍可能占住发送线程，这套限制保证资源有界并隔离业务补偿，不承诺每个连接的精确发送时限。数据库计时和网络取消也不是整次 HTTP 请求的严格墙钟上限。

`DATABASE_TIMEOUT` 返回 504；数据库连接或提交失败导致结果无法确认时返回 `503 / DATABASE_RESULT_UNCONFIRMED`，应先查询购物车或订单，禁止自动重试写入。MySQL 锁冲突继续使用现有 `409 / CONCURRENT_OPERATION`。组合推荐超额返回 `429 / RECOMMEND_LIMIT`，结果说明验证范围有限，不能视为全局最优。

新增 `PUT /cart/batch`，沿用现有认证、CSRF 和结果封装，请求为 `{"items":[{"productType":"SKU","productId":1,"quantity":2,"remark":"","expectedVersion":1}]}`。数量为目标绝对数量，每批 1–100 个不重复商品，成功返回购物车项 ID 列表；新项版本为 null 或 0，已有项必须提供当前版本。先锁用户后读取，同一事务复用单项检查，任一失败整批回滚。请求不自动重放；响应丢失后查询购物车核对结果。

日志统计保留进程内聚合与现有接口字段，后台写盘保留轮转；`agent_audit_write_failures_total` 表示写盘失败，`agent_audit_queue_dropped_total` 表示队列满或关闭时拒绝的新日志。关闭最多等待两秒，进程终止可能丢失尚未落盘的日志，业务数据库仍是交易依据。

提供商返回 usage 时，响应记录累计输入/输出 token 和调用数，缺少账单时不推算费用。真实订单取消工具仅返回待确认操作，用户点击后调用 Java。菜单、FAQ、模型文本及历史记录不能修改可信身份或商户。

## 验证与压测

README 给出基础验证命令。Java 真实 MySQL/Flyway 测试需要 Docker 且不允许跳过。普通 CI 无论成功或失败都保留已有的隔离验收 JSON 和 Surefire 报告14天，上传路径显式限定，不包含 `.env`、本地夹具或数据库导出。新增履约分页/隐私、响应字段白名单、聊天准入和截止取消仅做相应定向验证。Python 使用 Python 3.13 虚拟环境，并安装固定依赖：

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

历史证据按版本阅读：[2026-09-29](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-09-29/)、[2026-10-07](https://github.com/W205614/AI-Order-Assistant/blob/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-07/%E9%AA%8C%E6%94%B6%E6%8A%A5%E5%91%8A.md)、[2026-10-09](https://github.com/W205614/AI-Order-Assistant/blob/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-09/%E7%8A%B6%E6%80%81%E7%A0%81%E4%BF%AE%E5%A4%8D%E9%AA%8C%E8%AF%81.md)、[2026-10-11](https://github.com/W205614/AI-Order-Assistant/blob/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-11/%E9%87%8D%E6%9E%84%E4%B8%8E%E9%83%A8%E7%BD%B2%E9%AA%8C%E6%94%B6.md)。各轮测试数量和环境有差异，不能合并成同一版本的结论。

## 真实模型验收

普通 CI 不调用付费提供商。手动运行 GitHub Actions 的 **Live Agent Evaluation**，可选择 `campus`（默认六组校园场景）或 `legacy`；配置仓库的 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` Secrets 后显式触发。所有重复轮次共享同一限额，安全断言失败或预算不足均返回失败，脱敏报告保存在 workflow artifact 中。

本机验收先创建忽略的私有环境文件，包含隔离配置及 `CAMPUS_EVAL_LLM_API_KEY`、`CAMPUS_EVAL_LLM_BASE_URL`、`CAMPUS_EVAL_LLM_MODEL`、`CAMPUS_EVAL_RUN_ID`。模型密钥只进入 Agent，不能写入报告或提交。使用根配置、隔离覆盖、模型覆盖依次启动独立项目：

```powershell
docker compose --env-file .local/live-eval.env -p ai-order-eval -f docker-compose.yml -f deploy/compose/isolated.yml -f deploy/compose/model-eval.yml up --build -d --wait
python scripts/test-campus-live-model.py --project ai-order-eval --env-file .local/live-eval.env --fixture .local/live-eval-fixture.json --report .local/live-eval.json
```

评测前在隔离项目的 Redis 开启持久账本：每次提供商请求发送前，原子扣计请求次数与“序列化请求 UTF-8 字节数 + 预留输出额度”，包括失败请求和网络重试，不返还。单轮最多 60 次、150000 单位；无过期时间，既有上限只能降低。同一轮修复复测必须保留相同 ID、Redis 数据卷和账本，不能换 ID 或清空数据绕过限额。普通应用不配置 `AGENT_EVALUATION_RUN_ID`，不启用该专用账本。不同轮次另行明确费用授权。

脚本记录模型、源码提交及工作区状态、延迟分位数、提供商 usage、Java 结果断言和前后账本读数，每次另存带时间戳报告，失败记录不会被下一次覆盖。所有场景属于固定开发验收，不能推算普遍正确率、生产容量或费用。预算不足或提供商不可用均标为未完成，不等于通过。

## 备份恢复与清理

自动备份在每次尝试内最多等待数据库就绪 120 秒，探测同时检查 DNS、连接、认证和目标库。Docker 引擎重启不会重新执行 Compose 的依赖启动顺序，因此不能只依赖 `depends_on`。就绪超时或导出失败后保留失败状态，由同一容器按 30、60、120、300 秒顺序退避，之后每 300 秒重试；成功后等待 86400 秒。整个循环只运行一个备份子进程，退出时终止并等待该子进程。巡检要求备份容器 `healthy`，仅有进程运行不算通过。

```powershell
.\scripts\backup-db.ps1
# 恢复到独立临时 MySQL，不覆盖正式数据库
.\scripts\restore-db.ps1 -BackupFile .\backups\orders-时间戳.sql
```

默认每日备份，通常保留最近七份；未通过校验的旧备份保留供人工检查。恢复脚本验证 SHA-256、SQL 导入和订单/草稿明细归属，记录表行数、库存总数及订单/库存 CRC32 聚合指纹。只有传入 `-SourceProject` 才比较源库与恢复库指纹；此时先停止写流量，再备份并执行恢复。指纹检查不覆盖所有字段，也不能证明全库完全一致。跨版本回滚需将对应备份恢复到独立库验收后再切换，不能只换旧镜像读取新结构。

导出阶段默认超时300秒（`BACKUP_DUMP_TIMEOUT_SECONDS`，1–3600），额外副本阶段默认120秒（`BACKUP_COPY_TIMEOUT_SECONDS`，1–1800）。手动脚本也接受 `-DumpTimeoutSeconds`、`-CopyTimeoutSeconds`，优先级为显式参数、进程环境变量、`.env`、默认值。Linux 在超时后终止阶段进程组、必要时强制终止并等待；Windows 手动备份同时监督容器内导出与宿主复制。确认 worker 结束后才清理临时文件及本轮持有的锁，保留旧成功时间和有效备份，自动循环继续既有退避重试。

若 Docker 控制面断开、不能确认远端导出已经终止，手动脚本标记失败并保留锁，禁止直接重试覆盖。先恢复 Docker，确认相关临时导出进程已停止、没有活动备份，再处理该锁及残留文件；不能仅凭宿主 docker 进程退出判断容器内 worker 已停止。Windows 手动脚本的文件清理由回收站执行，不提供永久删除降级。

手动脚本和自动备份共用主备份目录的 `.backup-lock`，避免重叠写入状态或轮换文件；锁被占用即失败，不覆盖另一轮状态。进程被强杀或主机断电可能留下锁。此时先确认自动备份服务和手动备份均已停止，确认没有活动备份后才处理该锁目录及其 owner 标记，再启动备份。不要在备份运行时移除锁。每轮使用独立临时 SQL 文件，校验完成后才发布正式文件。

成功或失败均原子更新 `backups/backup-status.json`：`schemaVersion=1`、`status=ok/failed`、`lastAttemptAt`、`lastSuccessAt`（UTC Unix 秒）、`backupFile`、`sha256`、`extraCopyVerified`。失败不推进上次成功时间；无成功记录时为零。额外副本启用时，复制后重新计算 SHA-256，通过后才将本轮标为成功；额外复制失败仍保留已验证的主备份并返回失败。若存储本身不可写，状态也可能无法更新，须结合备份新鲜度和容器状态判断。

在本地 `.env` 设置 `BACKUP_EXTRA_DIRECTORY=D:/order-backup-copy`，或手动使用 `backup-db.ps1 -ExtraBackupDirectory <专用目录>`。目录应只保存本项目备份；两处均执行保留最近七份且保留无法校验旧文件的规则。自动备份将此宿主目录挂载为 `/extra-backups`，未配置时不复制。Windows Docker Desktop 需要可访问该路径；网络存储需先由宿主机挂载并配置权限，脚本不管理挂载或凭据。第二个同机目录不能抵御整机损坏，只有确认副本实际位于独立故障域并完成恢复验证后，才算异机保护；离线挂载还需人工确认，不能只凭本轮文件复制成功认定远端存储可用。

上线前由负责人确定可接受的数据丢失窗口 RPO 和恢复时间 RTO，并记录实际恢复耗时。每日全量备份间隔不是承诺的 RPO，失败可能拉长缺口；[2026-10-19 稳定性加固](https://github.com/W205614/AI-Order-Assistant/blob/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-19/%E7%A8%B3%E5%AE%9A%E6%80%A7%E5%8A%A0%E5%9B%BA.md)已执行升级备份的独立恢复与选定指纹比对，仍不构成 RTO 承诺。小规模单机运行时，升级前备份并验证、保留对应旧制品，按计划执行独立恢复验证和磁盘容量检查。停机维护应提前安排窗口，不能据此承诺企业级可用性或零丢失。

备份可能包含个人信息，限制宿主机访问并另存异机副本。同机备份不能覆盖整机损坏。配置、备份及本地验收日志在 Git 忽略范围内。

清理前记录项目标签、容器挂载及镜像引用；归档必要旧镜像并校验内容和 SHA-256。仅按核实后的明确名称或 ID 删除本项目临时容器、测试卷和旧应用镜像；仍被其他项目引用或归属不明的镜像保留。不要全局 prune、强制删除镜像或删除业务卷。2026-10-11 的归档与清理清单见对应验收报告。

## 监控

Prometheus 仅绑定 `127.0.0.1:19091`，内部鉴权密钥在运行时注入。监控覆盖网关连接池、HTTP、Agent 指标和基础告警。采集与告警定义分别见 [prometheus.yml](../deploy/prometheus.yml) 和 [alerts.yml](../deploy/alerts.yml)。

备份另写 `backups/health/backup.prom`，仅含 `ai_order_backup_last_success_timestamp_seconds` 和 `ai_order_backup_last_attempt_success`。Linux 下 SQL 和主状态保持私有，`health` 目录为 0755、指标为 0644；Windows 下由宿主目录 ACL 控制访问。备份容器只将健康文件写到 `/health`，Agent 的 `BACKUP_HEALTH_DIRECTORY` 仅挂载 `health` 元数据目录并只读访问，不挂载业务 SQL。Agent 汇出备份状态和该文件系统剩余空间；当前主备份与 health 位于同一宿主文件系统，若人为改为分盘挂载，空间指标就不再代表备份盘，需同时调整监控。

该空间指标不覆盖 MySQL 数据卷或 Docker Desktop 的磁盘映像。每次升级及定期维护时，执行 `docker compose -p ai-order-assistant exec -T mysql df -h /var/lib/mysql` 查看数据库所在文件系统，并执行 `docker info --format '{{.DockerRootDir}}'` 确认 Docker 内部数据目录。Windows 还需在 Docker Desktop 设置中确认磁盘映像的宿主位置，用 `Get-Volume` 检查对应宿主盘剩余空间；虚拟磁盘内部有空间并不代表宿主盘有空间。以上检查无需给 Agent 挂载业务卷或 Docker socket。

默认规则覆盖备份超过 30 小时未成功、最近尝试失败、状态缺失持续 5 分钟、备份盘剩余不足 5 GiB，以及 `agent_audit_write_failures_total`、`agent_quota_settlement_failures_total` 的新增失败。这些阈值需要按备份体积和维护窗口调整；规则触发不代表已发送通知。

只读巡检可直接运行或交给 Windows 任务计划：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-operations.ps1
# 仅显式指定时写JSON报告；可按自己的备份周期调整30小时阈值
.\scripts\check-operations.ps1 -MaxBackupAgeHours 30 -ReportPath .\.local\operations-check.json
# 仅在已配置公网代理、域名与可信 TLS 后启用；不携带账号或密钥
.\scripts\check-operations.ps1 -PublicBaseUrl 'https://orders.example.com'
```

默认巡检读取 Compose 状态、`backup-status.json`、主备份和 `.sha256`：MySQL、Redis、gateway、agent、backup 须 running 且 healthy，Prometheus 须 running，最新备份须成功且未超过阈值。备份文件名必须是配置目录中的普通 `.sql` 文件；路径穿越、符号链接或 junction 均拒绝。每次实际计算 SHA-256，与状态和校验文件同时比较，文件缺失、空文件、校验不一致也判失败。大备份的完整校验会增加巡检耗时，应按自己的备份体积安排任务计划；校验通过仍不能代替定期独立恢复演练，也不核验额外副本当前是否仍存在。

缺服务、异常状态、Docker 不可用、备份状态损坏/缺失、过期或完整性失败均返回退出码 1；全部通过为 0。标准输出始终为不含密钥或 SQL 的 JSON，保留 `schemaVersion=1`、`checkedAt`、`project`、`passed`、`services`、`backup`、`issues`，新增 `mode`、`public` 和 `backup.artifact`。默认 `mode=local`、`public.status=not_checked`、`public.passed=null`，明确表示公网未检查；默认不写文件，不重启或更改服务。

显式配置 `PublicBaseUrl` 后为 `mode=public`，额外要求同项目 `proxy` 服务 running，并通过标准证书验证访问 `/campus/index.html` 和 `/actuator/health/readiness`（须 `status=UP`）。只接受无路径、查询参数或凭据的 HTTPS 源站地址；每次 GET 最多 10 秒，不跳过证书校验、不接受重定向。任一失败均使整体巡检失败。此结果只代表本机发起检查时该入口可达，不代替异地网络检查或完整公网部署验收；本轮默认本地部署不会自动启用公网模式。

外部通知默认未启用，脚本和服务不会自动向通知渠道发送数据；只有显式配置公网检查时，脚本才向指定 HTTPS 入口发送只读 GET。需要通知时，由负责人在任务计划或自己的通知程序中读取退出码及 JSON 的 `passed/issues`，决定发送到已配置的渠道；接收地址、凭据、联系人和通知维护职责由该程序管理。当前没有配置 Alertmanager 或常驻通知服务；不能把告警规则存在描述成已经收到告警。
