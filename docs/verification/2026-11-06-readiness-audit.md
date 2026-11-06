# 2026-11-06 上线、文档、远端与容器核对

本页保留核对当时的快照；后续整改、日常升级与清理结果见[部署收尾](2026-11-06-production-closeout.md)。

核对时间：2026-11-06（UTC+8）。本轮检查实际Git状态、已停止的日常容器、镜像与JAR，并在线查阅成熟项目的官方README。只修改文档，没有提交／推送、启动日常服务、变更日常数据库或移除备份停用标记。状态属于当时快照，后续发布应重新核验。

## 四项结论

| 核对项 | 结论 | 依据 |
|---|---|---|
| 当时的交付阶段 | 业务与适配已实现、本地验证完成 | 待完成发布同步、实际服务接入、告警配置和新增域恢复验收；后续结果见部署收尾报告 |
| README及说明文档 | 已参考官方项目并补充 | 补充核心能力、架构图、上线准入、开发协作和安全说明；修正文档中的旧支付／地图、平台券成本及退款口径 |
| 本地与远端一致 | 提交一致，工作区不一致 | `git fetch --prune origin`后，`HEAD`与`origin/master`均为`b1ed1a4ec4ee7e124a37956060f45c578dd3f397`，左右分歧`0/0`；上一轮实现及本轮文档尚在工作区 |
| 日常容器是否最新 | 不是 | 日常网关与Agent均已停止、仍为11月5日旧镜像；新代码使用11月6日独立验收镜像验证，测试容器已清理 |

## 发布与生产准入

上一轮Java 229项及浏览器／业务287项等结果见 [实现验收报告](2026-11-06.md)。这些是本地结果，本轮没有重复运行与文档无关的业务测试。

在线重新核验：远端基线提交的 [CI 42040331563](https://github.com/W205614/AI-Order-Assistant/actions/runs/42040331563) 与 [Security 42040331435](https://github.com/W205614/AI-Order-Assistant/actions/runs/42040331435) 均成功；它们只验证`b1ed1a4`，没有包含本轮未提交的支付等代码。该阶段的工作区变更尚待提交并执行CI。

生产微信、短信、OSS、高德、云数据TLS、外部告警、异地恢复和模型效果仍按 [接入状态表](../integrations.md#10-接入验收记录) 为`NOT_RUN`。正式启用前遵循 [上线准入](../production-readiness.md)，运营负责人和实际私密漏洞收件渠道需登记核验；该次核对未检查GitHub私密漏洞报告设置，后续启用记录见[安全说明](../../SECURITY.md)。

本轮额外发现恢复验收边界：`restore-db.ps1`导入完整SQL，但业务指纹只检查原单店及V6校园表，未覆盖V7–V9新增渠道支付／退款、验证手机、媒体和通知数据。该阶段尚待补充新增域指纹，以及本地媒体卷、OSS对象的独立备份与回读。上述项目后续已在[部署收尾](2026-11-06-production-closeout.md)完成本地验证。

## README研究与本轮调整

查阅的均为项目官方仓库，2026-11-06核对：

| 官方资料 | 实际组织方式 | 在本项目中的应用 |
|---|---|---|
| [Alibaba Nacos README](https://github.com/alibaba/nacos/blob/develop/README.md)及[贡献指南](https://github.com/alibaba/nacos/blob/develop/CONTRIBUTING.md) | 产品定位、核心功能、快速开始、完整文档与贡献入口 | 保持README面向使用者，协作流程独立到`CONTRIBUTING.md` |
| [Apollo README](https://github.com/apolloconfig/apollo/blob/master/README.md)及[快速开始](https://github.com/apolloconfig/apollo/blob/master/docs/en/deployment/quick-start.md) | 截图、功能、开发、部署、发布记录及FAQ分层；快速体验与生产部署有明确区分 | 补充上线准入，本地体验、代码回归与生产发布分层说明 |
| [Spring AI Alibaba README](https://github.com/alibaba/spring-ai-alibaba/blob/main/README.md) | 架构图、能力、环境前提、快速运行、模块与文档导航 | 增加本项目真实架构和能力表，明确AI、Java业务与外部渠道的职责 |

新增 [CONTRIBUTING.md](../../CONTRIBUTING.md)、[SECURITY.md](../../SECURITY.md)、[上线准入](../production-readiness.md)，完善 [README](../../README.md)。同步修正 [校园业务](../campus-commerce.md)、[Agent说明](../../agent-service/README.md) 和 [源码阅读路径](../project-structure.md)，避免新接入指南与旧子文档互相矛盾。

已有接入与运维指南继续承担配置、回调、发布、备份和故障处理，不再复制一套相同部署步骤。项目级授权条款由权利人确定，第三方原许可保留。

## 镜像与日常更新

| 用途 | 网关镜像ID | Agent镜像ID | 状态 |
|---|---|---|---|
| 日常`ai-order-assistant` | `sha256:d8a0a7a0d9d40af4ed8d82dd6615c9b3d604c85ceb5d8f0890a720887d17bd2e` | `sha256:af5f10569c0aac755f390e7e13428fe81298a3abc2a67faea33bab7b490e950a` | 容器均停止，代码为旧版 |
| 最新独立验收 | `sha256:5a88d786283b47e282770741e764388f1256a7ff54520b83898723f6e4a445b3` | `sha256:88ce8cf737182ba083ca62e14ae85114574884d2435507f2a54fd4362cc36aa1` | 镜像保留，独立测试容器／网络已清理 |

从停止的日常网关实际复制JAR检查：不包含`ProviderPaymentService.class`及`V7__ProviderPayments.sql`；最新验收JAR包含两者。该证据直接说明日常容器不包含本轮支付域，而不是只凭镜像标签或创建时间判断。

对照上一轮源码摘要清单，Java／Python生产源码未在本轮文档核对中改变；最新验收镜像与上一轮代码验证仍对应。本轮纯文档变化无需重建应用，但日常部署要使用新功能，必须更新网关、Agent与一次性迁移镜像并重建相应容器。已有验收镜像可固定摘要复用；若需要最终提交身份标签，应在提交后按该提交重新构建并记录摘要。

MySQL／Redis不因业务源码变化而必须重建其基础镜像，但数据库结构需要V7–V9迁移。日常数据库在该次核对中保持停止，Flyway版本未查询。`start.ps1 -Build`会启动依赖、处理备份／账号并执行迁移，不是无副作用的镜像检查。

推荐收尾顺序：提交推送并取得本次CI结果 → 确认适用恢复点 → 固定镜像、迁移及日常部署验收 → 按启用范围完成生产实联与运营接管。按上述顺序逐项留存发布与验收结果。

原始本机证据位于`.local/readiness-audit-20261106/`；上一轮原始证据保留在`.local/trial-20261106/`。本轮文档检查记录于新的核对回执，不改写上一轮已冻结的源码摘要和测试回执。
