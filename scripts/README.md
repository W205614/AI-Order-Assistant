# 脚本索引

以下路径均相对项目根目录，默认先在根目录执行命令。完整应用入口是 [start.ps1](../start.ps1)，首次体验无需逐个运行这些脚本。

## 初始化与日常运维

| 脚本 | 用途 |
|---|---|
| `initialize-env.ps1` | 生成或补全本地环境配置，由启动脚本调用 |
| `start-demo.ps1` | 独立 `ai-order-demo`/19092 完整多角色演示；私有清单复用、不覆盖人工修改，默认不调用模型 |
| `provision-db-user.ps1` | 创建或授权数据库应用账号，由启动脚本调用 |
| `backup-db.ps1` | 创建数据库备份与校验文件，启动前调用，也可手动执行 |
| `restore-db.ps1` | 恢复到独立验证数据库，详见 [运维指南](../docs/operations.md#备份恢复与清理) |
| `check-operations.ps1` | 只读检查服务、备份时效与完整性；报告仅显式指定时写入 |
| `scan-images.ps1`、`scan_deployment_images.py` | 解析本地/公网部署镜像，按实际ID去重，扫描全部服务及Java SBOM；每轮独立manifest与原始报告 |
| `check-agent-security.py` | 对指定 `--project` 的运行 Agent 核验实际权限、禁用命令、就绪与只读 HTTP 依赖；不调用模型 |
| `check-security-boundaries.py` | 核验监控与业务认证隔离、Redis ACL、数据库 DML 权限、迁移退出和网络隔离；不调用模型 |

## 配置与静态检查

| 脚本 | 用途 |
|---|---|
| `check-compose.py` | 验证默认、隔离、演示、公网与模型评测配置，不启动服务 |
| `check-static-js.mjs` | 检查页面脚本语法与相关静态约束 |
| `test-backup-shell.py`、`test-backup-process.ps1` | 自动备份与宿主进程监督的定向测试，覆盖真实挂起、进程树、保锁与重试 |
| `test-operations.ps1` | 使用临时夹具验证巡检与备份行为 |
| `test-caddy-body-limit.py` | 检查代理请求体限制 |

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
| `test-campus-event-edit.cjs` | Chromium 确定性验证异步订单刷新不会覆盖顾客正在编辑的反馈 |
| `test-campus-safety.cjs` | 校园购物车与过敏约束 |
| `test-role-experience.cjs` | 顾客、店主、店员、平台与骑手交互及权限、反馈、售后 |
| `test-ui-quality.cjs` | 页面布局、交互与可选模型场景 |
| `test-agent-faults.py`、`fault-provider.py` | Agent 故障注入与模拟提供商 |
| `test-campus-live-model.py` | 校园六组真实模型验收，要求持久限额账本，保留每次失败和复测报告，可能产生费用 |
| `http_contract.py` | HTTP 测试共享客户端，不是独立运行入口 |

`smoke-compose.ps1` 使用日常项目名和 `9090` 端口，重建应用并写入其测试订单；默认结束时停止 gateway 和 agent。需要隔离测试时按运维指南使用独立项目名与 `19090`，不要把该脚本误当成只读巡检。

## 压测

| 脚本 | 用途 |
|---|---|
| `run-isolated-k6.ps1` | 启动独立压测环境，默认结束时清理该测试项目的数据卷 |
| `prepare-load-users.py` | 准备独立压测会话与私有夹具 |
| `test-campus-load.py` | 校园业务并发验证 |
| `check-load-invariants.py` | 校验压测后的库存等数据约束 |

k6 场景位于 [load/](../load/)，结果默认写入忽略的 `load/results/`；测试夹具和临时报告放 `.local/`，不提交账号、密钥或原始业务数据。Java 单元与集成测试保留在 `java-gateway/src/test/`，Python 单元测试保留在 `agent-service/tests/`。
