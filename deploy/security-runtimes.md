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

`infrastructure-images-<完整提交SHA>` 是可下载的二进制分发，保留期为 1 天。安全工作流先扫描实际镜像，再用 [verify-container-distribution.py](../scripts/verify-container-distribution.py)核验实际归档与[分发锁](../licenses/container-distribution.lock.json)；两项都通过才上传 `infrastructure-images.tar.gz`、`NOTICES.txt`、`source-materials.json` 和 `verification.json`。缺失或未覆盖的层、Go 模块、OS 库存、原始通知及对应源码会阻止导出；扫描全绿不代替这项门禁。

`Inspect historical image distribution` 工作流读取 [历史工件清单](../licenses/historical-ci-images.json)，已对列出的 7 份实际归档取证；检查仅解析归档和 Go 构建信息，不启动应用。范围包含最终文件系统及被覆盖但仍分发的下层程序、包清单和通知，不能用同一提交的另一份重建镜像替代原归档。输出元数据不包含可执行镜像载荷。

逐层材料由 `go-base`、`ui`、`os`、`go-additional` 四份固定 Release 资产共同提供，缺一不可。Go 材料含 419 个模块版本项与 Go 1.26.8、1.26.10、1.27.3 源码；UI 材料保留实际嵌入资源的源码、依赖锁与原始通知；OS 材料含 25 组 Caddy 系统源版本，以及 BusyBox/uClibc、对应 Buildroot 配置和 Debian 附加数据来源。Prometheus/Alertmanager 的实际 BusyBox 基础层通过上游 rootfs diffID 精确映射，不能仅凭镜像 Apache 标签判断其许可。四份资产的固定 URL、SHA-256、内部清单和覆盖关系在分发锁中保存，获取方法见[应用分发](../docs/distribution.md#容器的许可与对应源码)。

构建输入或依赖变化后，先更新相应来源材料、通知和锁，再对新实际镜像执行门禁。历史 7 份工件的取证结论只覆盖已记录层；新的层、工具链或 Go 模块不得自动沿用旧结论。源码资产及原始通知持续保留，即使旧 CI 归档到期也不删除对应 Release 补件。正式公开状态查看 [Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.2) 与对应 Actions 回执；旧 Tag、Java/Maven 版本和原六项应用附件不重写。

维护者可对已经生成的归档独立运行材料核验，不启动业务服务：

```powershell
python -B scripts/verify-container-distribution.py --archive .local/infrastructure-images.tar.gz --lock licenses/container-distribution.lock.json --output .local/infrastructure-distribution --write-notices
```

该命令会从固定 Release 资产下载相应源码材料并逐项验摘要，输出完整原始通知和源码入口；只读取归档，不重新构建另一份镜像。

已有部署只更新实际启用且已经验收的服务，保留全部数据卷；本地监控、公网和独立告警分别涉及 Prometheus、proxy、alertmanager。先核对要部署的不可变摘要、配置与数据兼容性，再按约定迁移和恢复策略操作，完成后恢复原有运行或停止状态。应用 Gateway/Agent 的 GHCR 获取、候选身份和 `scripts/release.py` 备份/独立恢复门禁见[应用分发](../docs/distribution.md#已有数据的升级)及[接入指南](../docs/integrations.md#9-固定镜像发布与回滚)，不通过覆盖日常 `latest` 标签绕过升级流程。
