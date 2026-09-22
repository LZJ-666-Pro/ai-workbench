# ai-workbench

一套 AI 应用底座，正演进为一个**企业级 AI 管理平台**（Java 全栈 / Spring Boot 3 + LangChain4j + Vue3）。

> 愿景：一个企业管理控制台 + 一个可执行任务的智能体
> —— 智能体通过工具中心调用后台接口执行任务（查询、交易、知识检索），可操作数据库，
> 并受企业级约束：登录鉴权、工具级权限、审计落库、不可逆操作人工审批（HITL）

## 架构

```
前端（Vue3 企业管理控制台，Phase 2 改造布局）
        │  统一 /api 入口
platform-core（智能体运行时 + 平台横切能力）
  ├─ Agent 运行时：AgentSpec 注册 / AiServices 装配 / SSE 流式 / 会话记忆持久化
  ├─ 工具中心：各业务模块注册 @Tool，Agent 调用后执行 → Service → 事务 → DB
  └─ 横切（Phase 2 补齐）：登录鉴权 → 工具级权限 → 审计 → 审批(HITL)
业务模块：app-bank（交易）/ app-knowledge（RAG）/ app-interview（面试）
```

**核心设计原则「读宽写窄」**：AI 可以获得受限的只读查询能力（白名单表 + 强制 LIMIT）做分析报表；
但写操作永远不给裸 SQL，必须走业务 Service（规则校验 + 事务）+ 人工确认。

## 模块结构

| 模块 | 说明 | 端口 |
|---|---|---|
| platform-core | LLM 接入、SSE 流式、会话记忆、Agent 框架、RAG 配置位、可观测雏形 | - |
| app-bank | Phase 1 旗舰：带风控的银行交易 Agent（数据已落库 MySQL） | 8081 |
| app-knowledge | Phase 3：多源知识库路由 Agent（当前占位） | 8082 |
| app-interview | Phase 3 后：AI 面试模拟器（当前占位，已可对话） | 8083 |
| frontend | Vue3 + Vite 控制台，一个前端对接三个应用 | 5173 |

> Phase 2 将把三个应用合并为单一 `app-platform`（Maven 模块保留，代码组织不变）。

## 快速开始

前置：JDK 21、Maven 3.9+、Docker。

```bash
# 1. 在项目根目录创建 .env（已被 gitignore，模板见下），Spring 经 spring-dotenv 读取，
#    docker compose 也读同一份——一套变量两边生效
#    MYSQL_PASSWORD=204512
#    GLM_API_KEY=xxxxxxxx.xxxxxxxx

# 2. 启动中间件（MySQL 必需；Redis/Postgres 为后续阶段预留）
#    注意：容器 MySQL 映射在 13306（本机 3306 已被 Windows 服务占用）
docker compose up -d

# 3. 构建并运行旗舰应用
mvn -DskipTests package
mvn spring-boot:run -pl app-bank

# 4. 启动前端
cd frontend && npm install && npm run dev
#    打开 http://localhost:5173 → 目录页进入各 Agent
```

换模型只改环境变量（默认 GLM）：`AI_LLM_BASE_URL` / `AI_LLM_API_KEY` / `AI_LLM_MODEL`，
例如切 DeepSeek：`https://api.deepseek.com/v1` + `deepseek-chat`。默认指向智谱 OpenAI 兼容端点
`https://open.bigmodel.cn/api/paas/v4`（注意不是 anthropic 端点，底座用 OpenAI 协议接入）。

> 本机默认 JDK 是 8，构建/运行需切 JDK 21：`export JAVA_HOME=/d/download/code/jdkVersion/jdk21`

## 接口

- `GET /api/chat/{agent}/stream?memoryId=会话ID&message=输入` — SSE 流式对话
  （事件：`delta` 增量 / `done` 含 token 用量 / `error`）
- `GET /api/chat/agents` — 已注册的 Agent 列表
- `GET /{agent}` 对应应用静态页（目前 app-bank 有聊天 demo 页）

## 技术选型

| 项 | 选型 |
|---|---|
| 基础 | JDK 21 + Spring Boot 3.5.x |
| AI 框架 | LangChain4j 1.20.x（OpenAI 兼容接入，默认 GLM glm-4.7-flash，配置化切换） |
| 存储 | MySQL 8（会话记忆 + 业务数据，容器端口 13306）+ Redis（预留） |
| 向量库 | Postgres + pgvector（知识库阶段启用） |
| 前端 | Vue3 + Vite，fetch + ReadableStream 手解 SSE 帧 |
| env | `.env` + spring-dotenv + docker compose 变量替换，一处定义两端生效 |

## 路线图

- [x] **Phase 0** 平台底座：LLM 配置化接入、SSE 流式、MySQL 会话记忆、Agent 注册/工具框架、token 用量日志、RAG 配置位、docker-compose
- [ ] **Phase 1** 银行交易 Agent：账户/流水落库（已完）→ 转账 + human-in-the-loop 确认卡片、幂等键、审计日志、限额/白名单硬规则
- [ ] **Phase 2** 平台化：合并三应用为 `app-platform`、Spring Security 登录、工具级权限（无权限工具对模型不可见）、平台级审计中心、前端控制台布局（侧边导航 + 全局 AI 助手）、「运营助手」Agent + 只读 SQL 分析工具
- [ ] **Phase 3** 企业文档中心：真实数据源接入、路由 Agent、查询改写、混合检索（向量 + 全文）、引用溯源、评测集与回归脚本
- [ ] **Phase 4** 固化：统一部署、评测补齐、架构图 + 关键决策记录（ADR）、简历叙事

## 设计约定

- 各应用通过声明 `AgentSpec` Bean 注册 Agent，底座负责装配（模型/记忆/工具）
- memoryId 用 `应用名:会话ID` 形式，三个应用共用 chat_memory 表也能隔离
- 换模型只改环境变量，不改代码；RAG 组件按需装配（`ai.embedding.enabled` / `ai.rag.enabled`）
- 确定性约束（限额、权限、幂等）在代码层硬执行，不交给 LLM 自觉
- 「读宽写窄」：AI 的只读能力可以放宽，写能力必须走业务规则 + 人工确认
