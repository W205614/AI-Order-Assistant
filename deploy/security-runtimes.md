# Caddy 与监控镜像的安全构建

三个运行镜像使用 `golang.org/x/net v0.62.0`，修复 [CVE-2026-78661 / GO-2026-6860](https://pkg.go.dev/vuln/GO-2026-6860)。组件版本、数据格式和入口保持原有版本；编译器只在构建阶段使用。

| 组件 | 版本与构建入口 | 上游来源 |
|---|---|---|
| Caddy | 2.11.7，[Dockerfile](caddy/Dockerfile) | [标准模块](caddy/main.go)及 [Go 依赖锁](caddy/go.mod)，保留已有 [go-jose 修复](caddy/patches/README.md) |
| Prometheus / promtool | 3.15.1，[Dockerfile](prometheus/Dockerfile) | [固定提交 bae3eced](https://github.com/prometheus/prometheus/tree/bae3eced0a5369e050e83ea73bc381d7c4b1a719)及同版官方 Web UI |
| Alertmanager / amtool | 0.34.2，[Dockerfile](alertmanager/Dockerfile) | [固定提交 e8349048](https://github.com/prometheus/alertmanager/tree/e8349048265ccc0871c3d0d668b05eacc68baf50)及同版官方 Web UI |

Prometheus 和 Alertmanager 的 Dockerfile 固定源码提交、源码归档 SHA-256、官方 Web UI SHA-256、Go 工具链和运行基础镜像摘要。构建仅通过 Go 模块工具升级 `x/net` 及其要求的传递依赖，校验模块并运行上游 HTML 解析测试。Prometheus 执行上游 `scripts/compress_assets.sh` 生成嵌入文件，再启用 `builtinassets`；两个服务均嵌入实际页面资源。最终阶段替换服务和配套命令两个二进制，继承官方用户、入口、配置和存储路径，保留上游许可证。

以默认项目名构建候选镜像，不启动服务：

```powershell
$env:APP_REVISION = git rev-parse HEAD
$env:APP_DOMAIN = 'example.invalid' # 仅用于构建时解析公网覆盖
$env:OPERATIONS_UID = '1000'
$env:OPERATIONS_GID = '1000'
docker compose --env-file .env -p ai-order-assistant -f docker-compose.yml -f deploy/compose/public.yml -f deploy/compose/operations.yml build proxy prometheus alertmanager
```

默认产物为 `ai-order-assistant-proxy:latest`、`ai-order-assistant-prometheus:3.15.1-security1`、`ai-order-assistant-alertmanager:0.34.2-security1`。更换项目名时镜像前缀随之变化。三个镜像带 `org.opencontainers.image.revision` 和 `com.ai-order.security.x-net` 标签；实际修复结果由构建测试和运行镜像扫描确认。

```powershell
$env:CADDY_TEST_IMAGE = 'ai-order-assistant-proxy:latest'
python scripts/test-caddy-body-limit.py
python scripts/test-alert-delivery.py
python scripts/scan_deployment_images.py --env-file .env --output-directory security-reports
```

告警测试需要已构建的默认 Agent 镜像作为独立收件器，并验证 Prometheus/Alertmanager 页面、JavaScript 资源、PromQL、两份告警规则及 SMTP/Webhook 的 firing/resolved 收件。其他项目名通过 `--receiver-image`、`--prometheus-image`、`--alertmanager-image` 指定；测试使用独立内部网络，不读取业务卷或调用模型。完整部署扫描还要求相应网关、Agent 和 MySQL 镜像已构建。

CI 构建并验证这些镜像；安全工作流同时加载根、公网和 operations 配置，扫描三个实际运行镜像及 Java SBOM。扫描器不会为缺失的本地维护镜像退回到官方旧镜像。

此前安全扫描成功后，工作流上传 `infrastructure-images-<完整提交SHA>` 制品，保留 1 天。2026-11-09 的追加核验发现，该分发路径没有随附完整的第三方通知和对应源码材料，因此暂停生成新的镜像归档；扫描继续执行。历史归档及现有 Release、Maven 版本保持原样，逐层库存与材料补充正在核验。仅扫描成功不代表许可材料完整，不应将重新构建的镜像当作旧归档的同一制品。

`Inspect historical image distribution` 工作流读取 [历史工件清单](../licenses/historical-ci-images.json)，只解析归档和 Go 构建信息，不启动应用。检查范围包括最终文件系统及被覆盖但仍随镜像分发的下层程序、包清单和通知。输出元数据不包含可执行镜像载荷。完成材料门禁和实际下载核验后，再恢复镜像归档导出。

已有部署在验收通过后更新对应服务即可，保留全部数据卷。只启用本地模式时更新 Prometheus；公网和告警模式分别更新已有的 proxy、alertmanager。需要保持停止状态时使用 `docker compose ... up --no-start --no-deps --no-build --force-recreate <服务名>`。固定镜像发布前先构建或分发这些维护镜像，应用发布流程仍见 [接入指南](../docs/integrations.md#9-固定镜像发布与回滚)。
