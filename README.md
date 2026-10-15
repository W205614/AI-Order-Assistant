# 🍽️ AI 点餐助手：Java 业务后端 + Python Agent

[![CI](https://github.com/W205614/AI-Order-Assistant/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/W205614/AI-Order-Assistant/actions/workflows/ci.yml)
[![Security](https://github.com/W205614/AI-Order-Assistant/actions/workflows/security.yml/badge.svg?branch=master)](https://github.com/W205614/AI-Order-Assistant/actions/workflows/security.yml)

一个可本地部署的多商户 AI 点餐演示系统。平台开通商户，顾客选店后通过菜单或对话准备订单，商家处理制作与配送，并查看库存、统计和操作记录。每个商户对应一家店，支付与退款均为模拟记录。

新增 [校园多店点餐](http://localhost:9090/campus/)：跨校区服务、持久跨店购物车、统一结算、店铺券与平台券、按数量退款、预约、自提及平台骑手。四种角色的操作、金额和履约规则见 [校园业务说明](docs/campus-commerce.md)。旧单店入口保留，新订单通过校园结算与履约接口处理。

2026-10-15 界面与真实模型复验：新页面 207 个检查点通过，真实连续对话完成跨店推荐、加购及含双券的 21.50 元报价；具体失败、修复和验证边界见 [界面与真实模型复验](docs/verification/2026-10-15/界面与真实模型复验.md)。此前交易、权限与清理审查见 [审查复验记录](docs/verification/2026-10-15/审查复验.md)。

最后一轮重新验收修复了切校区/店铺时的异步菜单问题：修复后 UI 212、校园 18、单店 23 个检查点通过；HTTP、退款、预约、骑手、模型故障回退及 3 轮真实模型测试也通过。完整范围和证据见 [最终功能与体验验收](docs/verification/2026-10-15/最终功能与体验验收.md)。

Java 负责身份、权限、报价、库存和订单事务；Python 负责自然语言理解和工具编排。用户核对后点击确认才创建订单。未配置模型、模型断网或 Agent 停止时，菜单点餐、确认、模拟支付和订单操作仍可使用。

## ✨ 功能概览

- **校园点餐**：按目的校区选店、持久跨店购物车、SKU 与固定套餐、双券报价、统一模拟支付、数量退款、预约配送和自提；历史结算可分页查看。
- **单店兼容**：原菜单、草稿、模拟支付、取消退款和催单入口保留；校园订单从聚合结算管理，商家在校园队列处理。
- **AI 辅助**：校园协议提供跨店搜索、预算组合、券查询、购物车准备与结算查询，价格由 Java 核验；旧单店对话保留 Router、静态 FAQ 和 LangGraph 路径。
- **安全约束**：菜单按长期过敏原和当前店铺临时约束过滤；草稿更新/确认还合并草稿快照。有过敏约束时，未核验或存在冲突的菜品禁止下单；约束尚未明确时阻止下单。
- **商户后台**：菜单与规格库存、固定套餐、所属校区与配送规则、店铺券、预约容量、备餐明细、自提核验、售后和经营统计；老板与店员权限分开。
- **平台与骑手**：平台管理校区、商户、券、骑手授权、派单改派、争议售后和异常任务；骑手按授权校区抢单、取餐并核码送达。
- **交易保护**：草稿版本、报价复核、幂等确认、并发库存扣减、支付超时关闭及库存回补。
- **实时状态**：持久订单事件、SSE 通知、断线重连补读，顾客与商家页面同步订单进度。
- **部署与运维**：Docker Compose、Flyway 迁移、数据库备份与恢复演练、Prometheus 指标和 CI 验证。

## 📸 页面预览

以下截图来自 **2026-10-15 隔离浏览器验收**，展示测试账号与测试数据。页面采用统一的导航、表单、账单和状态提示，覆盖手机、平板与桌面。设计参考 [Taste Skill](https://github.com/Leonxlnx/taste-skill) 的 Redesign 与 Minimalist UI 模块，仍使用原生 HTML、CSS 和 JavaScript，无需在运行时安装 Skill。

**校园顾客端：跨店菜单、购物车与实际报价**

![校园点餐页面](docs/screenshots/campus-customer.png)

<details>
<summary>真实模型准备后的双店报价：商品、费用与优惠逐项核对</summary>

![双店实际报价](docs/screenshots/campus-quote.png)

</details>

<details>
<summary>商户后台：订单、菜单库存、店铺设置与员工管理</summary>

![商户管理页面](docs/screenshots/campus-merchant.png)

</details>

<details>
<summary>平台后台：商户开通、账号状态与审计</summary>

![平台管理页面](docs/screenshots/campus-platform.png)

</details>

## 🏗️ 技术栈

| 部分 | 技术 | 职责 |
|---|---|---|
| 业务后端 | Java 21、Spring Boot 4.0.8、Spring Security、JDBC | 认证授权、菜单、草稿、交易、库存和审计 |
| Agent | Python 3.13、FastAPI、LangGraph、httpx、Pydantic | 对话编排、工具调用、截止时间与模型预算 |
| 前端 | HTML、CSS、原生 JavaScript 模块 | 顾客、商户、平台和骑手页面，移动端布局、SSE |
| 数据与缓存 | MySQL 8.4、Flyway、Redis 7.4 | 持久业务数据、迁移、聊天限流、额度和菜单元数据缓存 |
| 部署与监控 | Docker Compose、Prometheus；可选 Caddy | 六个默认服务、健康检查、指标、备份；公网 HTTPS 入口 |
| 验证 | JUnit、Testcontainers、Python unittest、Playwright、k6 | 数据库事务、接口与页面回归、隔离环境压测 |

Python 依赖由 [requirements.txt](agent-service/requirements.txt) 和 [requirements.lock.txt](agent-service/requirements.lock.txt) 固定；Java 依赖见 [pom.xml](java-gateway/pom.xml)。

## 🏛️ 架构与职责

```text
浏览器：校园顾客 / 原单店顾客 / 商户 / 平台 / 骑手页面
   │ HttpOnly Cookie、CSRF、选店与业务请求
   ▼
Java Gateway
   ├─ AuthService / MerchantService             身份、角色与商户权限
   ├─ MenuService / DraftService                菜单、草稿、报价与版本
   ├─ OrderSafetyService                        统一合并偏好与过敏约束
   ├─ OrderTransactionService / InventoryService  交易、库存与补偿
   ├─ OrderQueryService / AuditService           查询与审计
   ├─ OrderStatusEventBroker                    持久事件与 SSE
   ├─ CampusCatalogService / CommerceCartService 校区、地址、SKU、套餐与跨店购物车
   ├─ CommerceQuoteService / CommerceCheckoutService 报价、结算、子订单与库存预留
   ├─ CommerceCouponService / CommerceAftersaleService 双券分摊、逐份快照与数量退款
   ├─ CommerceFulfillmentService                预约容量、自提与骑手任务
   ├─ CommerceEventService / CommerceExperienceService 顾客/骑手事件、反馈与经营台账
   ├─ MySQL + Flyway                            业务事实与迁移
   └─ /chat：先处理过敏澄清/受约束推荐，其余请求固定用户、商户和截止时间
          ▼
      Python Agent
          ├─ 确定性购物车 Router / 静态 FAQ
          ├─ LangGraph / 模型工具白名单
          └─ 回调 Java：菜单、偏好、草稿、订单查询、催单及待确认取消

   /campus-assistant/chat → 独立校园工具循环 → Java 校区搜索、真实报价与本人购物车

Redis：聊天限流、商户模型额度、菜单元数据缓存、校园助手近期对话
运维：每日数据库备份 + Prometheus 指标与基础告警
```

控制器直接调用业务服务，事务由 Java 管理。校园工具固定已认证顾客、目的校区、地址与履约方式，不包含确认、支付、取消、退款审核和骑手状态变更；跨店搜索与报价逐店建立上下文。原单店工具固定商户上下文，取消已确认订单只生成待确认操作。菜单与草稿/交易复用过敏原合并及校验，校园购物车也保留约束快照。

当前部署是单 Java 应用、单 Agent，多商户共享数据库并通过商户归属校验隔离。Agent 的进程内并发准入限制需要在增加副本前重新设计。

## 📁 项目目录

```text
AI-Order-Assistant/
├─ java-gateway/
│  ├─ src/main/java/com/ai/assistant/   控制器、业务服务、认证和 Agent 客户端
│  ├─ src/main/java/db/migration/      Java 数据迁移
│  ├─ src/main/resources/
│  │  ├─ db/migration/                 SQL 数据迁移
│  │  └─ static/                       campus / chat / admin / platform / rider / assets
│  └─ src/test/                        Java 与真实 MySQL 集成测试
├─ agent-service/
│  ├─ app/                             Agent、Java 回调、FAQ、预算和配置
│  ├─ tests/                           Python 规则与失败路径测试
│  └─ evals/                           检索与对话评测数据及脚本
├─ scripts/                            启动辅助、备份恢复、接口与浏览器验收
├─ deploy/                             备份、Prometheus、告警和 Caddy 配置
├─ load/                               k6 隔离压测场景
├─ docs/                               页面截图、运维说明和版本验收记录
├─ docker-compose*.yml                 基础、运维、隔离测试和公网配置
└─ start.ps1                           Windows 启动入口
```

## 🚀 快速启动

需要 Docker Desktop（Linux 容器）及支持 `!reset` / `!override` 的 Docker Compose v2。在 Windows 项目根目录运行：

```powershell
.\start.ps1 -Docker -Build
```

脚本生成本地 `.env`，保留已有密钥，先备份已有数据库，再配置业务数据库账号，启动网关、Agent、MySQL、Redis、备份和 Prometheus 六个服务并等待应用健康检查。

| 入口 | 地址 | 本地演示账号 |
|---|---|---|
| 校园顾客 | [跨店点餐](http://localhost:9090/campus/) | 注册账号；原演示顾客也可登录 |
| 原单店顾客 | [单店兼容入口](http://localhost:9090/chat/) | `demo / 123456` |
| 商户 | [商户后台](http://localhost:9090/admin/) | `admin / admin123`（原演示店老板） |
| 平台 | [平台后台](http://localhost:9090/platform/) | 查看本地 `.env` 的 `PLATFORM_ADMIN_USERNAME` / `PLATFORM_ADMIN_PASSWORD` |
| 骑手 | [骑手任务](http://localhost:9090/rider/) | 由平台创建账号并授权校区 |
| 监控 | [Prometheus](http://localhost:19091/) | 仅绑定本机 |

演示种子默认开启，以上固定弱密码仅供本机演示。平台管理员密码随机生成；不要提交或分享 `.env`。

**模型配置可选**：不配置密钥也能菜单点餐；Agent 运行时，匹配 Router 的明确指令及符合首次消息、白名单等条件的静态 FAQ 不需要模型。需要一般自然语言推荐时，在 `.env` 设置 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`，重新运行 `.\start.ps1 -Docker` 应用配置。

Linux 启动、已有数据库升级、公网 HTTPS 和完整配置说明见 [部署与运维指南](docs/operations.md)。

## 📝 使用流程

1. **配置校区与店铺**：平台创建校区和商户；老板设置所属校区、营业时间、目的校区配送规则、商品库存，按需配置券和预约时段。新校区配送须明确启用。
2. **准备购物车**：顾客登录校园入口，选择目的校区、地址和配送或自提；跨店加购，也可领取店铺券和平台券。有过敏要求时先确认店铺约束。
3. **核对报价**：勾选店铺、优惠和预约。不可提交的店铺保留购物车，顾客明确选择可提交部分并重新报价；核对后创建整份结算。
4. **支付与履约**：十分钟内统一模拟支付；商家按商品明细接单、制作和备餐。配送由骑手抢单或平台指派，送达核验顾客码；自提由商家核验。
5. **订单与售后**：结算列表可加载历史记录。制作前取消或按数量退款会回补相应库存；制作后申请数量退款，商家审核，争议可交平台处理，食品库存不自动回补。

原单店入口继续使用“去结算—保存草稿—确认—模拟支付”流程；校园订单在校园结算页面处理，商家后台的原单店队列与校园队列分别展示。

## 🔧 核心实现

### 校园结算与履约

一次结算使用同一目的校区、地址和履约方式，包含多家店的独立子订单。报价五分钟有效，不锁资源；确认时统一复核并预留 SKU、券和预约容量，全部成功才创建交易。未支付取消任一子单会关闭整份结算；支付后各店独立履约和售后。

店铺券先抵扣商品金额，再按剩余商品金额分摊平台券；包装费和配送费单独核算，分角尾差按稳定顺序处理。退款使用逐份价格及优惠快照，不重算优惠门槛，累计数量和金额受原购买记录约束。更多规则见 [校园业务说明](docs/campus-commerce.md)。

### 原单店草稿、版本与幂等

每个用户在每家店最多保留一份活动草稿，创建和更新后五分钟有效，重新报价也刷新有效期。更新和确认提交 `expectedVersion`。确认时先复核可售状态、库存和过敏约束；这些检查通过后，若价格或菜品版本变化，返回 `409` 及重新报价的草稿，需再次确认。确认幂等键绑定用户、商户、草稿版本与收货信息，避免重复创建订单，也拒绝将同一键用于不同请求。

### 交易、库存与补偿

确认订单时在数据库事务内预留库存，支付超时和制作前取消会关闭订单并且只回补一次；已模拟支付的取消同时写入模拟退款记录。十分钟支付期限到达后拒绝支付，后台每十五秒扫描到期订单，关闭与回补可能晚于支付截止时间。独立库存接口使用版本、幂等键和流水，菜单编辑不会覆盖并发库存扣减。

```text
待模拟支付 → 商家待处理 → 制作中 → 配送中 → 完成
待模拟支付 → 十分钟未支付 → 支付超时关闭、回补库存
待模拟支付 / 商家待处理 → 顾客或商家取消、回补库存（已支付则模拟退款）
```

原单店状态值为 `0` 待模拟支付、`1` 商家待处理、`2` 制作中、`3` 配送中、`4` 完成、`5` 取消、`6` 支付超时；旧统计仅计算原单店已完成且已模拟支付的订单。校园订单分别记录支付、履约和售后状态，校园报表区分实付、退款、净收和店铺/平台优惠承担。

### AI 工具与安全约束

校园助手使用独立的七个受限工具，预算组合最多处理三类关键词，每类取有限候选，再由 Java 校验费用与可服务范围；只报告已核验的可行组合，不承诺全局最优。校园模型配额目前共用平台上下文，未做独立顾客计费。

校园对话按顾客、目的校区、地址及履约方式隔离，保留最近三轮纯文本对话，十五分钟过期。上下文仅用于理解“刚才的组合”等指代，商品、券和购物车版本仍重新查询。只有本轮明确授权才准备购物车；页面仅采用通过当前条件及购物车版本核验的真实结构化报价，创建订单和模拟支付仍由顾客点击确认。登录后的工作区加载失败可直接重试，无需重复登录。

原单店 Router 处理明确的点菜、增删菜品、单菜数量修改和备注等模式；菜名从当前可售菜单的首批最多 50 项中匹配，数量解析仅支持规定格式，不支持的表达转入模型路径。模型通过显式工具协议查询菜单、修改草稿或准备待确认操作。

长期偏好归用户，临时过敏约束归用户与商户。草稿创建保存临时过敏原快照，更新/确认时合并快照、当前临时约束和长期过敏原。花生、鸡蛋和麸质参与硬校验；不喜欢的食材、饮食目标及预算供推荐参考，不作为下单硬限制。后厨交叉接触仍需实际人工确认。

FAQ 使用关键词与标题二元字匹配，无向量数据库。只有无历史、无菜单勾选项的消息，高置信度命中静态白名单且未被指令/订单操作规则排除时，才直接返回答案；其余走模型路径。菜单、FAQ 和对话历史按不可信文本处理，可信身份与商户由网关及工具上下文控制；这不代表模型回答完全免疫提示注入。请求截止时间、提示大小、调用预算和每日商户额度共同限制模型调用；预算单位与提供商实际计费 token 分开记录。

### 故障降级与事件通知

超出 Agent 并发容量或聊天频率限制返回 `429 / AI_CAPACITY`；转发请求连接 Agent 超时、被拒绝或地址解析失败返回 `503 / AI_UNAVAILABLE`；响应读取超时或 Agent 返回 504 时映射为 `504 / AI_TIMEOUT`。提供商失败以及模型调用中的预算/额度耗尽通常返回 HTTP 200、`outcome=degraded` 和降级提示，不能统称为 429/504。顾客仍可使用普通菜单交易流程。

订单事件在事务内持久化，提交后尝试通过 SSE 推送，连接数与队列均有上限；断线重连按游标补读，实时推送失败不影响订单，页面列表作为最终状态依据。催单设置六十秒冷却，仅记录次数、时间和事件，不向真实商家发送外部消息。

## 🛡️ 认证与数据边界

浏览器使用 `HttpOnly` Cookie，不向页面返回 JWT，也不将凭证保存到 Web Storage；Cookie 写请求需要 `X-XSRF-TOKEN`。JWT 校验签发方、受众、有效期及账号状态和 token 版本；退出、改密码与停用账号使相应旧凭证失效。登录与注册同时受 CSRF 和数据库限流保护。

菜品、订单、草稿、库存、事件和审计由后端核验商户归属。老板管理本店菜单和员工，店员处理本店订单，平台管理员管理商户。公网部署还需关闭演示种子、更换弱密码并启用 HTTPS，详见 [安全部署要求](docs/operations.md#公网与认证)。

## 📚 接口与监控

| 范围 | 主要入口 |
|---|---|
| 校园商品与交易 | `/campuses`、`/addresses`、`/campus-menu/{merchant}`、`/cart`、`/checkouts`、`/coupons`、`/aftersales` |
| 校园履约与事件 | `/reservations/{merchant}`、`/delivery-tasks`、`/me/events`、`/me/events/replay` |
| 校园 AI | `/campus-assistant/search`、`/campus-assistant/recommend`、`/campus-assistant/chat` |
| 认证与身份 | `/auth/*`、`/admin/login` |
| 菜单与约束 | `/merchants`、`/dish/list`、`/user/preferences`、`/order/safety-context` |
| 草稿与交易 | `/order/drafts`、`/order/drafts/{id}/confirm`、`/order/{seq}/pay`、`/order/{seq}/cancel` |
| 订单与通知 | `/order/list`、`/order/events`、`/order/events/replay` |
| 商户管理 | `/admin/dishes`、`/admin/orders`、`/admin/merchant`、`/admin/staff`、`/admin/stats`、`/admin/audit` |
| 平台管理 | `/platform/merchants`、`/platform/users/{id}/enabled`、`/platform/audit` |
| AI | 网关 `/chat`；Agent 接口仅供内部调用 |
| 健康与指标 | `/actuator/health/readiness`；网关指标支持内部密钥或平台管理员身份，Agent 指标需要内部密钥 |

浏览器业务写接口通过会话 Cookie 认证并携带 CSRF，登录/注册不要求已有身份；版本和幂等参数按具体接口要求提交。内部 Bearer 回调无会话 Cookie 时不要求 CSRF。具体请求契约见 [控制器源码](java-gateway/src/main/java/com/ai/assistant/controller)。

## ✅ 自动化验证与实测

基础验证命令如下。Java 集成测试需要 Docker；Python 需使用安装了固定依赖的 Python 3.13 环境。

```powershell
node scripts/check-static-js.mjs
Push-Location java-gateway
mvn clean test
Pop-Location
Push-Location agent-service
python -m unittest discover -s tests -v
python -m compileall -q app tests evals
Pop-Location
```

测试按边界分工：单元测试保护规则与错误分类，真实 MySQL/Flyway 测试保护事务、行锁和迁移，HTTP 测试保护认证及商户隔离，浏览器测试保护页面流程。保留这些不同职责的验证，不绑定提示词原文或评测集固定条数。

最近完整审查复验：**2026-10-15**，对应本轮代码与依赖合并。

| 检查 | 实测结果 |
|---|---|
| Java / Python | Java 全量 92 项通过、零跳过；后续新增并发回归及相关 3 项 MySQL 测试通过；最终锁定依赖的 Agent 镜像中 Python 70 项通过 |
| 页面与配置 | 9 个 JS 模块、5 个页面静态/CSP 约束及 Compose 配置通过；校园四角色 18 个、原页面 23 个业务检查点通过；最终镜像 UI 检查 207 个通过，无脚本错误 |
| 真实模型 | API 三阶段 10 轮对话保留失败记录，最终定向推荐→加购→双券报价通过；浏览器另有 2 轮真实报价，最终实付 21.50 元与 Java 和页面一致，未擅自创建订单 |
| HTTP（前次审查基线） | 独立校园业务 21 个、履约 12 个、旧入口并发 15 个、权限隔离 34 个检查点通过；检查点数不等同独立测试场景数 |
| 混合负载（前次审查基线） | 6 并发、每人 3 轮，54 读 / 144 写；读取 P95 13.43ms、写入 P95 105.06ms，零意外错误；只代表该基线的本机场景，未作为新版压测结果 |
| 故障与一致性 | 前次审查停止隔离 Agent 后对话 503、手动跨店交易可用；本次覆盖工作区恢复、异步改券与旧购物车版本拒绝；数据库不变量见记录 |
| 正式部署与清理 | 六个服务保留，四个核心服务健康，两个监控目标正常；所选历史业务指纹一致。测试容器及旧应用镜像已清理，仅保留最终两份应用镜像，归档和数据卷保留 |
| 安全扫描 | 前次审查网关系统包及 Java 依赖高危/严重为零，Agent 44 项暂无修复版本；最终提交的扫描结果以对应 SHA 的 Security Actions 为准，并非零漏洞承诺 |

详细问题、修复和证据见 [审查复验](docs/verification/2026-10-15/审查复验.md) 与 [机器可读结果](docs/verification/2026-10-15/audit-results.json)。本轮使用真实数据库等待关系复现死锁后修复锁序和当前读，保留业务断言；SSE 测试改为固定观察期限，未通过放宽超时或盲目重跑掩盖失败。

本次真实模型、并发修复与最终镜像证据见 [界面与真实模型复验](docs/verification/2026-10-15/界面与真实模型复验.md)。首次扩展的迁移、升级前后恢复演练见 [校园平台验收](docs/verification/2026-10-15/校园平台验收.md)；[2026-10-11 记录](docs/verification/2026-10-11/重构与部署验收.md) 和 [2026-10-07 记录](docs/verification/2026-10-07/验收报告.md) 属于历史版本。当前提供商的指定校园链路已经实测，公网 TLS、长期 SLO、最大容量及模型回答可靠率尚未验证；最终提交的功能 CI 和安全扫描以顶部 Actions 链接为准。

## 💾 备份与恢复

启动升级前备份，运行后每日备份，通常保留最近七份，未通过校验的旧备份不自动删除。恢复脚本在独立临时 MySQL 中核验 SHA-256、恢复导入和订单明细归属，并记录订单/库存等指纹；只有传入 `-SourceProject` 才与源库指纹比较，不覆盖正式库。这不是全库逐字段一致性验证。

```powershell
.\scripts\backup-db.ps1
.\scripts\restore-db.ps1 -BackupFile .\backups\orders-时间戳.sql
```

本地配置和备份均被 Git 忽略。不要对业务数据卷运行 `docker compose down -v` 或全局清理；旧应用镜像删除前先归档、校验，并核实其他容器引用。跨版本回滚需配套数据库备份，操作见 [备份与清理指南](docs/operations.md#备份恢复与清理)。

## ❓ 使用边界

- **没有模型密钥**：普通点餐可用；Agent 运行时部分明确购物车指令和首次静态 FAQ 可直接处理。一般模型推荐需要兼容接口。Agent 停止时，需要转发的对话失败，Java 本地的过敏澄清和受约束菜单推荐仍可返回。
- **公网部署**：提供可选 Caddy 配置，本机验收没有验证实际域名的证书签发与公网 TLS。
- **交易范围**：支付和退款为模拟；已有店铺券、平台券、跨店结算及骑手抢单/人工改派。真实支付、真实商户结算、自动派单、地图定位、自动入驻和单商户多门店尚未实现。
- **运行规模**：当前是单应用/单 Agent 演示部署；备份位于同机，外部告警通知尚未配置，需要另存异机备份。

## 🤝 开发约定

业务事实和事务规则放在 Java 服务中；Python 使用显式工具协议和普通辅助函数，保持流程容易阅读。新增测试应保护行为和边界；改动数据库需提供 Flyway 迁移，不能通过放宽断言掩盖失败。提交前完成受影响的验证，配置、凭证、原始敏感日志及本地测试数据不入库。
