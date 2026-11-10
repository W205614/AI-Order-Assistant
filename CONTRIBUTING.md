# 开发与协作

先按 [README](README.md) 运行本地演示，再阅读 [项目结构](docs/project-structure.md)、[业务规则](docs/campus-commerce.md) 和 [服务接入指南](docs/integrations.md)。真实服务与生产资源验收独立于日常开发，不用生产订单做测试。

## 环境与配置

完整运行优先使用Docker Compose。本机修改Java需要JDK 21和Maven；Agent使用Python 3.13及`requirements.txt`、`requirements.lock.txt`组合。原生页面没有独立前端构建依赖；Node和Playwright用于静态／浏览器检查，CI采用的版本及安装命令见 [.github/workflows/ci.yml](.github/workflows/ci.yml)。

私有配置放在已忽略的`.env`及`.local/`。提交脱敏模板，不提交凭据、Cookie、验证码、手机号地址、支付唤起参数、数据库导出或私有测试账号。新增配置同步更新模板和所属文档。

## 修改边界

- Java负责身份、价格、库存、资金和业务状态；Agent通过受限工具调用Java。
- 既有Flyway迁移保持不变，新增结构使用新的增量版本；修改支付状态时同时核对单店、跨店、售后和运营入口。
- 保持商户、金额、优惠快照及重复操作的幂等约束。渠道未知、受理、退款成功和终端送达分别表示各自事实。
- 跟随现有命名、依赖锁、异常与格式约定；第三方代码保留许可及来源，不手工修改生成或vendor分发文件。

## 必要检查

按改动及直接调用范围执行最小充分检查；CI中的完整命令为准。在项目根目录可运行：

```powershell
python scripts/check-format.py
node scripts/check-static-js.mjs
python scripts/check-compose.py
python scripts/check-cloud-compose.py
mvn -B -ntp test -f java-gateway/pom.xml
Push-Location agent-service
python -m unittest discover -s tests -v
Pop-Location
```

Java集成测试需要Docker和真实MySQL；Python的Redis条件测试需独立Redis。验证记录分别填写通过、失败、跳过和未执行。浏览器验证、负载与恢复会写入夹具，只对独立测试项目执行，具体参数见 [脚本索引](scripts/README.md)。真实模型和外部渠道验收可能收费，须显式选择目标资源。

手动管理MySQL容器的Spring测试，在类结束时先通过`TestContextManager`将本类上下文移出缓存并关闭，再停止数据库及对应HTTP夹具。直接`context.close()`会留下关闭的缓存条目并干扰框架回调；只停数据库会让后台任务继续访问旧库。已有测试中的`markApplicationContextDirty`遵循Spring的[上下文回收语义](https://docs.spring.io/spring-framework/reference/testing/annotations/integration-spring/annotation-dirtiescontext.html)。

## 提交材料

提交或PR说明应包含具体问题、最终行为、影响范围、迁移／配置变化、实际验证命令与结果、尚未完成的生产验收。改动准备好后再提交远端；提交说明附上本次提交对应的CI结果。

原创贡献按项目 [MIT License](LICENSE) 提交；确认自己有权贡献相关内容，第三方代码保留来源、版权和许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。敏感问题按[安全说明](SECURITY.md)处理。

## 标记版本

发布前核对 Java `pom.xml` 与 Agent `app/main.py` 的应用版本，执行相关本地检查，并等待适用的分支/标签 CI、安全及许可门禁完成。应用版本通过 Gateway、Agent 两张 Docker/OCI 镜像交付；手动工作流推广同一已验候选至完整源码 SHA 标签，不重建或覆盖公开版本。源码 Tag、包装提交和仓库摘要分别记录，获取与升级见[应用分发](docs/distribution.md)。Maven 保留源码构建、测试职责，历史 Maven 应用包在文档迁移远端生效后按原计划退役。普通推送不会自动部署生产或发布应用 Packages；真实模型评测须手动选择，生产部署另行授权。
