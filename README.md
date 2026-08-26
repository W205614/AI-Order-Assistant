# AI 点餐助手 🍜🤖

基于 **LangGraph + FastAPI（AI Agent）+ Spring Boot（Java 后端）** 的智能点餐助手：用户用自然语言点餐，
Agent 理解意图并驱动后端完成 **下单 / 查单 / 取消 / 催单**，管理端负责订单流转与菜品管理。
内置 **JWT 双端鉴权**、**订单按用户隔离**、**严格状态机** 与 **本地 RAG 知识库**。


## 🧭 项目整体逻辑

```
用户聊天页 (注册/登录 + AI 对话点餐)
        │
        ▼
┌─ Java 后端 (Spring Boot :9090) ─────────────────────────┐
│  POST /chat  (Authorization: 用户JWT)                    │
│   ├─ 校验 JWT → 取 userId → 转发给 Agent                 │
│   ├─ 业务接口: /dish 菜单、/order 下单/查单/取消/催单       │
│   │   订单按 user_id 隔离，订单号每用户从 1 开始            │
│   └─ 管理端 /admin: 订单严格流转 + 菜品上/下架             │
└──────────┬──────────────────────────────────────────────┘
           │  {userId, jwtToken, message, history}
           ▼
┌─ Python Agent (FastAPI + LangGraph :8800) ──────────────┐
│  LLM 意图识别 + Function Calling                          │
│  工具: list_menu → place_order → query_orders            │
│        → cancel_order → remind_order → search_faq(RAG)  │
│  工具回调 Java 时携带用户 JWT（按用户隔离）                  │
└──────────────────────────────────────────────────────────┘
```

**一句话理解**：用户在聊天页用自然语言点餐 → Agent 用 LLM 把话「翻译」成一次下单/查单/催单动作 →
回调 Java 后端完成真实业务（写 MySQL），下单前会**先向用户确认清单和金额**；订单状态由管理端人工推进，数据全部持久化。


## ✨ 功能特点

- 🔐 **JWT 双端鉴权**：用户、管理员独立账号体系（bcrypt 密码哈希），接口按角色隔离
- 🧑🤝🧑 **订单按用户隔离**：每个用户只看到自己的订单，越权访问被拒；**订单号每用户从 1 开始**
- ⚙️ **严格状态机**：已下单 → 制作中 → 配送中 → 已送达，**不可跳步/回退**，可取消、可标记超时
- 🤖 **LangGraph Agent 编排**：LLM 决策 → 工具执行 → 循环，多步工具链自动串联
- ✅ **下单前确认**：Agent 先汇总「菜品+数量+金额」，用户确认后才真正下单
- 🧠 **本地 RAG 知识库**：退款 / 配送 / 超时等常见问题，关键词检索并带来源引用（**无需 embedding key**）
- 🍽 **动态菜单**：搜索、按分类/口味筛选、售罄标记；管理端可上架/下架（售罄的菜不可下单）
- 📅 **按日期查单**：今天 / 昨天 / 自定义日期范围
- 🛡 **数量限制**：单次下单 ≤20 种、单个菜品 ≤99 份
- 🎨 **现代化页面**：移动端卡片式聊天页（对话点餐 + 我的订单）+ 管理端控制台（订单 5 秒自动刷新）


## 🏗️ 技术栈

### Java 后端（`java-gateway`）
- **框架**：Spring Boot 3.2
- **持久化**：Spring JDBC（JdbcTemplate）+ MySQL 8
- **鉴权**：手写 HS256 JWT + Spring Security Crypto（BCrypt）

### Python Agent（`agent-service`）
- **编排**：FastAPI + LangGraph（StateGraph 工具循环）
- **LLM**：OpenAI 兼容接口（DeepSeek / OpenAI / 国产模型 / Ollama，改 `.env` 即可换）
- **RAG**：本地关键词 + bigram 评分检索（无向量库、无 embedding 依赖）

### 前端
- 原生 HTML / CSS / JS 单文件页面（用户端 + 管理端），无构建依赖


## 🏛️ 架构分层

```
┌─────────────────────────────────────────────────────┐
│  Controller 层（Java）                                │
│  AuthController / DishController / OrderController   │
│  ChatController / AdminController                    │
└───────────┬───────────────────────────┬─────────────┘
            │                           │
┌───────────▼──────────┐   ┌───────────▼─────────────┐
│  鉴权层（Java）        │   │  Agent 层（Python）       │
│  JwtUtil / AuthService│   │  LangGraph StateGraph:   │
│  AuthInterceptor      │   │  agent_node → tools_node │
│  UserContext(ThreadLocal)│ │  → agent_node(循环)      │
└───────────┬──────────┘   └───────────┬─────────────┘
            │                           │
┌───────────▼──────────┐   ┌───────────▼─────────────┐
│  业务服务层（Java）     │   │  工具层（Python）          │
│  OrderService         │   │  java_client(httpx,带JWT)│
│  (下单/状态机/菜品)     │   │  rag/faq_store 关键词检索 │
└───────────┬──────────┘   └──────────────────────────┘
            │
┌───────────▼──────────┐
│  数据层                │
│  MySQL: user / admin_user / dish / orders / order_item │
└───────────────────────┘
```


