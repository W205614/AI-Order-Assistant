# 历史验收入口

最近的发布回归、公开镜像获取及本机日常升级见[v0.1.4发布验证](verification/2026-11-12-release.md)。真实模型补测、候选身份与历史失败单列，不能把不同版本或阶段的结果合并为一次验收。各阶段记录如下：

| 日期 | 内容 |
|---|---|
| [2026-11-12 v0.1.4发布](verification/2026-11-12-release.md) | 诊断修复、依赖与Go安全修复、源码/包装身份、OCI公开获取及本机升级 |
| [2026-11-12 AI完整交易补测](verification/2026-11-12-ai-transaction-retest.md) | 发布前候选的26项闭环、4次模型调用及31,960保守预算单位 |
| [2026-11-12 v0.1.3原镜像补测](verification/2026-11-12-ai-transaction.md) | 原连接失败、持久预算及同期维护的历史边界 |
| [2026-11-10 v0.1.3发布](verification/2026-11-10-release.md) | 上一版发行、公开镜像及本机验收 |
| [2026-11-06 部署收尾](verification/2026-11-06-production-closeout.md) | 完整回归、V9日常升级、镜像验收与资源清理 |
| [2026-11-06 实现验收](verification/2026-11-06.md) | 外部服务适配、迁移、云配置及本地契约验证 |
| [2026-11-05](verification/2026-11-05.md) | 依赖维护、构建、售后／反馈及AI回归 |
| [2026-10-31](verification/2026-10-31.md) | 售后／反馈分页、AI核对及当时的构建情况 |
| [2026-10-30](verification/2026-10-30.md) | 重复防御逻辑修正 |
| [2026-10-28](verification/2026-10-28.md) | 架构与稳定性优化 |
| [2026-10-21](verification/2026-10-21.md) | 多角色演示、模型及恢复验收 |

[2026-10-21 Agent HIGH告警核查](verification/2026-10-21-high-review.md)保留当时的镜像发现与处置。当前安全结果见对应提交和实际部署镜像的扫描报告。

更早阶段的完整报告、JSON与截图归档于固定提交[58e93bd8](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification)，相对链接在该提交下解析。

| 需要追溯的内容 | 固定提交中的入口 |
|---|---|
| 全部历史阶段与结果索引 | [原验收索引](https://github.com/W205614/AI-Order-Assistant/blob/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/README.md) |
| 角色功能、截断回复和稳定性修复 | [2026-10-19](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-19) |
| 校园扩展、真实模型和部署复验 | [2026-10-15](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-15) |
| 更早的单店、多商户、状态码与重构验收 | [全部归档目录](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification) |

各报告按提交、环境与场景记录结果。当前功能与启动方式见[README](../README.md)、[校园业务说明](campus-commerce.md)和[运维指南](operations.md)；各提交的回归与安全结果见[GitHub Actions](https://github.com/W205614/AI-Order-Assistant/actions)。

需要离线查看时，可在本地用 `git show 58e93bd8:docs/verification/README.md` 阅读原索引，再用完整路径读取对应报告，无需恢复整个历史目录。
