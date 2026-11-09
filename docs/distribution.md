# 应用分发

源码及应用下载入口为 [GitHub Releases](https://github.com/W205614/AI-Order-Assistant/releases)。应用包、Maven 包、源码及本机镜像分别核对提交和 SHA-256，名称或版本一致不能替代内容核验。

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

## 发布维护

1. 最终干净提交按现有流程构建、测试；`scripts/prepare-maven-package.py --output <新目录>` 从 Maven 的 `.jar.original` 生成应用 JAR、源码 JAR、POM 和摘要清单，并附带项目许可。
2. 运行制品及必要第三方分发材料独立核对；Maven 包不携带第三方二进制，不代表包含第三方依赖的可执行 JAR 自动满足分发要求。
3. 将已验收的候选资产及 `maven-manifest.json` 上传到同版本 Release 草稿。分支及标签的 CI、安全工作流均成功后，复核并正式发布 Release，再手动在所属仓库运行 `Publish verified Maven package`，输入该补丁标签。
4. 工作流使用限定为 `contents: read`、`actions: read`、`packages: write` 的仓库 `GITHUB_TOKEN`，核对标签、源码和候选摘要，使用 Maven deploy-file 上传同一候选，拒绝覆盖已有包版本。上传后实际下载 JAR、sources 和 POM，逐一比对 SHA-256，回执保存在 Actions artifact。工作流不会构建另一份产物，也不会部署应用。
5. 回读 Release 与包版本，保留摘要及使用验收记录，再按约定更新本机日常环境。发包失败时保留已公开的 Release，不覆盖或删除历史资产，并将总体状态标为部分完成。

旧版本保留，不重打 Tag 或覆盖历史附件。权限或材料不齐时如实记录受阻项，不将列表查询失败推断为没有包或无法推送。

本机日常升级也可向 `scripts/release.py` 传入已有候选的完整 `sha256:<64位镜像ID>`，它不会拉取或重新构建本地镜像；registry 镜像仍使用 `仓库@sha256:<摘要>`。两条路径都要求匹配的数据库备份及独立恢复证据，不接受可变标签，也不会跳过迁移和数据保留门禁。本机镜像与公开应用包的身份及实际来源另留验收记录，不将本地镜像当作已经公开发布的镜像包。