## 📁 项目结构

```
AI-Order-Assistant/
├── java-gateway/                  # Java 后端
│   └── src/main/
│       ├── java/com/ai/assistant/
│       │   ├── security/         # JWT / BCrypt / 拦截器 / UserContext
│       │   ├── controller/       # auth / dish / order / chat / admin
│       │   ├── service/          # OrderService（下单/状态机/菜品）
│       │   ├── model/            # Dish / Order / OrderItem
│       │   ├── dto/  vo/  client/  config/
│       │   └── properties/       # 配置映射
│       └── resources/
│           ├── schema.sql        # 建表（启动自动执行）
│           └── static/           # 用户端 /chat、管理端 /admin、首页
└── agent-service/                # Python Agent
    ├── app/
    │   ├── main.py               # FastAPI /health /chat
    │   ├── agent/                # LangGraph（prompts/llm/tools/graph）
    │   ├── gateway/java_client.py# 回调 Java（带 JWT）
    │   └── rag/                  # 本地 FAQ 知识库
    ├── environment.yml / requirements.txt / run-agent.bat/.sh
    └── .env.example              # 配置 LLM / Java 地址
```


## 🚀 快速开始

### 前提条件

- **MySQL**（需自己在配置里填账号密码）
- **JDK 17+ / Maven**
- **conda**（Python 环境）
- **LLM API Key**（OpenAI 兼容接口）

### 1. 配置 MySQL

在 `java-gateway/src/main/resources/application.yml` 的 `spring.datasource` 中**填写你自己的数据库账号密码**：

```yaml
spring:
  datasource:
    username: 你的数据库用户名
    password: 你的数据库密码
    url: jdbc:mysql://你的主机:3306/ai_order_assistant?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
```

首次启动会自动创建数据库、建表并初始化菜单。

### 2. 启动 Python Agent（:8800）

```bash
cd agent-service
conda env create -f environment.yml      # 或 conda create -n ai-order-agent python=3.13 -y && pip install -r requirements.txt
cp .env.example .env                     # 填写 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL
./run-agent.sh                           # Windows 用 run-agent.bat（自动使用 conda 环境）
```

> Agent 通过 `.env` 的 `JAVA_BASE_URL` 连 Java 后端（默认 `http://localhost:9090`）。

### 3. 启动 Java 后端（:9090）

```bash
cd java-gateway
mvn spring-boot:run
```

### 4. 使用

| 页面 | 地址 | 登录 |
|---|---|---|
| 用户端（注册/登录 + AI 点餐 + 我的订单） | http://localhost:9090/chat/ | 自行注册 |
| 管理端（订单 + 菜品管理） | http://localhost:9090/admin/ | 默认 `admin / admin123` |


## 📝 使用指南

1. 打开用户端，**注册**自己的账号（用户名 + 密码 + 昵称）
2. 用自然语言点餐：「看看菜单」「我要一份鱼香肉丝饭」「推荐点清淡的」
3. Agent 会先**列出清单与金额让你确认**，确认后才下单
4. 「我的订单」页可查看状态、按今天/昨天筛选、催单、取消
5. 管理端登录后：新订单会在 **5 秒内自动出现**；对订单**接单 → 备餐 → 配送 → 送达**（严格单向），或取消 / 标记超时
6. 管理端可对菜品**上架 / 下架**，售罄的菜用户端不可下单


## 🔧 核心实现

### LangGraph 工具循环

```python
from langgraph.graph import StateGraph, START, END

builder = StateGraph(AgentState)
builder.add_node("agent", agent_node)   # LLM 决策：回复 or 工具调用
builder.add_node("tools", tools_node)   # 执行工具，回调 Java
builder.add_edge(START, "agent")
builder.add_conditional_edges("agent", should_continue, {"tools": "tools", "end": END})
builder.add_edge("tools", "agent")
graph = builder.compile()
```

### 订单严格状态机（Java）

