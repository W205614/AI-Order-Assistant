# Caddy 与监控镜像的安全构建

三个运行镜像使用 `golang.org/x/net v0.62.0`，修复 [CVE-2026-78661 / GO-2026-6860](https://pkg.go.dev/vuln/GO-2026-6860)；`x/crypto` 固定到 `v0.58.1-0.20261009181718-2f1f834330b2`，修复 [GO-2026-5932](https://pkg.go.dev/vuln/GO-2026-5932)。Caddy为2.11.9，两个监控组件版本及现有数据格式/入口保留；当前Dockerfile以Go1.27.4重建程序及标准库，编译器仅在构建阶段使用。

| 组件 | 版本与构建入口 | 上游来源 |
|---|---|---|
| Caddy | 2.11.9，[Dockerfile](caddy/Dockerfile) | [标准模块](caddy/main.go)及 [Go依赖锁](caddy/go.mod)，使用上游go-jose/v4 4.1.9及关联证书库修复，不再携带旧v3回移补丁 |
| Prometheus / promtool | 3.15.1，[Dockerfile](prometheus/Dockerfile) | [固定提交 bae3eced](https://github.com/prometheus/prometheus/tree/bae3eced0a5369e050e83ea73bc381d7c4b1a719)及同版官方 Web UI |
| Alertmanager / amtool | 0.34.2，[Dockerfile](alertmanager/Dockerfile) | [固定提交 e8349048](https://github.com/prometheus/alertmanager/tree/e8349048265ccc0871c3d0d668b05eacc68baf50)及同版官方 Web UI |

Prometheus和Alertmanager固定源码提交、归档SHA-256、同版官方Web UI、Go工具链和运行基础摘要。通过Go模块工具更新 `x/net`、`x/crypto` 及必要传递依赖，校验模块并运行上游HTML解析和bcrypt测试。Prometheus先运行 `scripts/compress_assets.sh` 再启用 `builtinassets`；两个服务均嵌入真实页面资源。最终替换服务和配套命令两个二进制，继承官方用户、入口、配置、存储路径及原始许可证。

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

CI构建并验证这些镜像；安全工作流同时加载根、公网和operations配置，覆盖实际服务镜像及Java SBOM。门槛包含UNKNOWN、LOW、MEDIUM、HIGH、CRITICAL，任一未解决发现或扫描错误均失败，不使用ignore-unfixed隐藏问题。扫描器不会为缺失的本地维护镜像退回官方旧版。默认MySQL与Redis也使用[项目修补镜像](../docs/operations.md#公网与认证)，不是另外公开发布的GHCR包。

`infrastructure-images-<完整提交SHA>` 是可下载的二进制分发，保留期为 1 天。安全工作流先扫描实际镜像，再用 [verify-container-distribution.py](../scripts/verify-container-distribution.py)核验实际归档与[分发锁](../licenses/container-distribution.lock.json)；两项都通过才上传 `infrastructure-images.tar.gz`、`NOTICES.txt`、`source-materials.json` 和 `verification.json`。缺失或未覆盖的层、Go 模块、OS 库存、原始通知及对应源码会阻止导出；扫描全绿不代替这项门禁。

`Inspect historical image distribution` 已改为仅显式手动触发，读取[历史清单](../licenses/historical-ci-images.json)。原7份实际归档的完整取证及60个唯一层证据保留，范围包含被覆盖但仍分发的下层程序；不启动应用，也不以另一份重建替代原归档。短期工件到期后的404与原失败状态按边界记录，不意味着固定Release对应源码丢失；输出元数据不含可执行镜像载荷。

历史逐层材料由v0.1.2的 `go-base`、`ui`、`os`、`go-additional` 提供，覆盖419个Go模块版本项与Go1.26.8、1.26.10、1.27.3源码；v0.1.3历史 `release-delta` 追加Go/Java/OS材料，共同覆盖当前锁定447个Go模块版本项。Go1.27.4完整源码、原文通知及固定构建配方另由[工具链输入锁](../licenses/go-toolchain-source-inputs.json)和日期命名的Release补件固定。UI保留真实嵌入资源、依赖锁及通知；BusyBox/uClibc、Buildroot等基础层依实际diffID映射，不能凭镜像Apache标签判断整层许可。固定URL、摘要、清单和逐层关系见分发锁及[对应源码入口](../docs/distribution.md#容器的许可与对应源码)。

输入或依赖变化后，先更新材料、通知和锁，再对新实际镜像执行门禁。历史结论只覆盖已记录层，不能自动沿用到新输入。对应源码与原始通知持续保留，不随CI归档到期删除。上述基础设施增量和Go1.27.4补件仍位于 [v0.1.3 Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.3) 的固定材料入口；当前应用另有[v0.1.4分发材料](../docs/distribution.md)。旧Tag、原始验证及必要历史附件不重写，历史Maven入口迁移与删除回执见[应用分发](../docs/distribution.md#历史-maven-入口迁移)。

维护者可对已经生成的归档独立运行材料核验，不启动业务服务：

```powershell
python -B scripts/verify-container-distribution.py --archive .local/infrastructure-images.tar.gz --lock licenses/container-distribution.lock.json --output .local/infrastructure-distribution --write-notices
```

该命令会从固定 Release 资产下载相应源码材料并逐项验摘要，输出完整原始通知和源码入口；只读取归档，不重新构建另一份镜像。

已有部署只更新实际启用且已经验收的服务，保留全部数据卷；本地监控、公网和独立告警分别涉及 Prometheus、proxy、alertmanager。先核对要部署的不可变摘要、配置与数据兼容性，再按约定迁移和恢复策略操作，完成后恢复原有运行或停止状态。应用 Gateway/Agent 的 GHCR 获取、候选身份和 `scripts/release.py` 备份/独立恢复门禁见[应用分发](../docs/distribution.md#已有数据的升级)及[接入指南](../docs/integrations.md#9-固定镜像发布与回滚)，不通过覆盖日常 `latest` 标签绕过升级流程。
