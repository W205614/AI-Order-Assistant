# 第三方代码与运行组件

本项目的 MIT 许可覆盖原创部分。第三方文件、依赖和容器基础组件保留原有版权与许可，不因根目录 LICENSE 而改变。精确版本与分发范围以本文引用的锁、实际制品及原始来源材料核对。

## 仓库直接包含的第三方内容

| 内容 | 来源与许可 | 保留方式 |
|---|---|---|
| `java-gateway/src/main/resources/static/assets/vendor/qrcodegen.js` | [Project Nayuki](https://github.com/nayuki/QR-Code-generator)，MIT，Copyright (c) Project Nayuki | 原文件含完整版权和许可；[vendor 索引](java-gateway/src/main/resources/static/assets/vendor/README.md)记录下载来源与摘要，静态检查核验原始字节 |

历史 go-jose v3 派生补丁保留上游声明：Copyright 2014 Square Inc.。0.1.3 已迁移到上游 jose/v4，活跃构建不再使用该补丁；[历史补丁说明](https://github.com/W205614/AI-Order-Assistant/blob/2e974980a637b598add862f939ce92e1bd5f6d9d/deploy/caddy/patches/README.md)、完整 Apache-2.0 文本及原对应源码仍在历史提交和原 `go-base` 来源包中保留。

`docs/screenshots/` 为项目已有演示场景截图；本轮未新增或替换截图，未将其当作本轮浏览器测试的取证图。页面其余图形为仓库内 SVG、CSS 及文字；本轮未引入外部图片或字体。

## 应用依赖与构建产物

Java 的精确依赖以 [pom.xml](java-gateway/pom.xml)、Maven 解析结果及打包生成的 CycloneDX SBOM 为准；运行镜像内为 `/app/runtime-bom.json`。Python 的完整固定版本见 [requirements.lock.txt](agent-service/requirements.lock.txt)。不得把本表当成传递依赖的完整清单。

| 主要组件 | 上游许可入口 |
|---|---|
| Spring Boot / Spring Framework / Spring Security、微信支付 Java SDK、阿里云 SMS 与 OSS SDK | Apache-2.0；保留依赖内已有声明，并在分发材料中补齐未内嵌的许可及通知；SDK 来源见[接入指南](docs/integrations.md#10-接入验收记录) |
| FastAPI、LangGraph | MIT；安装包保留各自许可与版权；[FastAPI](https://github.com/fastapi/fastapi/blob/master/LICENSE)、[LangGraph](https://github.com/langchain-ai/langgraph/blob/main/LICENSE) |
| OpenAI Python SDK 3.42.0 | Apache-2.0；按锁定版本的[完整许可](https://github.com/openai/openai-python/blob/v3.42.0/LICENSE)核对，不能沿用旧版 MIT 的描述 |
| MySQL Connector/J | [GPL-2.0 及 Universal FOSS Exception](https://github.com/mysql/mysql-connector-j/blob/release/9.x/LICENSE)；原许可证和例外随依赖保留，未将其重许可为 MIT |

当前 `v0.1.4` 通过 Gateway、Agent 两张 GHCR Docker/OCI 镜像交付，获取方式、源码标识及对应源码见[应用分发](docs/distribution.md)。`v0.1.0`、`v0.1.1` 仅分发源码；`v0.1.2` 曾分发源码、JavaZIP、Maven薄JAR/POM/自身源码JAR及两张GHCR镜像，历史事实保留。旧Maven应用包已纳入授权清退，具体执行与删除回执见 [v0.1.3 Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.3)；第三方源码、通知及必要历史附件不随应用入口退役而删除。Agent源码安装使用两份requirements；容器另核对实际Python包、原生扩展和运行时。Spring Boot可执行JAR与历史Maven薄JAR的依赖范围不同，不能混用许可清单。

Java Release 应用包须随附实际嵌套 JAR 的摘要与版本清单、原始 LICENSE / NOTICE / COPYRIGHT / `about.html`、补充许可文本和来源记录。部分上游 JAR 没有独立许可文件，不能据此认为无署名要求；对应 Maven 源码 JAR 保留上游源码声明，Netty 与 Kotlin 的补充通知按实际版本保留。SBOM 包含的解析组件与最终嵌套 JAR 数量可能不同，分发核对以实际包内容为准。

下列组件还须提供对应源码及构建入口；应用包内保留原许可，不将它们重许可为 MIT：

| 实际运行组件 | 许可选择及源码材料 |
|---|---|
| MySQL Connector/J 9.7.0 | GPL-2.0，附 Universal FOSS Exception 1.0；完整许可在其 JAR 根目录 `LICENSE`，对应 Maven 源码和[同版完整源码](https://github.com/mysql/mysql-connector-j/tree/9.7.0)一同提供 |
| JaCoCo agent 0.8.4 | EPL-1.0；保留 `about.html` 中的自身及 ASM 声明，并提供[同版完整源码](https://github.com/jacoco/jacoco/tree/v0.8.4)；这是上游 SDK 引入的运行依赖，不是本项目测试通过的证明 |
| Logback core / classic 1.5.38 | 本发行选择其双许可中的 EPL-2.0；提供对应 Maven 源码和[完整源码](https://github.com/qos-ch/logback/tree/v_1.5.38)，保留上游双许可声明 |
| Jakarta Annotations API 3.0.0 | 本发行选择 EPL-2.0；保留 JAR 内的 LICENSE / NOTICE，并提供对应 Maven 源码和[完整源码](https://github.com/jakartaee/common-annotations-api/tree/3.0.0) |

`redis-authx-core` 0.1.1-beta2 的 POM 许可链接现返回 404；其 Maven 源码明确声明 MIT 及 `Copyright 2024, Redis Ltd. and Contributors`。分发材料保留该源码声明并附完整 MIT 文本，不把 Redis Java 客户端组件的 MIT 与 Redis Server 的许可混为一谈。外部依赖未被本项目修改；应用自身修改见版本对应源码。

## Docker 运行组件

| 组件 | 来源与许可边界 |
|---|---|
| Caddy、Prometheus、Alertmanager | 主要程序采用 Apache-2.0；[安全构建说明](deploy/security-runtimes.md)固定上游源码和 Web UI，传递 Go 模块、UI 依赖与 OS 层分别保留独立许可和通知，不能只沿用镜像标签上的 Apache 声明 |
| gosu | [Apache-2.0](https://github.com/tianon/gosu/blob/29b939aa5bd4dc588af765b8248f6e39150d2565/LICENSE)，按固定源码重编译；Dockerfile 记录来源提交 |
| MySQL Server | 官方镜像及其 GPL-2.0 等组件许可适用，参见[上游 Docker 源码](https://github.com/docker-library/mysql)及镜像内许可 |
| Redis 8 | [上游三许可选择](https://redis.io/legal/licenses/)含 AGPLv3、RSALv2、SSPLv1；镜像不属于本项目 MIT 代码。部署或再分发时按具体使用方式选择并履行适用许可 |
| Eclipse Temurin | 按实际 JRE 保留 GPL-2.0、Classpath Exception、Assembly Exception、ADDITIONAL_LICENSE_INFO 及模块内第三方通知，提供相应完整源码与构建配置；固定基础镜像见 Gateway Dockerfile |
| CPython、Alpine / Ubuntu 系统包 | CPython 保留其完整 PSF 及兼容许可；系统包按实际 origin/source/version 提供原许可、配方、补丁与必要对应源码。Agent 的 readline、gdbm 等有独立 GPL 条件，不能统一标成 PSF 或 MIT |
| BusyBox / uClibc | Prometheus/Alertmanager 实际基础层为 BusyBox 1.38.0 静态 uClibc 变体；保留 GPL-2.0 / LGPL-2.1 许可、BusyBox 与 uClibc 源码、精确 Buildroot 配置和构建配方。Caddy 的 Alpine BusyBox 另按其 APK 版本核对 |

`v0.1.2` 的 Java 应用 ZIP 不包含 OS、JRE 或基础设施镜像；容器的相应材料独立提供。CI 的 Caddy、Prometheus、Alertmanager 归档同样属于二进制分发。历史 7 份归档逐层核对，包含被覆盖但仍实际随镜像下载的旧二进制、系统包及原始通知；仅查看最终文件系统或 SBOM 不能覆盖这部分内容。

[container-distribution.lock.json](licenses/container-distribution.lock.json)保留 `go-base`、`ui`、`os`、`go-additional` 四份历史 Release 源码材料，并追加 `release-delta-0.1.3`。原材料覆盖 411+8 个 Go 模块版本项、三版 Go 标准库、两个实际 Web UI、BusyBox/uClibc 源码与构建配方，以及历史系统源版本；新增 28 个 Go 模块版本后共 447 项。当前构建目录逐文件固定，原 v3 补丁材料仍保留用于历史层，不再列为当前构建输入。

## 0.1.3 运行时源材料增量

[release-delta-source-inputs.json](licenses/release-delta-source-inputs.json)固定新增 Go、Alpine/JRE、Java 与 GNU runtime 的 289 项输入。完整来源包、原始通知和逐成员清单分别为 `AI-Order-Assistant-0.1.3-release-delta-source-materials.tar.gz`、`AI-Order-Assistant-0.1.3-release-delta-NOTICES.txt` 和同名 manifest，发布到 [v0.1.3 Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.3)。镜像发布前必须验证这些实际公开资产；保留原 v0.1.2 包及其摘要，不覆盖附件。

Gateway 的 140 项实际嵌套依赖中，138 项与旧 Java ZIP 完全一致；Okio JVM 3.4.0、Kotlin common 1.8.0 的完整源码和原始声明在新增量包中。Temurin Alpine JRE 25.0.5+8 与官方 musl 二进制逐文件对应，复用旧包中的同版完整 OpenJDK 源码及 184 项相同 legal 内容，新增实际 Alpine recipe/source 与构建入口。

Agent 的 Python 分发及原生扩展保持原字节；复核发现五个 wheel 路径携带 GNU `libgcc_s`，不能由 450 项 Rust 来源集合代替。新增量补齐 GCC 12.4 完整源码、GPL-3.0 与 GCC Runtime Library Exception 3.1、实际供应工具链及后处理 recipe/patches；[原生组件对应表](licenses/agent-native-runtime-sources.json)记录五路径、两组完整摘要和公共来源。此补件也适用于 v0.1.2 中完全相同的 GNU 库，不修改原发行或声称历史可变标签、完整文件及位级重建均相同。

Gateway/Agent 候选还核对 Java 依赖、Python 包及原生扩展、OS、JRE、CPython 和内嵌字体。应用候选采用最终文件系统与运行配置等价的单层制品，其实际来源摘要按候选归档和 registry 配置字节核验；不能要求原本机 image ID 在不同序列化方式下始终相同。

后续基础设施归档必须同时通过安全扫描与实际分发材料门禁，随附完整 `NOTICES.txt`、固定源码入口及逐项验证结果。根 MIT、上游主程序的 Apache-2.0、资产 URL 可访问或扫描成功，均不能代替各组件的许可与对应源码核对。源码和原始通知是持续保留的发行材料；历史 Tag 与原附件不覆盖，新增许可材料只追加到对应 Release。

## master 依赖更新（2026-11-12，v0.1.4 发行前记录）

源码更新了 Okio 3.18.2、FastAPI 0.162.2、Pydantic 2.14.3 / pydantic-core 2.50.4、LangGraph SDK 0.4.14、Click 8.5.1 和 websockets 17.4，以及三项 GitHub Actions。MySQL 仍使用 8.4.13；首阶段仅更新 Go 1.27.3 构建基础摘要时，实际 gosu 1.20 与已发布运行时的二进制摘要相同；后续 Go 1.27.4 安全修复另见下段。Gateway 仅为现有 Temurin 摘要补充 `25-jre-alpine` 标签，运行时内容不变。

这些是默认分支的源码与测试更新，没有替换 v0.1.3 Tag、镜像或对应材料。上节的依赖版本和源包范围仍专指已发布的 v0.1.3 制品；后续分发新构建时，应按实际解析的 Java/Python 依赖和镜像层重新核对并提供对应材料，不能用旧版清单证明新二进制已完成许可验收。

同期安全扫描识别到 Go 1.27.3 的 `CVE-2026-97054` 后，用户另行授权将 Caddy、Prometheus、Alertmanager 与 MySQL/gosu 的构建工具链更新为 Go 1.27.4。`licenses/go-toolchain-source-inputs.json` 绑定完整官方源码及 13 份实际构建输入；新增日期命名的 `AI-Order-Assistant-go1.27.4-20261112-source-materials.tar.gz` 补件提供原始 LICENSE/NOTICE 与固定配方，中央分发锁追加该补件并保留历史记录。该补件服务于尚未另行发行的默认分支基础设施构建，不意味着已发布 v0.1.3 应用或本机日常镜像已更新，也不替代新 Java/Python 应用依赖的独立分发材料核对。

## 0.1.4 应用依赖源材料增量

[application-0.1.4-source-inputs.json](licenses/application-0.1.4-source-inputs.json) 固定本次应用增量的 45 项来源及原文：Okio JVM 3.18.2 的 Maven 二进制、POM、源码 JAR、同版完整上游源码及原始法律文本；六个升级的 Python 分发各自的精确 wheel、sdist 与原始通知；pydantic-core 2.50.4 完整 `Cargo.lock` 新增的六个 registry crate 版本及其原始法律文本。新包为 `AI-Order-Assistant-0.1.4-application-delta-source-materials.tar.gz`，配套 `AI-Order-Assistant-0.1.4-application-delta-NOTICES.txt` 和 `.manifest.json`，获取入口见[应用分发](docs/distribution.md)。新源包与历史包共同使用，不覆盖旧 Tag 或附件。

六个实际升级的 Python 分发为 Click 8.5.1、FastAPI 0.162.2、LangGraph SDK 0.4.14、Pydantic 2.14.3、pydantic-core 2.50.4 和 websockets 17.4。所选官方 wheel 的每个文件均与实际候选安装文件逐摘要相同，其他 48 个分发的安装文件与 v0.1.3 相同。pydantic-core 与 websockets 的两个原生扩展已更新；pydantic-core 的实际 Rust 编译提交仍为 `58cf8f9c35b355199727627d07ae34a4e2846d67`，复用旧集合的完整标准库及编译器原始法律材料。其新 `Cargo.lock` 的 101 个 registry crate 中，95 个与旧集合的名称、版本、checksum 完全一致，六个新增版本另附完整源码和原文，没有 git source。集合保守包含构建、开发与其他平台依赖，不声称所有 crate 都链接在 Linux 扩展中。

新增通知按完整上游原文字节保留，包括源码归档中的独立 NOTICE；历史 Java、Python、Rust、OS、JRE、CPython 与 GNU runtime 材料继续保留。镜像内 `inventories/` 标识当前实际组件，`source-materials.json` 记录固定公共来源与摘要，历史超集不表示每个旧版本仍安装。根 MIT、SBOM、同版本号或地址可访问均不能代替这项匹配。