```java
private static final Map<Integer, List<Integer>> ALLOWED_TRANSITIONS = Map.of(
    Order.STATUS_ORDERED,   List.of(Order.STATUS_PREPARING, Order.STATUS_CANCELLED, Order.STATUS_TIMEOUT),
    Order.STATUS_PREPARING, List.of(Order.STATUS_DELIVERING, Order.STATUS_CANCELLED),
    Order.STATUS_DELIVERING, List.of(Order.STATUS_DONE, Order.STATUS_TIMEOUT));
// 状态 1已下单 2制作中 3配送中 4已送达 5已取消 6已超时
```

### 订单号按用户独立

```sql
-- 下单时：每个用户从 1 开始
SELECT COALESCE(MAX(user_seq), 0) + 1 FROM orders WHERE user_id = ?;
-- 查询/取消/催单按 (user_id, user_seq) 定位，天然隔离
```

### JWT 鉴权

- 用户 / 管理员分开密钥（`application.yml` 的 `auth.*`），HS256 签名
- `AuthInterceptor` 拦截 `/dish`、`/order`、`/chat`（用户）与 `/admin`（管理员）
- 当前用户写入 `UserContext`（ThreadLocal），请求结束清理


## 🛡️ 安全设计

| 层级 | 手段 |
|---|---|
| 密码 | BCrypt 哈希存储 |
| 传输 | 每次请求带 JWT（`Authorization`），校验签名与过期时间 |
| 数据 | 订单按 `user_id` 隔离，越权访问返回「找不到订单」 |
| 角色 | 用户与管理员独立密钥与接口，管理端接口仅管理员 token 可调 |
| 输入 | 下单数量上限（≤20 种、≤99 份）、菜品售罄校验、DTO 校验 |
| 部署 | JWT 密钥、数据库密码均需在配置中自行修改，不写入仓库 |


## 📚 API 文档

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/register` | 用户注册 | 公开 |
| POST | `/auth/login` | 用户登录 | 公开 |
| POST | `/admin/login` | 管理员登录 | 公开 |
| GET | `/dish/list` | 菜单（含售罄状态） | 用户 |
| POST | `/order/place` | 下单 | 用户 |
| GET | `/order/list` | 我的订单（状态/日期筛选） | 用户 |
| GET | `/order/{seq}` | 订单详情（seq 为本人订单号） | 用户 |
| POST | `/order/{seq}/cancel` | 取消订单 | 用户 |
| POST | `/order/{seq}/remind` | 催单 | 用户 |
| GET | `/admin/orders` | 全部订单（含用户昵称） | 管理员 |
| GET | `/admin/orders/{id}` | 订单详情（全局 id） | 管理员 |
| POST | `/admin/orders/{id}/status` | 更新订单状态（严格单向） | 管理员 |
| GET/POST/PUT/DELETE | `/admin/dishes...` | 菜品增删改、上下架 | 管理员 |


## ❓ 常见问题

**Q1: 换 LLM 模型怎么改？**
编辑 `agent-service/.env` 三个参数：`LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL`，无需改代码。

**Q2: 为什么用户端「订单 #」从 1 开始，管理端却看到大编号？**
用户端显示的是**该用户自己的订单号**（`user_seq`，每用户从 1 开始）；管理端显示的是**全局自增 id**，两者并存不冲突。

**Q3: 服务启动时日志报数据库连接失败？**
检查 `application.yml` 的 `spring.datasource` 是否填了你自己的账号密码、MySQL 是否已启动。

**Q4: 管理端看不到新订单？**
订单面板每 5 秒自动刷新；若仍不显示，点「刷新」或确认订单属于登录的管理员可见范围。


## ⚠️ 已知局限与后续优化方向

1. **用户体系较基础** 🟡：仅账号密码，无微信/OAuth 登录、无找回密码、无 token 刷新
2. **无支付** 🟡：下单即生效，未接支付流程
3. **菜品无图片/库存** 🟡：有上/下架，但无图片、无实时库存联动
4. **实时推送** 🟡：管理端用轮询（5s）刷新，未用 WebSocket/SSE 做即时推送
5. **RAG 为关键词式** 🟢：轻量离线，不支持语义模糊检索；如需可升级向量 RAG
6. **无自动化测试** 🟢：尚未接入 CI / 接口测试


## 🤝 贡献指南

欢迎提交 Issue 或 Pull Request。


## 📚 文档和资源

- [LangGraph](https://github.com/langchain-ai/langgraph) - Agent 编排框架
- [FastAPI](https://fastapi.tiangolo.com/) - Python Web 框架
- [Spring Boot](https://spring.io/projects/spring-boot) - Java 后端框架
- [OpenAI 兼容接口](https://platform.openai.com/docs) - LLM 接入协议（可走 DeepSeek / 国产 / Ollama）
