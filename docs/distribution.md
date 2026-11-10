# 应用分发

应用通过 Gateway、Agent 两张 Docker/OCI 镜像交付；源码、运行配置与许可材料随对应 [Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.3) 提供。Maven 用于 Java 源码构建和测试，不再作为应用下载入口。

本轮镜像[发布运行42807884557](https://github.com/W205614/AI-Order-Assistant/actions/runs/42807884557)已推送并完成回拉。两张镜像均已用空Docker凭据目录实际匿名拉取完整SHA标签及仓库摘要，核对config、层字节和diffID，并以真实仓库摘要运行通过隔离就绪、四角色页面及平台登录/店铺读取检查；无需登录Registry。该轮不调用模型或写交易，完整验收与日常升级状态见[本轮验收](verification/2026-11-10-release.md)。

## 版本与镜像身份

平台为 **Linux amd64**。源码 Tag `v0.1.3` 指向 `a566819c115c25202a535aac0a20c5c96cedb462`；包装工作流来自 `ecb25026df0c5f6ae6ba5b7d396490b36dec2e3d`，没有重新构建应用。两张镜像的标签为这个完整源码 SHA，**没有 `:0.1.3` 镜像标签**；发布脚本拒绝覆盖同名不同制品。

| 服务 | 仓库 | 仓库 manifest 摘要 |
|---|---|---|
| Gateway | `ghcr.io/w205614/ai-order-assistant-gateway` | `sha256:500ab4cab22be85d0258459c44038aa495a82b26807fd3f48a9796eb3208972f` |
| Agent | `ghcr.io/w205614/ai-order-assistant-agent` | `sha256:075d8b8cb57ac5911aab38ada8d9399eced0e48118845ade59fe4a89818670c0` |

### GHCR 应用镜像

使用 `仓库:完整源码SHA` 或 `仓库@sha256:manifest摘要` 获取固定版本：

```powershell
docker pull --platform linux/amd64 ghcr.io/w205614/ai-order-assistant-gateway:a566819c115c25202a535aac0a20c5c96cedb462
docker pull --platform linux/amd64 ghcr.io/w205614/ai-order-assistant-agent:a566819c115c25202a535aac0a20c5c96cedb462

docker image inspect --format '{{json .RepoDigests}}' ghcr.io/w205614/ai-order-assistant-gateway:a566819c115c25202a535aac0a20c5c96cedb462
docker image inspect --format '{{json .RepoDigests}}' ghcr.io/w205614/ai-order-assistant-agent:a566819c115c25202a535aac0a20c5c96cedb462
```

公开 GHCR 包允许匿名获取；需要认证时按 [GitHub Container registry 说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)登录，不能把凭据写入仓库、Compose或示例。

拉取不会启动服务或替换现有容器。完整运行仍需 MySQL、Redis、Prometheus、迁移配置和私有 `.env`；这三个配套服务使用项目修补后的 Dockerfile，本项目未发布它们的 GHCR 包。应用镜像不携带业务数据库、账号、媒体或模型密钥，也不代表真实支付、短信或云环境已经验收。

### 镜像身份与使用验收

候选采用经核验的最终文件系统及运行 `Config` 制作单层镜像，避免附带已卸载安装器等旧层，并加入 `/licenses` 原始通知及固定对应源码入口。原始应用候选的隔离18项浏览器流程与后续最终GHCR运行分别记录，不将候选验收改称匿名拉取验收，也不扩展为真实外部渠道或其他架构的结论。

Docker save可能重新序列化配置JSON，因此运行配置字段相同，不保证配置字节或整个归档SHA相同。Docker29本轮实际本机image ID显示OCI manifest摘要：Gateway为 `500ab4...`、Agent为 `075d8b...`；它们的config digest分别为 `456b2eeadb56e8c4f0037241daea7aced5c813f224cecf172ac743eda2f580be` 和 `9087cd189b417366d95a55c3234f1926dafa726667fd6a7ce5926a41b377fff9`。工具沿实际描述链、按身份类型分别核对，不假设所有image ID都等于config SHA；拉取和升级使用上表完整仓库manifest摘要。源码SHA、config digest、diffID和归档SHA不能互相替代。

发布维护使用 [publish-containers.yml](../.github/workflows/publish-containers.yml) 和 [publish-container-images.py](../scripts/publish-container-images.py)：从审查过的固定候选取得相同制品，校验当前包装提交的 CI/安全结果及原应用 Tag，验证归档的每张镜像、单层文件系统、运行配置和完整摘要，再推送并按 registry 摘要回拉。它不会重新构建应用，也不会部署日常环境。公开可见性和匿名拉取另以 Packages 页面及最终获取回执确认。

## 首次镜像启动

此流程仅用于全新仓库目录与空业务卷。按 [README](../README.md#快速开始) 克隆稳定 Tag，在仓库根目录运行以下 PowerShell 命令；每一步成功后再继续。首次下载与配套服务构建需要联网，确保9090、19091端口空闲。

```powershell
$project = 'ai-order-assistant'
$envPath = Join-Path $PWD '.env'
$env:APP_REVISION = git rev-parse HEAD
.\scripts\initialize-env.ps1 -EnvPath $envPath
$gatewayImage = 'ghcr.io/w205614/ai-order-assistant-gateway@sha256:500ab4cab22be85d0258459c44038aa495a82b26807fd3f48a9796eb3208972f'
$agentImage = 'ghcr.io/w205614/ai-order-assistant-agent@sha256:075d8b8cb57ac5911aab38ada8d9399eced0e48118845ade59fe4a89818670c0'
docker pull --platform linux/amd64 $gatewayImage
if ($LASTEXITCODE -ne 0) { throw 'Gateway pull failed' }
docker pull --platform linux/amd64 $agentImage
if ($LASTEXITCODE -ne 0) { throw 'Agent pull failed' }
docker tag $gatewayImage "${project}-gateway:latest"
docker tag $agentImage "${project}-agent:latest"
docker compose --env-file $envPath -p $project -f docker-compose.yml build mysql redis prometheus
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure build failed' }
$images = docker compose --env-file $envPath -p $project -f docker-compose.yml config --images
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed' }
foreach ($image in ($images | Sort-Object -Unique)) {
    docker image inspect $image --format '{{.Id}}'
    if ($LASTEXITCODE -ne 0) { throw "Required image missing: $image" }
}
.\start.ps1 -ProjectName $project -EnvPath $envPath
```

这里把同一已拉取镜像赋予 Compose 需要的本地名称，migrate 与 Gateway 使用同一镜像。`latest` 只是启动别名，版本身份仍以上表仓库摘要核对。不要给这条镜像路线加 `-Build`；缺少可构建镜像时，即使不加它也可能自动构建，因此先准备全部镜像。源码 Docker 路线直接运行 `$env:APP_REVISION = git rev-parse HEAD` 和 `.\start.ps1 -Build`。

初始化脚本为缺失配置生成独立随机值，保留已有键，已有空值不会被重新生成。自定义 EnvPath 须先创建父目录。模型密钥可留空，普通点餐及模拟支付不依赖模型；`.env` 与生成账号不能公开。

`-ProjectName` 同时决定资源名和镜像前缀，`-p` 优先于 `COMPOSE_PROJECT_NAME`。Agent 的隐式镜像名为 `<项目>-agent:latest`；配套镜像为 `<项目>-mysql-runtime:8.4.13`、`<项目>-redis-runtime:8.10.3-security1`、`<项目>-prometheus:3.15.1-security1`。换项目名不会自动隔开端口、根 backups 和其他绑定路径；并行环境按[隔离配置](operations.md#启动与配置)处理。

### Linux 首次部署

取得同一源码 Tag 后，`cp .env.example .env`，将全部密码和认证密钥换成各自独立的随机值；模型 key 可空。Linux不会自动执行Windows的随机配置生成、先备份与账号重配。全新空库的应用镜像路线为：

```bash
export APP_REVISION="$(git rev-parse HEAD)"
gateway='ghcr.io/w205614/ai-order-assistant-gateway@sha256:500ab4cab22be85d0258459c44038aa495a82b26807fd3f48a9796eb3208972f'
agent='ghcr.io/w205614/ai-order-assistant-agent@sha256:075d8b8cb57ac5911aab38ada8d9399eced0e48118845ade59fe4a89818670c0'
docker pull --platform linux/amd64 "$gateway" && docker pull --platform linux/amd64 "$agent"
# 确认拉取成功后，依次执行：
docker tag "$gateway" ai-order-assistant-gateway:latest
docker tag "$agent" ai-order-assistant-agent:latest
docker compose --env-file .env -p ai-order-assistant -f docker-compose.yml build mysql redis prometheus
docker compose --env-file .env -p ai-order-assistant -f docker-compose.yml up --no-build -d --wait
```

源码Docker路线使用 `APP_REVISION="$(git rev-parse HEAD)" docker compose --env-file .env -p ai-order-assistant -f docker-compose.yml up --build -d --wait`。空库由MySQL初始化业务/迁移账号，migrate成功后网关才启动。已有卷不能直接套用首次步骤。本轮不据此声称macOS或arm64验收通过。

### 检查与停止

使用相同项目和配置执行 `docker compose --env-file .env -p ai-order-assistant -f docker-compose.yml ps --all`；五个常驻服务应正常，migrate应成功退出。打开 `http://127.0.0.1:9090/campus/`，就绪接口为 `/actuator/health/readiness`，Prometheus为 `http://127.0.0.1:19091/`。真实登录及代表订单按[第一笔订单](../README.md#体验第一笔订单)验证，页面可打开不等于业务通过。

查看日志用 `docker compose --env-file .env -p ai-order-assistant -f docker-compose.yml logs --tail 100 gateway agent migrate`；停止并保留数据用相同前缀执行 `stop`，不要删卷。拉取失败先核对平台、完整标签/摘要和访问条件，不以其他版本裸镜像替代。

## 已有数据的升级

从上面的拉取回执中选择各自完整的 `ghcr.io/...@sha256:<64位摘要>`，替换以下占位值。先准备配置覆盖，再使用真实备份与独立恢复报告部署：

```powershell
$gatewayImage = 'ghcr.io/w205614/ai-order-assistant-gateway@sha256:500ab4cab22be85d0258459c44038aa495a82b26807fd3f48a9796eb3208972f'
$agentImage = 'ghcr.io/w205614/ai-order-assistant-agent@sha256:075d8b8cb57ac5911aab38ada8d9399eced0e48118845ade59fe4a89818670c0'

python scripts/release.py prepare --project ai-order-assistant --env-file .env --gateway-image $gatewayImage --agent-image $agentImage
python scripts/release.py deploy --project ai-order-assistant --env-file .env --gateway-image $gatewayImage --agent-image $agentImage --backup-file 实际备份.sql --restore-report 实际独立恢复报告.json
```

存在媒体记录时还需匹配的 `--media-restore-report`。脚本复用既有业务卷、检查备份时效与指纹、执行一次性迁移，再启动应用；`prepare` 只准备与检查，不部署。公网、云配置和回滚参数见[固定镜像发布与回滚](integrations.md#9-固定镜像发布与回滚)。不得用无条件 `docker tag ... latest`、重置数据卷或空库代替该流程。

## 历史 Maven 入口迁移

当前应用入口已迁移为GHCR及源码启动，Maven仅用于本地Java构建和测试。v0.1.2曾发布薄JAR、POM及自身源码JAR；这是历史发表事实，不是v0.1.3的获取方式。旧v0.1.2应用包已纳入授权清退，具体执行与删除回执见 [v0.1.3 Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.3)；本文不再提供旧包获取教程。

正常历史Tag/Release、原JavaZIP、第三方源码、原始通知和恢复材料继续保留。旧版验证报告保留原测试与发表记录，由Release说明交代当前入口；不改写历史为从未发布Maven，也不因应用包退役删除对应源码。

## 容器的许可与对应源码

安全 CI 中可以下载的 Caddy、Prometheus、Alertmanager 镜像归档也属于分发。历史 7 份工件按每一层检查，包含已被后续 `COPY` 或包升级覆盖、仍实际存在于归档中的程序和包；仅检查最终 rootfs、根 MIT 或扫描报告不够。

[container-distribution.lock.json](../licenses/container-distribution.lock.json)固定实际组件与材料。历史基础材料由以下四份互补的Release资产提供，本轮再追加v0.1.3增量，不能只下载其中一份来替代完整清单：

| 标识 | 对应源码与原始通知 |
|---|---|
| `go-base` | [Go 基础材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-go-base-source-materials.tar.gz)：411 个模块版本项、主要程序源码、重建入口及已有工具链源码 |
| `ui` | [Web UI 材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-ui-source-materials.tar.gz)：Prometheus/Alertmanager 实际嵌入页面的来源、依赖锁和原始许可通知 |
| `os` | [OS/runtime 材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-os-source-materials.tar.gz)：基础层、BusyBox/uClibc、25 组 Caddy 系统源版本、Gateway/Agent 系统及语言运行时源码、配方和完整法律文本 |
| `go-additional` | [Go 追加材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.2/AI-Order-Assistant-0.1.2-go-additional-source-materials.tar.gz)：其余 8 个模块版本项和另一版工具链源码 |

这四份历史材料覆盖419个Go模块版本项及Go `1.26.8`、`1.26.10`、`1.27.3`三版标准库源码。本轮新增[release-delta源材料](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.3/AI-Order-Assistant-0.1.3-release-delta-source-materials.tar.gz)、[全文通知](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.3/AI-Order-Assistant-0.1.3-release-delta-NOTICES.txt)和[摘要清单](https://github.com/W205614/AI-Order-Assistant/releases/download/v0.1.3/AI-Order-Assistant-0.1.3-release-delta-source-materials.manifest.json)，源包SHA-256为 `a65624290909fbd83c56421f25334da99aa87347a08c6a97819bcb0e3e8243d0`。历史与增量共同覆盖当前锁定447个Go模块版本项，以及本轮新增Go、Java、OS/runtime对应源码和法律材料。

BusyBox材料保留精确上游镜像层映射、配置、补丁、Buildroot和uClibc。Java实际140个运行依赖中138个与旧材料字节相同，Okio/Kotlin两项升级补充精确材料；Agent的Python依赖、原生扩展及语言运行时按实际镜像核对。不能用基础设施Go清单替代Java/Python分发材料，也不因旧Maven入口清退删除这些材料。

维护时运行 [verify-container-distribution.py](../scripts/verify-container-distribution.py)，从上述固定 URL 下载并校验资产 SHA-256、内部逐文件摘要、原始通知和实际分发层。安全扫描及这项门禁均通过后，CI 才上传镜像归档与 `NOTICES.txt`、`source-materials.json`、`verification.json`；门禁不会因某个下载地址可访问或扫描全绿就跳过组件与源码匹配。

源码与法律材料的生成入口为 [fetch-distribution-materials.py](../scripts/fetch-distribution-materials.py)、[build-distribution-bundle.py](../scripts/build-distribution-bundle.py)及[源码准备工作流](../.github/workflows/prepare-license-materials.yml)。OS 与追加 Go 的原始输入分别由[OS 锁](../licenses/container-os-sources.lock.json)和[追加 Go 锁](../licenses/go-additional-source-inputs.json)固定，正文通知按原始字节去重并保留所有来源路径；JRE 法律例外、Go PATENTS 和系统通用许可证也在收集范围内。

历史 `legacy` scope保留OS/追加Go候选重建语义。本轮使用 `scope=release-delta`、[增量输入锁](../licenses/release-delta-source-inputs.json)及明确 `--version 0.1.3`；手动 `publish=true` 才允许向既有Release追加，可用成功 `prepared_run_id` 推广同仓库、同工作流的原候选，不重建另一份、不自动创建Tag/Release。发布前核对当前锁，同名不同字节必须失败。对应源码、通知、锁和回执持续保留，不因CI工件到期或普通缓存清理而删除。

## 发布维护

1. 对干净应用源码运行适用测试、安全和质量检查，固定源码Tag、制品摘要及对应源码/通知；全部适用严重级别未解决告警或扫描错误均阻止安全门禁。
2. 完成相应分支/标签CI及许可门禁后，推广同一已验候选至两个GHCR仓库的完整源码SHA标签，不重新构建应用、不覆盖已有公开版本。
3. 实际匿名拉取标签与摘要，核对OCI描述链、config、层字节与运行配置，完成适用平台的隔离运行及约定本机环境验收。
4. 更新并远端核验文档引用后，由发布职责清退原计划内旧Maven应用包，再复核远端；正常历史版本、附件和对应源码保留。

文档提交可晚于镜像来源，不能为使SHA相同重打Tag或重复发布。原Maven应用发布脚本和工作流已从当前工程回收，Git历史保留其实现；它们不是当前下载或发布入口。

旧版本保留，不重打 Tag 或覆盖历史附件。权限或材料不齐时如实记录受阻项，不将列表查询失败推断为没有包或无法推送。

本机日常升级也可向 `scripts/release.py` 传入已有候选的完整 `sha256:<64位镜像ID>`，它不会拉取或重新构建本地镜像；registry 镜像仍使用 `仓库@sha256:<摘要>`。两条路径都要求匹配的数据库备份及独立恢复证据，不接受可变标签，也不会跳过迁移和数据保留门禁。本机镜像与公开应用包的身份及实际来源另留验收记录，不将本地镜像当作已经公开发布的镜像包。
