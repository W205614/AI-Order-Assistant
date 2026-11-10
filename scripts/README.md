# 脚本索引

以下路径均相对项目根目录，默认先在根目录执行命令。完整应用入口是 [start.ps1](../start.ps1)，首次体验无需逐个运行这些脚本。

## 常用入口与运行影响

以下为默认参数下成功执行的结果。路径相对项目根目录。

| 入口 | 目标环境 | 数据与文件写入 | 结束时的服务状态 |
|---|---|---|---|
| `start.ps1 -Build` | 日常，9090 | 补全配置、按策略备份、执行迁移；新演示库初始化种子 | 五个日常服务运行 |
| `scripts/check-operations.ps1` | 已启动的日常环境 | 只读巡检；指定 `-ReportPath` 才写报告 | 保持执行前状态 |
| `scripts/start-demo.ps1 -Build` | 独立演示，19092 | 初始化演示数据与本地账号清单，再次运行复用 | 演示环境继续运行 |
| `scripts/smoke-compose.ps1` | **日常，9090** | 重建应用，写测试订单和报告 | 默认停止 gateway 和 agent；`-LeaveRunning` 保持运行 |
| `scripts/run-isolated-k6.ps1` | 独立压测项目 `ai-order-perf` | 创建压测账号、订单、夹具和结果 | 默认移除该项目的容器和数据卷；`-LeaveRunning` 保留 |

## 初始化与日常运维

| 脚本 | 用途 |
|---|---|
| `initialize-env.ps1` | 生成或补全本地环境配置，由启动脚本调用 |
| `start-demo.ps1` | 独立 `ai-order-demo`/19092 完整多角色演示；私有清单复用、不覆盖人工修改，默认不调用模型 |
| `provision-db-user.ps1` | 创建或授权数据库应用账号，由启动脚本调用 |
| `backup-db.ps1` | 创建数据库备份与校验文件，启动前调用，也可手动执行 |
| `restore-db.ps1`、`restore-integrity.py` | 独立无外部网络MySQL恢复，默认核验V1–V9全列指纹和新增域不变量，输出媒体清单；旧结构须显式选择模式 |
| `backup_metadata.py` | SQL摘要与UTC原始创建时间的统一元数据契约，复制／下载不刷新快照年龄 |
| `backup-media.py`、`media_manifest.py` | 按恢复库清单备份本地／OSS对象，独立空目标恢复及逐对象校验，报告绑定数据库清单 |
| `check-operations.ps1` | 只读检查服务、备份时效与完整性；报告仅显式指定时写入 |
| `check-integrations.py` | 只读检查支付、短信、OSS、地图及云配置；`--live` 仅探测应用健康，不发起第三方交易 |
| `render-alertmanager.py` | 生成私有SMTP/Webhook告警配置和数据目录，不向接收端发测试消息 |
| `backup-oss.py` | 使用官方MySQL/ossutil客户端导出、上传并回读校验异地备份；`restore-check` 调用独立恢复验证 |
| `release.py` | 以不可变镜像摘要准备、发布或回滚；实际变更要求有效备份及匹配恢复报告，保留业务卷 |
| `scan-images.ps1`、`scan_deployment_images.py` | 解析本地/公网部署镜像，按实际ID去重，扫描全部服务及Java SBOM；每轮独立manifest与原始报告 |
| `check-agent-security.py` | 对指定 `--project` 的运行 Agent 核验实际权限、禁用命令、就绪与只读 HTTP 依赖；不调用模型 |
| `check-security-boundaries.py` | 核验监控与业务认证隔离、Redis ACL、数据库 DML 权限、迁移退出和网络隔离；不调用模型 |

## 分发材料与容器发布

