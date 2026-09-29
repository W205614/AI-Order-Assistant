# 企业级加固验收记录（2026-09-29）

范围：Windows / Docker Desktop 的本地 Compose 演示环境，MySQL 8.4、Java 21、Python Agent。结论限于下述场景，不代表公网生产部署或容量验收。

## 实际结果

| 验证项 | 命令或方式 | 结果 |
|---|---|---|
| Python Agent | `python -m unittest discover -s tests -q`；`python -m compileall -q app tests evals` | 48 项通过，编译通过 |
| Java 与真实 MySQL | `mvn test -q -Duser.timezone=UTC` | 33 项通过，0 失败、0 错误、0 跳过；其中订单 MySQL 集成 12 项、Flyway 迁移 3 项 |
| 数据库并发 | 16 个线程提交 32 次建草稿；多用户竞争最后一份库存；偏好更新等待用户锁 | 每人仅 1 份活动草稿；库存不会超卖；新偏好在确认时生效 |
| HTTP 功能与并发 | `python scripts/test-functional-concurrency.py --workers 16` | 14 项断言通过；16 次并发创建只保留 1 份草稿，16 次并发确认全部返回同一订单，实际订单总数 1；测试订单已取消 |
| 前端脚本 | `node scripts/check-static-js.mjs` | 用户端与管理端脚本通过；当前消息不进入先前历史；跨午夜时间格式检查通过 |
| Compose | 两套 Compose 配置校验；`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-compose.ps1 -TimeoutSeconds 240 -LeaveRunning` | 构建、四服务健康、登录、草稿确认、重复确认幂等、取消清理通过 |
| 真实模型评测 | Agent 容器执行 `python evals/run_live_eval.py --runs 3 --results-file /tmp/release-live-results.jsonl` | `deepseek-v4-flash`，28 场景 × 3 次 = 84 次；安全 18/18，其他 66/66，全部通过 |
| FAQ 固定集 | `python evals/run_faq_eval.py --iterations 100` | 39 个标注问题；当前词法 + bigram Top-1 92.31%、Precision@1 96.77%、Recall@1 90.91%、未知问题拒答 100% |

FAQ 结果是独立的固定集质量测量，不适用上述端到端任务的 95% 门槛；检索耗时是进程内微基准，不代表 LLM 或页面响应速度。HTTP 并发回归证明本次测试下的一致性，不是最大并发容量测量。

可审查的脱敏证据：

- [Java 分套件计数](docs/verification/2026-09-29/java-tests.json)
- [真实 HTTP 功能与并发断言](docs/verification/2026-09-29/functional-concurrency.json)
- [真实模型逐场景结果](docs/verification/2026-09-29/live-eval.jsonl)
- [真实模型汇总](docs/verification/2026-09-29/live-eval.summary.json)

JSON/JSONL 报告不包含提问原文、回复原文、JWT、密码或订单内容。完整 Maven/Compose 运行日志留在本地被忽略的 `.log` 文件中。测试脚本创建独立测试账号；其已取消订单保留在本地库，不修改既有用户订单。

## 功能覆盖与修复

- `POST /order/place` 返回 405；订单只能经草稿显式确认。确认重试不重复建单、不重复扣库存。
- 明确和含糊的过敏表述进入待澄清状态；页面明确选择后跨轮、刷新保留。本次约束不会写入长期偏好，确认、放弃或到期后失效。
- 存在过敏约束时，未核验菜品不可选；确认重新检查长期偏好、临时约束、草稿快照和菜品核验状态。迁移保留最新有效草稿后建立唯一约束，旧空标签不自动获准。
- Java 原先通过 `Timestamp` 读写本地时间，在 UTC 容器、北京时间 JDBC 配置之间发生隐式转换。现改用明确的餐厅时钟及 JDBC `LocalDateTime` 读写，并将连接的 `NOW()` 固定为 `+08:00`，保留历史 `DATETIME` 的北京时间含义。测试检查 30 分钟约束、5 分钟草稿、订单时间及午夜日期边界。
- 长期偏好保存新增事务及用户行锁，避免与确认校验交错；锁顺序与草稿和临时约束一致。
- Agent 达到迭代上限进入未完成终态，不继续悬置工具；未恢复的工具失败不会记为运行完成。工具失败、运行结果和离线任务成功率分别记录。
- Testcontainers 由 1.20.1 升为 1.21.4，并统一依赖版本，真实 Docker/MySQL 用例已实际运行。

## 用户视角页面评价

桌面约 1280×720 实际操作覆盖登录、菜单选择、生成草稿、刷新恢复、确认下单、订单列表、临时过敏原设置与刷新恢复。页面的主要点餐路径清楚，金额与确认按钮可见，确认后按钮停用且订单列表反映真实状态。页面内取消框的“保留订单”和“确认取消”均通过浏览器实际操作验证；本轮浏览器创建的订单 #17、#18 已取消。见[演示订单取消确认截图](docs/verification/2026-09-29/cancel-confirmation.png)。

本轮发现并修复：

1. 右侧占位提示占满首屏，过敏设置被推到下方：缩短提示区，安全设置直接可见。
2. 订单时间偏移，刷新后旧聊天时间变成当前时间：统一餐厅时区，保存消息时间；旧记录没有时间时明确显示“历史消息”。
3. 过敏过滤后只有“无匹配菜品”，无法理解原因：改为说明筛选/饮食约束与人工核验要求。
4. 内置浏览器的原生取消弹窗交互超时：改成页面内确认框，展示订单号，并提供“保留订单”和“确认取消”两个清楚的选择。

评价：已具备较清楚的桌面演示体验；过敏用户的点餐可用性仍取决于食堂核验菜品标注。移动端实机、读屏器和真实用户可用性访谈未纳入本轮结论。

## 失败记录与修复后的复验

首次模型评测为 83/84：`unsafe_large_quantity` 没有调用建草稿工具，也没有产生草稿或订单，旧评测器却要求必须出现失败的工具调用。已修正为允许直接拒绝危险请求，同时继续禁止成功创建草稿。原始失败报告保存在本地 `agent-service/evals/results/enterprise-hardening-20260929.jsonl`，未改写为通过；后续完整重跑为 84/84，本文件链接的逐场景报告是最新重跑结果。

时区专项测试曾暴露约束有效期偏差，修复后使用 UTC 测试 JVM 重跑全部 Java/MySQL 用例通过。MySQL 秒精度存储允许过期时间四舍五入的一秒误差。首次 Compose 构建还曾因 Maven Central 下载中断失败；移除额外的 `dependency:go-offline`、使用 BuildKit Maven 缓存后，标准构建及冒烟通过。
