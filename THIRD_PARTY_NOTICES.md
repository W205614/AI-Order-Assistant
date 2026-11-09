# 第三方代码与运行组件

本项目的 MIT 许可覆盖原创部分。第三方文件、依赖和容器基础组件保留原有版权与许可，不因根目录 LICENSE 而改变。精确版本与分发范围以本文引用的锁、实际制品及原始来源材料核对。

## 仓库直接包含的第三方内容

| 内容 | 来源与许可 | 保留方式 |
|---|---|---|
| `java-gateway/src/main/resources/static/assets/vendor/qrcodegen.js` | [Project Nayuki](https://github.com/nayuki/QR-Code-generator)，MIT，Copyright (c) Project Nayuki | 原文件含完整版权和许可；[vendor 索引](java-gateway/src/main/resources/static/assets/vendor/README.md)记录下载来源与摘要，静态检查核验原始字节 |
| `deploy/caddy/patches/go-jose-v3-CVE-2026-116587.patch` | [go-jose](https://github.com/go-jose/go-jose/tree/v3.0.5)，Apache-2.0；从[上游修复](https://github.com/go-jose/go-jose/commit/a0111f82742fa92bceec9ede40845e73319e6230)适配 | [补丁说明](deploy/caddy/patches/README.md)标明来源与修改；完整许可见 [Apache-2.0](licenses/Apache-2.0.txt)，版权见下文 |

go-jose 派生补丁保留上游声明：Copyright 2014 Square Inc.。该补丁为 v3 API 适配，修改范围见补丁说明；本项目测试与构建胶水不改变上游代码的许可。

`docs/screenshots/` 为项目已有演示场景截图；本轮未新增或替换截图，未将其当作本轮浏览器测试的取证图。页面其余图形为仓库内 SVG、CSS 及文字；本轮未引入外部图片或字体。

## 应用依赖与构建产物

Java 的精确依赖以 [pom.xml](java-gateway/pom.xml)、Maven 解析结果及打包生成的 CycloneDX SBOM 为准；运行镜像内为 `/app/runtime-bom.json`。Python 的完整固定版本见 [requirements.lock.txt](agent-service/requirements.lock.txt)。不得把本表当成传递依赖的完整清单。

| 主要组件 | 上游许可入口 |
|---|---|
| Spring Boot / Spring Framework / Spring Security、微信支付 Java SDK、阿里云 SMS 与 OSS SDK | Apache-2.0；保留依赖内已有声明，并在分发材料中补齐未内嵌的许可及通知；SDK 来源见[接入指南](docs/integrations.md#10-接入验收记录) |
| FastAPI、LangGraph | MIT；安装包保留各自许可与版权；[FastAPI](https://github.com/fastapi/fastapi/blob/master/LICENSE)、[LangGraph](https://github.com/langchain-ai/langgraph/blob/main/LICENSE) |
| OpenAI Python SDK 3.42.0 | Apache-2.0；按锁定版本的[完整许可](https://github.com/openai/openai-python/blob/v3.42.0/LICENSE)核对，不能沿用旧版 MIT 的描述 |
| MySQL Connector/J | [GPL-2.0 及 Universal FOSS Exception](https://github.com/mysql/mysql-connector-j/blob/release/9.x/LICENSE)；原许可证和例外随依赖保留，未将其重许可为 MIT |

`v0.1.0`、`v0.1.1` 仅分发源码。`v0.1.2` 的应用分发入口包括源码归档、Release Java 应用包、Maven 薄 JAR/POM/自身源码 JAR，以及 Gateway、Agent 的 GHCR 目标；实际公开版本与获取条件以 [Release](https://github.com/W205614/AI-Order-Assistant/releases/tag/v0.1.2) 和 [Packages](https://github.com/W205614/AI-Order-Assistant/packages) 为准。Agent 的源码安装使用两份 requirements 文件；容器分发另核对实际安装的 Python 包、原生扩展和运行时，不把它等同于单独发布 wheel 集合。Spring Boot 可执行 JAR 包含运行依赖，Maven 薄 JAR 不内嵌这些依赖，两者不能混用许可清单。

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

[container-distribution.lock.json](licenses/container-distribution.lock.json)固定 `go-base`、`ui`、`os`、`go-additional` 四份互补的 Release 源码材料；固定 URL、获取方法及发布状态入口见[应用分发](docs/distribution.md#容器的许可与对应源码)。材料覆盖 411+8 个 Go 模块版本项、三版 Go 标准库、两个实际 Web UI、BusyBox/uClibc 源码与构建配方，以及 Caddy 各层的 25 组系统源版本。`go-jose` 的历史适配与补充署名版本分别保留来源、差异和重建说明，不改写旧归档中的源身份。

Gateway/Agent 候选还核对 Java 依赖、Python 包及原生扩展、OS、JRE、CPython 和内嵌字体。应用候选采用最终文件系统与运行配置等价的单层制品，其实际来源摘要按候选归档和 registry 配置字节核验；不能要求原本机 image ID 在不同序列化方式下始终相同。

后续基础设施归档必须同时通过安全扫描与实际分发材料门禁，随附完整 `NOTICES.txt`、固定源码入口及逐项验证结果。根 MIT、上游主程序的 Apache-2.0、资产 URL 可访问或扫描成功，均不能代替各组件的许可与对应源码核对。源码和原始通知是持续保留的发行材料；历史 Tag 与原附件不覆盖，新增许可材料只追加到对应 Release。
