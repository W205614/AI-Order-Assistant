# 第三方代码与运行组件

本项目的 MIT 许可覆盖原创部分。第三方文件、依赖和容器基础组件保留原有版权与许可，不因根目录 LICENSE 而改变。下列信息于 2026-11-07 对照源码、锁文件和上游核查。

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
| Spring Boot / Spring Framework / Spring Security、微信支付 Java SDK、阿里云 SMS 与 OSS SDK | Apache-2.0；依赖 JAR 内保留相应 LICENSE / NOTICE；SDK 来源见[接入指南](docs/integrations.md#10-接入验收记录) |
| FastAPI、LangGraph、OpenAI Python SDK | MIT；安装包保留各自许可与版权；[FastAPI](https://github.com/fastapi/fastapi/blob/master/LICENSE)、[LangGraph](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)、[OpenAI SDK](https://github.com/openai/openai-python/blob/main/LICENSE) |
| MySQL Connector/J | [GPL-2.0 及 Universal FOSS Exception](https://github.com/mysql/mysql-connector-j/blob/release/9.x/LICENSE)；原许可证和例外随依赖保留，未将其重许可为 MIT |

本次 GitHub Release 分发源码及 GitHub 自动生成的源码归档，不附带可执行 JAR、wheel 集合或容器镜像。若后续另行分发二进制、镜像或商业集成，按实际打包的依赖核对许可证、NOTICE、源代码提供及例外适用条件，再准备分发材料。

## Docker 运行组件

| 组件 | 来源与许可边界 |
|---|---|
| Caddy、Prometheus、Alertmanager | Apache-2.0；[安全构建说明](deploy/security-runtimes.md)固定上游源码和 Web UI，运行镜像继承上游许可文件；传递 Go 模块保留各自许可 |
| gosu | [Apache-2.0](https://github.com/tianon/gosu/blob/29b939aa5bd4dc588af765b8248f6e39150d2565/LICENSE)，按固定源码重编译；Dockerfile 记录来源提交 |
| MySQL Server | 官方镜像及其 GPL-2.0 等组件许可适用，参见[上游 Docker 源码](https://github.com/docker-library/mysql)及镜像内许可 |
| Redis 8 | [上游三许可选择](https://redis.io/legal/licenses/)含 AGPLv3、RSALv2、SSPLv1；镜像不属于本项目 MIT 代码。部署或再分发时按具体使用方式选择并履行适用许可 |
| Eclipse Temurin、Python、Alpine / Ubuntu | 沿用运行基础镜像与系统包各自许可；固定镜像摘要见所属 Dockerfile |

本轮仅本地构建与验证，未向镜像仓库发布上述组件，也未修改上游署名或许可。
