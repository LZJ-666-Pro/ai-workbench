# ai-workbench

一套 AI 应用底座 + 三个 Agent 应用（Java 全栈 / Spring Boot 3 + LangChain4j + Vue）。

> 定位：一个 AI 应用平台，三种 Agent 模式
> —— 交易型（HITL 风控）/ 检索型（路由 + RAG）/ 流程型（结构化面试官）

## 模块结构

| 模块 | 说明 | 端口 |
|---|---|---|
| platform-core | LLM 接入、SSE 流式、会话记忆、Agent 框架、RAG 配置位、可观测雏形 | - |
| app-bank | Phase 1 旗舰：带风控的银行交易 Agent（当前为 Phase 0 骨架） | 8081 |
| app-knowledge | Phase 2：多源知识库路由 Agent（当前占位） | 8082 |
| app-interview | Phase 3：AI 面试模拟器（当前占位，已可对话） | 8083 |
| frontend | Vue3 + Vite 单页应用，一个前端对接三个应用 | 5173 |

```
ai-workbench/
├── platform-core/    # 底座
│   ├── config/       # LlmProperties / LlmConfig / EmbeddingProperties / RagProperties
│   ├── agent/        # AgentSpec / AgentRegistry / Assistant（AiServices 装配）
│   ├── api/          # ChatStreamController（SSE：/api/chat/{agent}/stream）
│   ├── memory/       # MysqlChatMemoryStore（会话持久化）
│   ├── rag/          # EmbeddingConfig（向量模型 + pgvector，默认关闭）
│   └── observability/ # LoggingChatModelListener（token 用量/错误日志）
├── app-bank/         # Agent: bank    · 工具: AccountTools（mock 银行数据）
├── app-knowledge/    # Agent: knowledge
├── app-interview/    # Agent: interview
└── frontend/         # Vue3 + Vite：SSE 聊天界面，/xxx/api 前缀代理到各应用
```

## 快速开始

前置：JDK 21、Maven 3.9+、Docker。

```bash
# 1. 配置模型 API Key（默认 DeepSeek，任何 OpenAI 兼容服务都可以）
export AI_LLM_API_KEY=sk-xxxx
# 换厂商示例：export AI_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
#            export AI_LLM_MODEL=qwen-plus

# 2. 启动中间件（MySQL 必需；Redis/Postgres 为后续阶段预留）
docker compose up -d

# 3. 构建
mvn -DskipTests package

# 4. 运行旗舰应用
mvn spring-boot:run -pl app-bank

# 5. 启动前端（推荐）
cd frontend && npm install && npm run dev
#    打开 http://localhost:5173 → 目录页进入各 Agent
#    （不用前端也可以，各应用自带最小演示页，如 http://localhost:8081）
```

接口：
- `GET /api/chat/{agent}/stream?memoryId=会话ID&message=输入` — SSE 流式对话
- `GET /api/chat/agents` — 已注册的 Agent 列表
- `GET /{agent}` 对应应用静态页（目前 app-bank 有聊天 demo 页）

> 注意：本机默认 JDK 是 8，构建/运行时需切到 JDK 21：
> `export JAVA_HOME=/d/download/code/jdkVersion/jdk21`（Windows 原生终端用对应路径）

## 技术选型

| 项 | 选型 |
|---|---|
| 基础 | JDK 21 + Spring Boot 3.5.x |
| AI 框架 | LangChain4j 1.20.x（OpenAI 兼容接入，DeepSeek/Qwen/GLM 配置化切换） |
| 向量库 | Postgres + pgvector（Phase 2 启用） |
| 存储 | MySQL 8（会话记忆/业务数据）+ Redis（预留） |
| 前端 | 原生 HTML/JS SSE demo 页 → Phase 1 换 Vue3 |

## 路线图

- [x] **Phase 0** 平台底座：LLM 配置化接入、SSE 流式、MySQL 会话记忆、Agent 注册/工具框架、token 用量日志、RAG 配置位、docker-compose
- [ ] **Phase 1** 银行交易 Agent：转账 + human-in-the-loop 确认卡片、幂等键、审计日志、限额/白名单硬规则、多 Agent 拆分（路由/查询/交易/知识库 FAQ RAG）
- [ ] **Phase 2** 多源知识库：真实数据源接入（笔记/代码/收藏）、路由 Agent、查询改写、混合检索（向量 + 全文）、引用溯源、评测集与回归脚本
- [ ] **Phase 3** 面试模拟器：简历上传解析 + RAG、结构化评分输出、Markdown 评估报告、前端打磨
- [ ] **Phase 4** 固化：统一部署、评测补齐、架构图 + 关键决策记录、简历叙事

## 设计约定

- 各应用通过声明 `AgentSpec` Bean 注册 Agent，底座负责装配（模型/记忆/工具）
- memoryId 用 `应用名:会话ID` 形式，三个应用共用 chat_memory 表也能隔离
- 换模型只改环境变量，不改代码；RAG 组件按需装配（`ai.embedding.enabled` / `ai.rag.enabled`）
