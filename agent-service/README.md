# agent-service — LangGraph 点餐助手 Agent

AI 点餐助手的 Python 端：FastAPI + LangGraph，通过 Function Calling 调用 Java 后端
（`java-gateway`）完成菜单浏览、下单、查单、取消、催单，并带一个本地关键词 RAG 知识库
回答常见问题。**无数据库、无 Redis、无 JWT。**

## 技术栈

- FastAPI / Uvicorn
- LangGraph（工具调用编排）
- OpenAI SDK（OpenAI 兼容 API，可换 DeepSeek / 国产大模型 / Ollama）
- httpx（回调 Java 后端）

## 目录结构

```
agent-service/
├── requirements.txt
├── environment.yml      # conda 环境定义（python 3.13 + pip 依赖）
├── run-agent.bat/.sh    # 自动使用 conda 环境 ai-order-agent 启动
├── .env.example         # 复制为 .env 后填写
└── app/
    ├── main.py          # FastAPI: /health、/chat
    ├── config.py        # .env 配置
    ├── schemas.py       # 请求/响应模型
    ├── gateway/java_client.py  # 调 Java 后端，解析 Result 信封
    ├── rag/             # 本地 FAQ 知识库（关键词 + bigram 评分）
    └── agent/           # LangGraph 编排（prompts / llm / tools / graph）
```

## 快速开始

```bash
cd agent-service
conda env create -f environment.yml      # 或用 requirements.txt
cp .env.example .env                     # 填 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL
./run-agent.sh                           # Windows 用 run-agent.bat
```

监听 `:8800`。需先启动 Java 后端 `java-gateway`（`:9090`）。

## 接口

- `GET /health` → `{"status":"ok"}`
- `POST /chat`，请求体：

```json
{
  "userId": 1,
  "message": "我要一份鱼香肉丝饭",
  "history": [{"role": "user", "content": "..."}]
}
```

响应：`{reply, citations?, toolCalls?}`

## 工具清单（全部回调 Java 后端）

| 工具 | 说明 | Java 接口 |
|---|---|---|
| list_menu | 查看菜单 | `GET /dish/list` |
| place_order | 下单（items 用菜单菜名） | `POST /order/place` |
| query_orders | 订单列表（可按状态） | `GET /order/list` |
| get_order_detail | 订单详情 | `GET /order/{id}` |
| cancel_order | 取消订单 | `POST /order/{id}/cancel` |
| remind_order | 催单 | `POST /order/{id}/remind` |
| search_faq | 常见问题 RAG | 本地知识库 |
