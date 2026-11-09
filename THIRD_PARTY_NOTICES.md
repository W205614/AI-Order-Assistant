# 第三方代码与运行组件

本项目的 MIT 许可覆盖原创部分。第三方文件、依赖和容器基础组件保留原有版权与许可，不因根目录 LICENSE 而改变。下列信息于 2026-11-09 对照源码、锁文件、实际应用 JAR 和上游核查。

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

`v0.1.0`、`v0.1.1` 仅分发源码。`v0.1.2` 的应用分发范围为源码归档、Release Java 应用包，以及 Maven Packages 的薄 JAR、POM 和自身源码 JAR；实际公开状态与获取地址以对应 Release 为准。Agent 通过源码及锁文件安装依赖，不分发 wheel 集合。Release 应用包内的 Spring Boot 可执行 JAR 包含运行依赖，Maven 薄 JAR 不内嵌这些依赖，两者不能混用许可清单。

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
| Caddy、Prometheus、Alertmanager | Apache-2.0；[安全构建说明](deploy/security-runtimes.md)固定上游源码和 Web UI，运行镜像继承上游许可文件；传递 Go 模块保留各自许可 |
| gosu | [Apache-2.0](https://github.com/tianon/gosu/blob/29b939aa5bd4dc588af765b8248f6e39150d2565/LICENSE)，按固定源码重编译；Dockerfile 记录来源提交 |
| MySQL Server | 官方镜像及其 GPL-2.0 等组件许可适用，参见[上游 Docker 源码](https://github.com/docker-library/mysql)及镜像内许可 |
| Redis 8 | [上游三许可选择](https://redis.io/legal/licenses/)含 AGPLv3、RSALv2、SSPLv1；镜像不属于本项目 MIT 代码。部署或再分发时按具体使用方式选择并履行适用许可 |
| Eclipse Temurin、Python、Alpine / Ubuntu | 沿用运行基础镜像与系统包各自许可；固定镜像摘要见所属 Dockerfile |

应用镜像用于本机构建与验收，`v0.1.2` 的上述应用包不包含 OS、JRE 或基础设施镜像。若另行分发容器镜像，须按实际基础层、升级后系统包及运行时补齐对应许可与源码材料，不能直接复用 Java 应用包清单。上游署名与许可继续保留。