此组工具处理候选和对应源码，不启动日常服务。源码URL、Git提交或模块 `h1` 由审查后的锁固定；当前应用与增量材料见 [v0.1.3 Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.3) 及 [Packages](https://github.com/W205614/AI-Order-Assistant/packages)，v0.1.2基础源材料继续保留。完整获取、平台和升级约束见[应用分发](../docs/distribution.md)。

原Maven应用包准备/发布脚本及工作流已从当前工程回收，实现保留在Git历史；不再把它们列作可运行入口。旧包的远端收尾状态见[入口迁移](../docs/distribution.md#历史-maven-入口迁移)。Maven源码编译及测试仍保留。

| 入口 | 作用与边界 |
|---|---|
| `inspect-distribution-images.py` | 解析实际 Docker 归档、逐层文件/原生 ELF/Go build info/系统库存和通知；保留已覆盖的下层内容，不运行镜像程序；历史输入见 `licenses/historical-ci-images.json` |
| `verify-container-distribution.py` | 对实际基础设施归档执行门禁；校验构建输入、每层、Go/OS组件、固定Release基础与增量材料及逐文件摘要，`--write-notices` 输出原文通知和源码入口 |
| `fetch-distribution-materials.py` | 按 SHA-256、SHA-512 或 Go `h1` 获取锁定源码；验证多级 archiveMember 依赖后再提取通知，拒绝缺项、摘要变化及路径越界 |
| `build-distribution-bundle.py` | 对已取得材料复验，保留源码/配方/版权/原 NOTICE，收集 PATENTS 与 JRE 法律例外；生成全文通知、内部 `MANIFEST.json`、逐文件哈希及确定性归档，完成整包回读 |
| `publish-source-materials.py` | 默认只验证；显式 `--publish` 才向既有Release追加三项源资产。支持历史os/go-additional和明确 `--version 0.1.3 --kind release-delta`；锁须一致，同名不同字节失败，不删除旧附件 |
| `publish-container-images.py` | 只推广锁定的 Gateway/Agent 单层候选到 GHCR 完整 SHA 标签，检查包装提交 CI/安全与应用 Tag、实际归档配置摘要和 registry 回拉；不重建或部署应用 |
| `test_distribution_images.py`、`test_distribution_materials.py`、`test_distribution_bundle.py`、`test_container_distribution.py` | 定向验证归档、来源、通知、完整性、路径、重复资产、错误 prepared run 和固定候选的失败行为；不构成业务或真实渠道验收 |

在 Python 3.13 环境中，从固定 OS 锁重建源码材料候选：

```powershell
python -B scripts/fetch-distribution-materials.py --lock licenses/container-os-sources.lock.json --output .local/license-materials/os
python -B scripts/build-distribution-bundle.py --lock licenses/container-os-sources.lock.json --materials .local/license-materials/os --output .local/license-bundles/os --kind os
python -B scripts/publish-source-materials.py --input .local/license-bundles/os --kind os
```

上述命令下载源码并写本地候选，最后一步只验证。历史追加Go使用 `licenses/go-additional-source-inputs.json` 和 `--kind go-additional`；本轮增量使用 `licenses/release-delta-source-inputs.json`、独立输出目录及 `--kind release-delta`，发布验证明确 `--version 0.1.3`。源码归档保留上游测试数据，不把它当作应用，不执行其中安装脚本。

[源码准备工作流](../.github/workflows/prepare-license-materials.yml)区分 `legacy`（历史OS/追加Go）与 `release-delta`；后者显式手动准备。手动 `publish=true` 才进入写权限任务，可用成功 `prepared_run_id` 推广同仓库、同工作流的原候选，先验来源与当前锁，不重建。它只向既有正式Release追加，不自动建版本；普通候选任务只读，写权限限发布任务。

[安全工作流](../.github/workflows/security.yml)仅在全五级漏洞扫描和分发材料门禁均成功后，上传镜像归档及 `NOTICES.txt`、`source-materials.json`、`verification.json`。历史基础材料与本轮增量共同按锁核对，摘要、原通知和回执持续保存，不因短期CI工件到期删除Release源资产。[容器发布工作流](../.github/workflows/publish-containers.yml)只推广已验应用候选；日常升级仍走 `release.py` 的不可变摘要、备份、独立恢复和数据保留流程。

## 配置与静态检查

| 脚本 | 用途 |
|---|---|
| `check-format.py` | 在项目根目录运行 `python scripts/check-format.py`，扫描全部 Git 跟踪文本的 UTF-8、文件末尾换行、行尾空白和指定语言的缩进制表符。兼容 Windows 工作区 CRLF，同时检查索引换行。历史验收目录 `docs/verification/` 中的脚本与本地未跟踪产物跳过，现役测试脚本照常检查。Markdown 与补丁保留行尾空格。 |
| `check-compose.py` | 验证默认、隔离、演示、公网与模型评测配置，不启动服务 |
| `check-cloud-compose.py` | 使用合成配置验证云数据、告警及公网覆盖组合，不连接外部资源或启动服务 |
| `test_integration_operations.py` | 运维预检查、恢复证据门禁、停用策略及OSS回读损坏的定向测试 |
| `test_recovery_artifacts.py` | 原始备份时间与摘要、媒体对象归档和独立恢复、受控OSS及损坏拒绝 |
| `test-recovery-integration.py` | 显式选择本轮网关与Agent镜像，真实独立MySQL验证V9完整恢复、媒体目录／卷恢复与各域损坏拒绝 |
| `test-alert-delivery.py` | 唯一隔离项目中运行真实Alertmanager、标准库HTTP/SMTP收件器和promtool，验证FIRING及RESOLVED实际投递；默认使用10.77.244.0/28测试子网，运行前核对占用，可用`--subnet`调整；结束只清理本测试项目 |
| `check-static-js.mjs` | 检查页面脚本语法与相关静态约束 |
| `test-backup-shell.py`、`test-backup-process.ps1` | 自动备份与宿主进程监督的定向测试，覆盖真实挂起、进程树、保锁与重试 |
| `test-operations.ps1` | 使用临时夹具验证巡检与备份行为 |
| `test-caddy-body-limit.py` | 检查代理请求体限制及转发头信任边界 |

## 系统与浏览器验证

这组脚本不参与应用启动。按 [运维指南](../docs/operations.md#验证与压测) 准备隔离环境，再运行相应场景；CI 的具体命令见 [ci.yml](../.github/workflows/ci.yml)。部分脚本会建立测试账号、修改购物车或创建订单。

| 脚本 | 场景 |
|---|---|
| `test-functional-concurrency.py` | HTTP、会话和订单并发 |
| `test-roles-and-isolation.py` | 角色权限与租户隔离 |
| `test-browser.cjs` | 原单店完整浏览器流程 |
| `test-campus-browser.cjs` | 校园跨店浏览器流程 |
| `test-campus-recovery.cjs` | 页面延迟、失败与恢复 |
| `test-event-subscriptions.cjs` | 可取消事件订阅、账号游标、编辑后补刷和刷新后确认，无外部依赖 |
| `test-fulfillment-lists.cjs` | 三个履约工作台活动/历史分页、骑手历史隐私提示和事件刷新编辑保护 |
| `test-support-lists.cjs` | 商家、平台售后与反馈待办/历史分页，处理后刷新及顾客反馈范围切换 |
| `test-campus-event-edit.cjs` | Chromium 确定性验证异步订单刷新不会覆盖顾客正在编辑的反馈 |
| `test-campus-safety.cjs` | 校园购物车与过敏约束 |
| `test-role-experience.cjs` | 顾客、店主、店员、平台与骑手交互及权限、反馈、售后 |
| `test-ui-quality.cjs` | 页面布局、交互与可选模型场景 |
| `test-agent-faults.py`、`fault-provider.py` | Agent 故障注入与模拟提供商 |
| `test-campus-live-model.py` | 校园六组真实模型验收，要求持久限额账本，保留每次失败和复测报告，可能产生费用 |
| `test-integration-ui.cjs` | 受控HTTP验证Native/H5/JSAPI、OAuth、手机绑定、图片、地图和通知；不调用真实渠道 |
| `test-account-lifecycle.cjs` | 实际前端模块和页面验证401、退出与跨账号切换时清除通知、收货表单、对话、购物车及弹窗，取消请求与SSE |
| `verify-live-integrations.py` | 显式生产接入验收：默认只读配置，按参数实际调用地图、图片、短信、支付查单、退款查询及对账 |
| `test_live_integrations.py` | 验收脚本的本地HTTP契约测试，验证Cookie/CSRF、隐私报告、支付与短信待确认状态 |
| `http_contract.py` | HTTP 测试共享客户端，不是独立运行入口 |

## 内部辅助脚本与直接测试

| 文件 | 调用者与职责 |
|---|---|
| `backup-process.ps1` | `backup-db.ps1` 加载的进程监督函数，由备份与巡检测试直接覆盖 |
| `backup-copy-worker.ps1` | `backup-db.ps1` 在独立进程中调用，复制并校验额外备份 |
| `test_scan_deployment_images.py` | 安全工作流通过 unittest 发现执行，直接测试 `scan_deployment_images.py` |

## 压测

| 脚本 | 用途 |
|---|---|
| `run-isolated-k6.ps1` | 启动独立压测环境，默认结束时清理该测试项目的数据卷 |
| `prepare-load-users.py` | 准备独立压测会话与私有夹具 |
| `test-campus-load.py` | 校园业务并发验证；准备账号遇到注册限流时最多等待一个60秒窗口，不修改生产限额，准备时间不计入负载样本 |
| `check-load-invariants.py` | 校验压测后的库存等数据约束 |

k6 场景位于 [load/](../load/)，结果默认写入忽略的 `load/results/`；测试夹具和临时报告放 `.local/`，不提交账号、密钥或原始业务数据。Java 单元与集成测试保留在 `java-gateway/src/test/`，Python 单元测试保留在 `agent-service/tests/`。

## 生产接入验收

完整参数和分角色操作见[接入指南](../docs/integrations.md#11-执行生产服务验收)。默认仅获取应用配置，不发送短信、上传图片或创建支付会话：

```powershell
python scripts/verify-live-integrations.py --base-url https://实际应用域名 --report .local/live-capabilities.json
python -m unittest discover -s scripts -p test_live_integrations.py -v
```

执行功能验收前，在环境中设置 `LIVE_VERIFY_USERNAME`、`LIVE_VERIFY_PASSWORD`、`LIVE_VERIFY_ROLE`。真实服务调用必须通过 `--map-address`、`--upload-image`、`--sms-phone` 等参数逐项选择。`--checkout-id` 只创建支付会话并等待本人在浏览器付款；脚本不直接确认付款，也不代发退款。验证码用 `--challenge-id ... --code` 的交互输入读取，不把验证码值写进命令行。

状态区分 `PASS`、`FAIL`、`PENDING`、`NOT_RUN`。默认只读检查记录为`NOT_RUN`；短信受理和支付会话创建记录为待确认，实际收码和渠道付款确认后更新验收结果。
