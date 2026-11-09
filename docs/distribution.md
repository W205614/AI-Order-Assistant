# 应用分发

源码及应用下载入口为 [GitHub Releases](https://github.com/W205614/AI-Order-Assistant/releases)。应用包、Maven 包、源码及本机镜像分别核对提交和 SHA-256，名称或版本一致不能替代内容核验。

已公开版本、资产摘要和可见性以 [v0.1.2 Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.2)、[Packages](https://github.com/W205614/AI-Order-Assistant/packages) 及对应工作流回执为准。下面记录维护与获取契约；配置了发布目标或完成候选验证，不等于该目标已经公开可拉取。

## GHCR 应用镜像

Gateway、Agent 分别使用以下目标，平台为 **Linux amd64**。应用来源为 `v0.1.2` 剥离后的完整提交 `1a368baeb5c351764135754c089b2fe802ce56ba`；发布脚本拒绝覆盖已经指向不同配置摘要的同名 SHA 标签。

| 服务 | 完整来源标签 |
|---|---|
| Gateway | `ghcr.io/w205614/ai-order-assistant-gateway:1a368baeb5c351764135754c089b2fe802ce56ba` |
| Agent | `ghcr.io/w205614/ai-order-assistant-agent:1a368baeb5c351764135754c089b2fe802ce56ba` |

先在 Packages 页面核对对应版本和获取条件，再拉取：

```powershell
docker pull --platform linux/amd64 ghcr.io/w205614/ai-order-assistant-gateway:1a368baeb5c351764135754c089b2fe802ce56ba
docker pull --platform linux/amd64 ghcr.io/w205614/ai-order-assistant-agent:1a368baeb5c351764135754c089b2fe802ce56ba

docker image inspect --format '{{json .RepoDigests}}' ghcr.io/w205614/ai-order-assistant-gateway:1a368baeb5c351764135754c089b2fe802ce56ba
docker image inspect --format '{{json .RepoDigests}}' ghcr.io/w205614/ai-order-assistant-agent:1a368baeb5c351764135754c089b2fe802ce56ba
```

公开可拉取的 GHCR 包支持匿名获取；需要认证的包按 [GitHub Container registry 说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)执行 `docker login ghcr.io`，使用具备读取权限的凭据。不要把凭据写入仓库、Compose 文件或示例命令。Maven 的认证条件另见下节。

拉取不会启动服务，也不会替换现有容器。两张应用镜像仍需仓库中的 MySQL、Redis、Prometheus、迁移配置和私有 `.env`；AI 对话还依赖模型配置并可能产生费用。它们不携带业务数据库、账号、媒体或模型密钥，也不代表真实支付、短信、云环境已经验收。首次使用可沿 README 的全新部署流程准备环境；已有数据按下面的升级步骤处理。

### 镜像身份与使用验收

候选采用已验收的最终文件系统及运行 `Config` 制作单层镜像，避免附带已卸载安装器等旧层。最终文件系统和运行配置的等价性已核对，隔离环境的 18 项浏览器流程通过；首次失败与后续修正报告分别保留。这项验证不扩展为真实外部渠道或其他架构的验收。

Docker save 可能重新序列化配置 JSON，因此运行配置字段相同，不保证配置字节、归档 SHA 或 image ID 始终相同。发布以最终候选归档的配置字节计算 image ID，并核对实际 registry 配置摘要；拉取和升级以 `仓库@sha256:<manifest摘要>` 为身份。源码 SHA 标签、image ID、registry manifest 摘要和整个归档 SHA 是不同对象，不互相替代。

发布维护使用 [publish-containers.yml](../.github/workflows/publish-containers.yml) 和 [publish-container-images.py](../scripts/publish-container-images.py)：从审查过的固定候选取得相同制品，校验当前包装提交的 CI/安全结果及原应用 Tag，验证归档的每张镜像、单层文件系统、运行配置和完整摘要，再推送并按 registry 摘要回拉。它不会重新构建应用，也不会部署日常环境。公开可见性和匿名拉取另以 Packages 页面及最终获取回执确认。

### 已有数据的升级

从上面的拉取回执中选择各自完整的 `ghcr.io/...@sha256:<64位摘要>`，替换以下占位值。先准备配置覆盖，再使用真实备份与独立恢复报告部署：

```powershell
$gatewayImage = 'ghcr.io/w205614/ai-order-assistant-gateway@sha256:替换为实际64位manifest摘要'
$agentImage = 'ghcr.io/w205614/ai-order-assistant-agent@sha256:替换为实际64位manifest摘要'

python scripts/release.py prepare --project ai-order-assistant --env-file .env --gateway-image $gatewayImage --agent-image $agentImage
python scripts/release.py deploy --project ai-order-assistant --env-file .env --gateway-image $gatewayImage --agent-image $agentImage --backup-file 实际备份.sql --restore-report 实际独立恢复报告.json
```

存在媒体记录时还需匹配的 `--media-restore-report`。脚本复用既有业务卷、检查备份时效与指纹、执行一次性迁移，再启动应用；`prepare` 只准备与检查，不部署。公网、云配置和回滚参数见[固定镜像发布与回滚](integrations.md#9-固定镜像发布与回滚)。不得用无条件 `docker tag ... latest`、重置数据卷或空库代替该流程。

## Maven Packages

Java 包坐标为 `com.ai:assistant-gateway`，仓库为 `https://maven.pkg.github.com/w205614/AI-Order-Assistant`。这个包包含本项目编译后的类和静态页面，依赖由同版本 POM 声明，不将第三方依赖嵌入 Maven JAR；`sources` 附件提供本项目 Java 源码。Agent 使用同版本源码归档中的 `agent-service/` 及两份 requirements 文件，不发布到不支持 PyPI 的 GitHub Packages。

GitHub Maven registry 的公开包下载仍需认证，使用者需自行配置具备 `read:packages` 的 personal access token (classic)。配置放在用户 Maven `settings.xml` 中，不写入源码或命令行。具体步骤见 [GitHub 官方说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)。服务端 ID 使用 `github`。

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/w205614/AI-Order-Assistant</url>
</repository>
```

获取指定版本后，可将本项目 JAR 与按 POM 解析的运行依赖放入 Java classpath，主类为 `com.ai.assistant.AssistantGatewayApplication`。完整运行仍需 MySQL、Redis、Agent 及 README 中的配置；Maven 包本身不包含这些服务或任何账号。

配置上述认证后，可下载 `0.1.2`：

```powershell
mvn dependency:get "-Dartifact=com.ai:assistant-gateway:0.1.2" "-DremoteRepositories=github::default::https://maven.pkg.github.com/w205614/AI-Order-Assistant"
```

Maven 分发继续保留；使用 GHCR 不改变这个包的坐标、原版本或认证要求。

## 容器的许可与对应源码

安全 CI 中可以下载的 Caddy、Prometheus、Alertmanager 镜像归档也属于分发。历史 7 份工件按每一层检查，包含已被后续 `COPY` 或包升级覆盖、仍实际存在于归档中的程序和包；仅检查最终 rootfs、根 MIT 或扫描报告不够。

[container-distribution.lock.json](../licenses/container-distribution.lock.json)要求以下四份互补的固定 Release 资产，缺少任何一份都不能完成基础设施分发门禁：

| 标识 | 对应源码与原始通知 |
|---|---|
| `go-base` | [Go 基础材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-go-base-source-materials.tar.gz)：411 个模块版本项、主要程序源码、重建入口及已有工具链源码 |
| `ui` | [Web UI 材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-ui-source-materials.tar.gz)：Prometheus/Alertmanager 实际嵌入页面的来源、依赖锁和原始许可通知 |
| `os` | [OS/runtime 材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-os-source-materials.tar.gz)：基础层、BusyBox/uClibc、25 组 Caddy 系统源版本、Gateway/Agent 系统及语言运行时源码、配方和完整法律文本 |
| `go-additional` | [Go 追加材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-go-additional-source-materials.tar.gz)：其余 8 个模块版本项和另一版工具链源码 |

合计覆盖 419 个 Go 模块版本项，以及 Go `1.26.8`、`1.26.10`、`1.27.3` 三版标准库源码。BusyBox 材料还保留精确上游镜像层映射、配置、补丁、Buildroot 和 uClibc 源码。Java 应用包的 140 项依赖材料与 Agent 的第三方依赖、原生扩展材料另按实际应用制品核对，不能用这张基础设施清单互相替代。

维护时运行 [verify-container-distribution.py](../scripts/verify-container-distribution.py)，从上述固定 URL 下载并校验资产 SHA-256、内部逐文件摘要、原始通知和实际分发层。安全扫描及这项门禁均通过后，CI 才上传镜像归档与 `NOTICES.txt`、`source-materials.json`、`verification.json`；门禁不会因某个下载地址可访问或扫描全绿就跳过组件与源码匹配。

源码与法律材料的生成入口为 [fetch-distribution-materials.py](../scripts/fetch-distribution-materials.py)、[build-distribution-bundle.py](../scripts/build-distribution-bundle.py)及[源码准备工作流](../.github/workflows/prepare-license-materials.yml)。OS 与追加 Go 的原始输入分别由[OS 锁](../licenses/container-os-sources.lock.json)和[追加 Go 锁](../licenses/go-additional-source-inputs.json)固定，正文通知按原始字节去重并保留所有来源路径；JRE 法律例外、Go PATENTS 和系统通用许可证也在收集范围内。

普通 push 只准备和保存候选。手动 `publish=true` 才允许 [publish-source-materials.py](../scripts/publish-source-materials.py)追加到已有 Release；可提供 `prepared_run_id` 推广同仓库、同工作流的成功候选，不重建另一份。发布前仍检查当前锁摘要，已有同名资产必须逐字节摘要相同，否则失败。原 `v0.1.2` Tag 和原六项应用附件保持，许可补件只追加。对应源码、原始通知、锁和回执是交付材料，应持续保存，不能因 CI 工件过期或普通缓存清理而删除固定 Release 源码资产。

## 发布维护

1. 最终干净提交按现有流程构建、测试；`scripts/prepare-maven-package.py --output <新目录>` 从 Maven 的 `.jar.original` 生成应用 JAR、源码 JAR、POM 和摘要清单，并附带项目许可。
2. 运行制品及必要第三方分发材料独立核对；Maven 包不携带第三方二进制，不代表包含第三方依赖的可执行 JAR 自动满足分发要求。
3. 将已验收的候选资产及 `maven-manifest.json` 上传到同版本 Release 草稿。分支及标签的 CI、安全工作流均成功后，复核并正式发布 Release，再手动在所属仓库运行 `Publish verified Maven package`，输入该补丁标签。
4. 工作流使用限定为 `contents: read`、`actions: read`、`packages: write` 的仓库 `GITHUB_TOKEN`，核对标签、源码和候选摘要，使用 Maven deploy-file 上传同一候选，拒绝覆盖已有包版本。上传后实际下载 JAR、sources 和 POM，逐一比对 SHA-256，回执保存在 Actions artifact。工作流不会构建另一份产物，也不会部署应用。
5. 回读 Release 与包版本，保留摘要及使用验收记录，再按约定更新本机日常环境。发包失败时保留已公开的 Release，不覆盖或删除历史资产，并将总体状态标为部分完成。

旧版本保留，不重打 Tag 或覆盖历史附件。权限或材料不齐时如实记录受阻项，不将列表查询失败推断为没有包或无法推送。

本机日常升级也可向 `scripts/release.py` 传入已有候选的完整 `sha256:<64位镜像ID>`，它不会拉取或重新构建本地镜像；registry 镜像仍使用 `仓库@sha256:<摘要>`。两条路径都要求匹配的数据库备份及独立恢复证据，不接受可变标签，也不会跳过迁移和数据保留门禁。本机镜像与公开应用包的身份及实际来源另留验收记录，不将本地镜像当作已经公开发布的镜像包。
