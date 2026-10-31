# 历史验收入口

最近的本地部署与流程验证见 [2026-10-31 本地验收](verification/2026-10-31.md)，包含售后/反馈分页、AI 核对流程及构建限制。此前记录包括 [2026-10-30 过度防御修正](verification/2026-10-30.md)、[2026-10-28 架构与稳定性优化](verification/2026-10-28.md) 和 [2026-10-21 校招演示与稳定性验收](verification/2026-10-21.md)。

[2026-10-21 Agent HIGH 告警核查](verification/2026-10-21-high-review.md) 记录的是当时镜像的风险与处置，不能用来判断当前镜像是否仍有这些漏洞；当前安全结果须查看对应提交和实际部署镜像的扫描报告。

历史验收报告、JSON 输出和验收过程截图已从当前目录移除，避免与当前使用文档混在一起。完整材料保留在清理前的固定提交 [58e93bd8](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification)，其中的相对链接也在该提交下解析。

| 需要追溯的内容 | 固定提交中的入口 |
|---|---|
| 全部历史阶段与结果索引 | [原验收索引](https://github.com/W205614/AI-Order-Assistant/blob/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/README.md) |
| 角色功能、截断回复和稳定性修复 | [2026-10-19](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-19) |
| 校园扩展、真实模型和部署复验 | [2026-10-15](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification/2026-10-15) |
| 更早的单店、多商户、状态码与重构验收 | [全部归档目录](https://github.com/W205614/AI-Order-Assistant/tree/58e93bd8d438fbacb6feb09807fda3eda50302e4/docs/verification) |

这些记录只证明各报告所列提交、环境与场景下的结果。当前功能与启动方式见 [README](../README.md)、[校园业务说明](campus-commerce.md) 和 [运维指南](operations.md)；当前提交的回归与安全结果查看 [GitHub Actions](https://github.com/W205614/AI-Order-Assistant/actions)。不同轮次的检查点不能相加，也不能把旧报告当作当前版本已经通过的证据。

需要离线查看时，可在本地用 `git show 58e93bd8:docs/verification/README.md` 阅读原索引，再用完整路径读取对应报告，无需恢复整个历史目录。
